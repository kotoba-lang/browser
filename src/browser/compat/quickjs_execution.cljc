(ns browser.compat.quickjs-execution
  "Injected QuickJS WASM execution loop.

   The engine is supplied by the host as a function. It represents a WASM
  component instance and returns capability requests instead of receiving
  ambient DOM, network, storage, timer, or process access."
  (:require [browser.audit :as audit]
            [browser.compat.quickjs-binding :as binding]
            [browser.dom-bridge :as dom-bridge]
            [browser.net :as net]
            [browser.origin :as origin]
            [browser.profile :as profile]
            [browser.runtime :as runtime]
            [clojure.string :as str]))

(defn invocation
  ([call payload]
   (invocation call payload []))
  ([call payload capability-results]
   (cond-> (assoc payload :quickjs/call call)
     (seq capability-results)
     (assoc :capability/results (vec capability-results)))))

(defn- invocation-with-snapshots
  "Build an `invocation` carrying the real document snapshot plus the real,
  current `:storage` snapshot (deref'd from the page-lifetime
  `quickjs-execution` atom -- see `browser.compat.quickjs-runner`'s
  `persistent-execution-keys`), the real, current clipboard
  permission-decisions + text snapshot (already computed host-side by
  `clipboard-snapshot`, since unlike storage it needs more than a bare
  deref -- see that fn), the real, current geolocation permission-decision +
  position snapshot (already computed host-side by `geolocation-snapshot`,
  for the same reason), the real, current per-WebSocket-connection inbound
  message snapshot (computed host-side by `websocket-snapshot`, by draining
  each open connection's REAL socket -- see that fn), the real per-Worker
  inbound message snapshot already TAKEN (consumed, not re-derived -- see
  `worker-snapshot` below) from a previous script's real
  `:worker/post-message` replies, and the real per-fetch-request response
  snapshot already TAKEN (consumed, not re-derived -- see
  `fetch-snapshot`/`take-fetch-snapshot` below) from a previous script's
  real `fetch()` calls, so `quickjs-wasm`'s webapi shim can install all of
  them as VM globals before running `payload`'s script (`:document/snapshot`,
  `:storage/snapshot`, `:clipboard/snapshot`, `:geolocation/snapshot`,
  `:websocket/snapshot`, `:worker/snapshot`, `:fetch/snapshot` respectively)
  and answer reads like `localStorage.getItem` / `navigator.clipboard.
  readText`/`writeText` / `navigator.geolocation.getCurrentPosition`
  synchronously from real host state instead of always returning
  `null`/`''`/never calling back, and deliver any real WebSocket/Worker
  messages or `fetch()` responses that genuinely arrived since the script
  that opened the connection/worker/request ran (see
  `websocket-snapshot`/`worker-snapshot`/`fetch-snapshot`) to that
  connection/worker's `onmessage` or that `fetch()` call's pending `.then()`
  chain before this script's own source runs."
  [call payload capability-results document storage clipboard-snapshot geolocation-snapshot websocket-snapshot worker-snapshot fetch-snapshot]
  (let [snapshot (cond-> (dom-bridge/document-snapshot document)
                   (:script/node-id payload)
                   (assoc :current-script (:script/node-id payload)))]
    (invocation call
                (assoc payload
                       :document/snapshot snapshot
                       :storage/snapshot (some-> storage deref)
                       :clipboard/snapshot clipboard-snapshot
                       :geolocation/snapshot geolocation-snapshot
                       :websocket/snapshot websocket-snapshot
                       :worker/snapshot worker-snapshot
                       :fetch/snapshot fetch-snapshot)
                capability-results)))

(defn- take-capability-results
  [state]
  [(:capability/results state)
   (assoc state :capability/results [])])

(defn wasm-engine
  [{:keys [binary manifest invoke dispose] :as opts}]
  {:quickjs.engine/binary binary
   :quickjs.engine/manifest manifest
   :quickjs.engine/manifest-valid? (runtime/valid-manifest? manifest)
   :quickjs.engine/invoke invoke
   :quickjs.engine/dispose dispose
   :quickjs.engine/status :created
   :quickjs.engine/meta (dissoc opts :binary :manifest :invoke :dispose)})

(defn- engine-not-ready-reason
  [engine]
  (cond
    (not (map? engine)) :quickjs/engine-not-ready
    (not (:quickjs.binary/wasm? (:quickjs.engine/binary engine))) :quickjs/invalid-wasm-binary
    (not (:quickjs.engine/manifest-valid? engine)) :quickjs/invalid-runtime-manifest
    (= :disposed (:quickjs.engine/status engine)) :quickjs/engine-disposed
    (not (fn? (:quickjs.engine/invoke engine))) :quickjs/missing-engine-invoke
    :else :quickjs/engine-not-ready))

(defn engine-ready?
  [engine]
  (cond
    (fn? engine) true
    (map? engine) (and (:quickjs.binary/wasm? (:quickjs.engine/binary engine))
                       (:quickjs.engine/manifest-valid? engine)
                       (not= :disposed (:quickjs.engine/status engine))
                       (fn? (:quickjs.engine/invoke engine)))
    :else false))

(defn start-engine
  [engine]
  (if (engine-ready? engine)
    (assoc engine :quickjs.engine/status :ready)
    (assoc engine :quickjs.engine/status :error
                  :quickjs.engine/error (engine-not-ready-reason engine))))

(defn dispose-engine
  [engine]
  (cond
    (fn? engine) engine
    (map? engine) (let [dispose (:quickjs.engine/dispose engine)
                        result (when (fn? dispose)
                                 (dispose engine))]
                    (cond-> (assoc engine :quickjs.engine/status :disposed
                                          :quickjs.engine/invoke nil
                                          :quickjs.engine/dispose nil)
                      (some? result)
                      (assoc :quickjs.engine/dispose-result result)))
    :else engine))

(defn- exception-message
  [e]
  #?(:clj (.getMessage e)
     :cljs (.-message e)))

(defn- invoke-engine-fn
  [f request]
  (try
    (f request)
    (catch #?(:clj Throwable :cljs :default) e
      {:error :quickjs/engine-invoke-failed
       :error/message (exception-message e)
       :requests []})))

(defn invoke-engine
  [engine request]
  (cond
    (fn? engine) (invoke-engine-fn engine request)
    (and (map? engine) (engine-ready? engine)) (invoke-engine-fn (:quickjs.engine/invoke engine) request)
    :else {:error (engine-not-ready-reason engine) :requests []}))

(defn- valid-capability-request?
  [request]
  (and (map? request)
       (keyword? (:capability request))))

(defn normalize-response
  "Normalize a QuickJS engine response before host capability application.

  Unsupported capability keywords remain as host requests so they are recorded
  by the normal unsupported-capability path. Malformed request values are
  removed before they can affect browser state."
  [response]
  (let [raw-response response
        invalid-response? (not (map? response))
        response (if invalid-response?
                   {:error :quickjs/invalid-response
                    :quickjs.response/raw raw-response}
                   response)
        requests (:requests response)
        requests-present? (contains? response :requests)
        request-list (if (sequential? requests) (vec requests) [])
        invalid-requests (keep-indexed
                          (fn [idx request]
                            (when-not (valid-capability-request? request)
                              {:error :quickjs/invalid-capability-request
                               :index idx
                               :request request}))
                          request-list)
        errors (cond-> (if invalid-response?
                         [{:error :quickjs/invalid-response
                           :response raw-response}]
                         (vec invalid-requests))
                 invalid-response?
                 (into (vec invalid-requests))
                 (and requests-present? (not (sequential? requests)))
                 (conj {:error :quickjs/requests-not-sequential
                        :requests requests}))]
    (cond-> (assoc response
                   :requests (filterv valid-capability-request? request-list)
                   :response/valid? (empty? errors))
      (seq errors) (assoc :response/errors errors))))

(defn- record-response-errors
  [state response]
  (let [state (cond-> state
                (:error response)
                (assoc :last-error (:error response)))]
    (if-let [errors (seq (:response/errors response))]
      (-> state
          (assoc :last-error (:error (last errors)))
          (update :response/errors into errors)
          (update :capability/results into
                  (mapv (fn [error]
                          {:capability :quickjs/response
                           :request/id nil
                           :ok? false
                           :error (:error error)
                           :detail error})
                        errors)))
      state)))

(defn- audit-call
  [state call payload response]
  (if-let [audit-log (:audit state)]
    (assoc state :audit
           (audit/append-event
            audit-log
            (audit/quickjs-event {:call call
                                  :url (:url payload)
                                  :specifier (:specifier payload)
                                  :request-count (count (:requests response))
                                  :result (:result response)
                                  :error (:error response)
                                  :profile-id (get-in state [:binding :quickjs/adapter :compat/profile-id])})))
    state))

(defn- present?
  [m k]
  (and (contains? m k)
       (some? (get m k))))

(defn- token-value?
  [value token]
  (contains? (set (str/split (str/lower-case (str value)) #"[,\s]+"))
             token))

(defn- window-open-result
  [request]
  (let [features (:window/features request)
        noopener? (or (token-value? features "noopener")
                      (token-value? features "noreferrer"))]
    (cond-> {:url (:url request)
             :target (str (or (:target request) "_blank"))
             :opener? (not noopener?)}
      (some? features)
      (assoc :window/features features))))

(def permission-capabilities
  {"clipboard-read" :clipboard/read
   "clipboard-write" :clipboard/write
   "geolocation" :geolocation/read
   "notifications" :notification/show
   "fullscreen" :fullscreen/request
   "camera" :media/camera
   "microphone" :media/microphone
   "websocket" :websocket/connect
   "worker" :worker/create
   "beacon" :beacon/send
   "window-management" :window/open})

(defn- permission-name
  [request]
  (some-> (:permission/name request) str/lower-case))

(defn- permission-query-result
  [state request]
  (let [name (permission-name request)
        capability (get permission-capabilities name (keyword name))
        context (:net/context state)
        profile (:profile context)
        request-origin (origin/origin (or (:origin request)
                                          (:page-url context)
                                          (get-in state [:binding :quickjs/adapter :compat/origin])))]
    (if profile
      (let [decision (profile/permission-decision profile request-origin capability)
            granted? (= :allow (:permission/decision decision))]
        {:name name
         :capability capability
         :origin request-origin
         :state (if granted? "granted" "denied")
         :permission/decision decision})
      {:name name
       :capability capability
       :origin request-origin
       :state "denied"
       :permission/decision {:permission/decision :deny
                             :origin request-origin
                             :capability capability
                             :profile/id nil
                             :reason :permission/no-profile}})))

(defn- permission-decision-for
  [state request capability]
  (let [context (:net/context state)
        profile (:profile context)
        request-origin (origin/origin (or (:origin request)
                                          (:page-url context)
                                          (get-in state [:binding :quickjs/adapter :compat/origin])))]
    (if profile
      (profile/permission-decision profile request-origin capability)
      {:permission/decision :deny
       :origin request-origin
       :capability capability
       :profile/id nil
       :reason :permission/no-profile})))

(defn- clipboard-read-result
  [state request]
  (let [decision (permission-decision-for state request :clipboard/read)]
    (if (= :allow (:permission/decision decision))
      {:ok? true
       :result {:text (or (:text @(:clipboard state)) "")}
       :permission/decision decision}
      {:ok? false
       :error (:reason decision)
       :permission/decision decision})))

(defn- clipboard-write-result
  [state request]
  (let [decision (permission-decision-for state request :clipboard/write)]
    (if (= :allow (:permission/decision decision))
      {:ok? true
       :permission/decision decision}
      {:ok? false
       :error (:reason decision)
       :permission/decision decision})))

(defn- clipboard-snapshot
  "Compute the REAL, current clipboard permission decisions (for BOTH
  `:clipboard/read` and `:clipboard/write`) plus the real, current clipboard
  text from `state`'s persisted `:clipboard` atom and the active profile's
  permission grants, host-side, BEFORE the script runs -- so
  `invocation-with-snapshots` can hand it to `quickjs-wasm` as
  `:clipboard/snapshot` and the webapi shim's real
  `navigator.clipboard.readText`/`writeText` can synchronously
  resolve/reject the promise each returns with it (this engine evaluates a
  whole script synchronously in one pass, so there is no realistic way to
  defer that settlement the way a real, async/permission-prompted browser
  would -- see `quickjs-wasm/webapi-shim-source`'s clipboard shim). Mirrors
  `geolocation-snapshot` exactly, one coherent map carrying both the
  permission decisions and the value together (clipboard needs two
  decisions, not one, since read and write are gated independently).

  Uses the SAME `permission-decision-for` gate `apply-capability`'s
  `:clipboard/read`/`:clipboard/write` cases apply (via
  `clipboard-read-result`/`clipboard-write-result`) when they process the
  queued `clipboard/read`/`clipboard/write` capability requests AFTER the
  script runs for the audit trail -- with an empty `request` map, since the
  real `navigator.clipboard` JS shim never sets a custom `:origin` on either
  request either, so the two decisions always agree.

  Returns a single, JSON-safe map: `{:text \"...\" :read {:granted bool
  :error \"...\"} :write {:granted bool :error \"...\"}}` (`:error` only
  present when `:granted` is false, a short human-readable message mirroring
  `geolocation-snapshot`'s error-message building)."
  [state]
  (let [read-decision (permission-decision-for state {} :clipboard/read)
        write-decision (permission-decision-for state {} :clipboard/write)
        read-granted? (= :allow (:permission/decision read-decision))
        write-granted? (= :allow (:permission/decision write-decision))
        text (or (:text (some-> (:clipboard state) deref)) "")
        denial-message (fn [action decision]
                          (str "User denied Clipboard " action " ("
                               (subs (str (or (:reason decision) :permission/not-granted)) 1)
                               ")"))]
    {:text text
     :read (if read-granted?
             {:granted true}
             {:granted false
              :error (denial-message "read" read-decision)})
     :write (if write-granted?
              {:granted true}
              {:granted false
               :error (denial-message "write" write-decision)})}))

(defn- geolocation-result
  [state request]
  (let [decision (permission-decision-for state request :geolocation/read)]
    (if (= :allow (:permission/decision decision))
      (let [position @(:geolocation state)
            coords (select-keys position [:latitude :longitude :accuracy :altitude
                                          :altitudeAccuracy :heading :speed])
            result (cond-> {:coords coords}
                     (:timestamp position)
                     (assoc :timestamp (:timestamp position)))]
        {:ok? true
         :result result
         :permission/decision decision})
      {:ok? false
       :error (:reason decision)
       :permission/decision decision})))

(defn- geolocation-snapshot
  "Compute the REAL, current geolocation permission decision and position
  from `state`'s persisted `:geolocation` atom and the active profile's
  permission grants, host-side, BEFORE the script runs -- so
  `invocation-with-snapshots` can hand it to `quickjs-wasm` as
  `:geolocation/snapshot` and the webapi shim's real
  `navigator.geolocation.getCurrentPosition` can synchronously call
  `success`/`error` with it (this engine evaluates a whole script
  synchronously in one pass, so there is no realistic way to defer the
  callback the way a real, async/permission-prompted browser would -- see
  `quickjs-wasm/geolocation-snapshot-source`).

  Uses the SAME `permission-decision-for` gate `apply-capability`'s
  `:geolocation/read` case applies (via `geolocation-result`) when it
  processes the queued `geolocation/read` capability request AFTER the
  script runs for the audit trail -- with an empty `request` map, since the
  real `navigator.geolocation` JS shim never sets a custom `:origin` on that
  request either, so the two decisions always agree.

  Returns a single, JSON-safe (plain string/number/boolean/nil, no
  keywords -- ready for `jsonable`/`clj->js`) map shaped one of two ways:
  - denied, or no real position was ever injected by the host (i.e. the
    `:geolocation` atom holds no `:latitude`/`:longitude` -- the
    `:geolocation` state key always defaults to a zeroed position via
    `new-state`, so this is really \"a test/host explicitly cleared it\"):
    `{:granted false :error {:code <1|2> :message \"...\"}}`
    (`1`/`\"PERMISSION_DENIED\"` mirrors the real
    `GeolocationPositionError.PERMISSION_DENIED`, `2` mirrors
    `POSITION_UNAVAILABLE`).
  - granted with a real position: `{:granted true :position {:coords {...}
    :timestamp ...}}`, mirroring `geolocation-result`'s own
    `{:coords {...} :timestamp ...}` result shape."
  [state]
  (let [decision (permission-decision-for state {} :geolocation/read)
        granted? (= :allow (:permission/decision decision))
        position (some-> (:geolocation state) deref)
        has-position? (and (map? position)
                           (contains? position :latitude)
                           (contains? position :longitude))]
    (cond
      (not granted?)
      {:granted false
       :error {:code 1
               :message (str "User denied Geolocation ("
                             (subs (str (or (:reason decision) :permission/not-granted)) 1)
                             ")")}}

      (not has-position?)
      {:granted true
       :error {:code 2
               :message "Position unavailable"}}

      :else
      {:granted true
       :position (cond-> {:coords (select-keys position [:latitude :longitude :accuracy :altitude
                                                          :altitudeAccuracy :heading :speed])}
                   (:timestamp position)
                   (assoc :timestamp (:timestamp position)))})))

(defn- websocket-connection-snapshot
  [websocket-fn handle]
  (let [drained (websocket-fn {:op :drain :handle handle})]
    (cond-> {:messages (vec (:messages drained))
             :closed? (boolean (:closed? drained))}
      (:close-info drained)
      (assoc :close-code (:code (:close-info drained))
             :close-reason (:reason (:close-info drained))))))

(defn- websocket-snapshot
  "Compute the REAL, current per-connection inbound-message snapshot from
  `state`'s persisted `:websocket/connections` + `:websocket/handles` (see
  `browser.compat.quickjs-runner`'s `persistent-execution-keys`) and the
  injected `:websocket-fn`, host-side, BEFORE the script runs -- the
  WebSocket analogue of `geolocation-snapshot`.

  For every open connection that has a real handle (i.e. a real
  `:websocket-fn` was injected AND `:websocket/connect` actually opened it
  for real -- see `websocket-connect-result`), calls `:drain` on it (see
  `browser.net.websocket/drain-messages!`): this genuinely pops whatever
  real text has arrived on that real socket since the last time anything
  drained it, waiting a bounded amount of time first if nothing has
  arrived yet (`:clj`) or just peeking at whatever has already been
  buffered by a real `onmessage` callback (`:cljs` -- see that namespace's
  docstring for why it cannot also bound-wait). This is why the JS-visible
  round trip this enables is provable only ACROSS `<script>` tags, never
  within one: a message that left the wire while script 1 was still
  running is, at best, sitting in the real queue by the time script 2's
  snapshot is computed -- there is no way for it to already be there
  mid-script-1.

  Without a `:websocket-fn` (the default), returns `{}` immediately --
  no connections ever have a real handle in that mode, so there is nothing
  to drain, and the webapi shim's delivery step (see
  `quickjs-wasm/websocket-snapshot-source`) is a no-op against an empty
  map, keeping every pre-existing fabricated-mode caller/test unaffected.

  Returns a JSON-safe map keyed by WebSocket id string: `{<id>
  {:messages [...] :closed? bool :close-code <int> :close-reason
  <string>}}` (`:close-code`/`:close-reason` only present once the real
  peer has actually closed)."
  [state]
  (if-let [websocket-fn (:websocket-fn state)]
    (reduce-kv
     (fn [snapshot id _connection]
       (if-let [handle (get (:websocket/handles state) id)]
         (assoc snapshot id (websocket-connection-snapshot websocket-fn handle))
         snapshot))
     {}
     (:websocket/connections state))
    {}))

(defn- worker-snapshot
  "Compute the REAL, current per-Worker inbound-message snapshot from
  `state`'s persisted `:worker/outbox` (see
  `browser.compat.quickjs-runner`'s `persistent-execution-keys`) -- the
  Worker analogue of `websocket-snapshot`, but simpler: unlike a real
  WebSocket's inbound bytes (whose arrival time on the wire is genuinely
  unpredictable, so `websocket-snapshot` has to actively re-`:drain` the
  real socket fresh before every script eval), a real Worker's reply is
  ALREADY captured, in full, host-side, the instant `apply-capability`
  processes the `:worker/post-message` request that produced it (same-
  process, synchronous QuickJS-to-QuickJS delivery -- see
  `worker-create-result`/the `:worker/post-message` case below and
  `browser.compat.quickjs-wasm/worker-fn`'s docstring for exactly why
  there is nothing left to wait for). So this fn does not call back into
  any `:worker-fn` op at all -- it just reads (does not yet clear)
  whatever `apply-capability` already queued into `:worker/outbox` since
  the last time this was taken. See `take-worker-snapshot` for the
  consuming half (mirrors `take-capability-results`): `evaluate!`/
  `load-module!` take (and clear) the snapshot BEFORE invoking the engine
  for the NEXT script, so a message a PREVIOUS script's `worker.postMessage`
  produced a real reply for is delivered into that worker's still-
  registered `worker.onmessage` at the start of THIS script's eval --
  deliberately deferred by one script-tag boundary even though the reply
  was computed earlier, mirroring `websocket-snapshot`'s
  never-same-tick delivery discipline (a real `postMessage` is
  asynchronous even when computationally instant -- see
  `quickjs-execution/worker-create-result`'s docstring).

  Without a `:worker-fn` (the default), `:worker/outbox` never receives
  anything in the first place (see the `:worker/post-message` case below),
  so this returns `{}` -- a no-op against the webapi shim's delivery step,
  keeping every pre-existing fabricated-mode caller/test unaffected.

  Returns a JSON-safe map keyed by Worker id string: `{<id> {:messages
  [...]}}`."
  [state]
  (reduce-kv
   (fn [snapshot id messages]
     (if (seq messages)
       (assoc snapshot id {:messages (vec messages)})
       snapshot))
   {}
   (:worker/outbox state)))

(defn- take-worker-snapshot
  "Consume (read, then clear) `state`'s `:worker/outbox` via
  `worker-snapshot` -- see that fn's docstring for why, unlike
  `websocket-snapshot`, this one needs an explicit take/clear step at all.
  Mirrors `take-capability-results`'s `[value state]` shape."
  [state]
  [(worker-snapshot state) (assoc state :worker/outbox {})])

(defn- jsonable-error
  "Convert a response's `:error` keyword (if any) into a plain, JSON-safe
  string (`:cors/blocked` -> `\"cors/blocked\"`), mirroring the same
  `(subs (str kw) 1)` idiom `geolocation-snapshot` already uses to strip a
  keyword's leading `:` before it crosses into `clj->js`/`JSON.stringify`
  territory (`quickjs-wasm/fetch-snapshot-source`). Every other field a
  `:net/fetch` response carries (`:status` an int, `:headers`/`:body`
  strings) is already JSON-safe as-is."
  [error]
  (when error
    (subs (str error) 1)))

(defn- fetch-response-snapshot-entry
  [response]
  (cond-> (select-keys response [:status :headers :body])
    (:error response) (assoc :error (jsonable-error (:error response)))))

(defn- fetch-snapshot
  "Compute the REAL, current per-fetch-request response snapshot from
  `state`'s persisted `:net/fetch-responses` (see
  `browser.compat.quickjs-runner`'s `persistent-execution-keys`) -- the
  `:net/fetch` analogue of `worker-snapshot`, and for the identical reason:
  a real `:fetch-fn`'s HTTP call (`java.net.http.HttpClient` on the JVM) is
  itself synchronous/blocking, so by the time `apply-capability` finishes
  processing the `:net/fetch` request that produced it, the real response
  already exists in full -- there is nothing left to wait for the way
  WebSocket's genuinely unpredictable-arrival-time inbound bytes make
  `websocket-snapshot` actively `:drain` a real socket. So, exactly like
  `worker-snapshot`, this does not call back into any `-fn` op at all -- it
  just reads (does not yet clear) whatever `apply-capability` already
  queued into `:net/fetch-responses` since the last time this was taken.
  See `take-fetch-snapshot` for the consuming half (mirrors
  `take-worker-snapshot`): `evaluate!`/`load-module!` take (and clear) the
  snapshot BEFORE invoking the engine for the NEXT script, so a `fetch()`
  call a PREVIOUS script made is delivered into that call's still-pending
  `.then()` chain (`quickjs-wasm`'s `__kotobaFetchPending` registry) at the
  start of THIS script's eval -- deliberately deferred by one script-tag
  boundary even though the response was computed earlier, mirroring
  `websocket-snapshot`/`worker-snapshot`'s never-same-tick delivery
  discipline (a real `fetch()` response is asynchronous even when
  computationally instant).

  Without a real `:fetch-fn` (the default -- e.g. every existing test/
  caller that never supplies one), `:net/fetch-responses` never receives
  anything in the first place (see `apply-capability`'s `:net/fetch` case),
  so this returns `{}` -- a no-op against the webapi shim's delivery step,
  keeping every pre-existing fabricated-mode caller/test unaffected: a
  fabricated-mode `fetch()` call's returned thenable is registered but
  simply never resolved/rejected, exactly as real, un-echoed WebSocket
  sends and un-replied Worker messages are today.

  Returns a JSON-safe map keyed by fetch request id string (the JS shim's
  own `fetch-<n>` ids, threaded through as the request's `:request/id` --
  see `apply-capability`'s `:net/fetch` case): `{<id> {:status <int>
  :headers {...} :body <string> :error <string>}}` (`:error` only present
  for a genuine network-level failure -- see `jsonable-error` -- an HTTP
  error status like 404 has no `:error` key, only a non-2xx `:status`,
  mirroring a real browser's `fetch()`, which only REJECTS on network
  failure and still RESOLVES -- with `response.ok === false` -- for HTTP
  error statuses)."
  [state]
  (reduce-kv (fn [snapshot id response]
               (assoc snapshot id (fetch-response-snapshot-entry response)))
             {}
             (:net/fetch-responses state)))

(defn- take-fetch-snapshot
  "Consume (read, then clear) `state`'s `:net/fetch-responses` via
  `fetch-snapshot` -- see that fn's docstring for why, unlike
  `websocket-snapshot`, this one needs an explicit take/clear step at all.
  Mirrors `take-worker-snapshot`'s `[value state]` shape."
  [state]
  [(fetch-snapshot state) (assoc state :net/fetch-responses {})])

(defn- notification-permission-result
  [state request]
  (let [decision (permission-decision-for state request :notification/show)
        allowed? (= :allow (:permission/decision decision))]
    {:state (if allowed? "granted" "denied")
     :permission/decision decision}))

(defn- notification-show-result
  [state request]
  (let [decision (permission-decision-for state request :notification/show)]
    (if (= :allow (:permission/decision decision))
      (let [notification (cond-> {:title (:title request)}
                           (:notification/options request)
                           (assoc :notification/options (:notification/options request)))]
        {:ok? true
         :result notification
         :permission/decision decision})
      {:ok? false
       :error (:reason decision)
       :permission/decision decision})))

(defn- resolve-request-node-id
  [state request]
  (cond
    (:node/id request) (:node/id request)
    (:node/client-id request) (get-in state [:dom/client-ids (:node/client-id request)])
    (:node/selector request) (dom-bridge/query-selector (:document state) (:node/selector request))
    :else nil))

(defn- fullscreen-request-result
  [state request]
  (let [decision (permission-decision-for state request :fullscreen/request)
        node-id (resolve-request-node-id state request)]
    (cond
      (not= :allow (:permission/decision decision))
      {:ok? false
       :error (:reason decision)
       :permission/decision decision}

      (nil? node-id)
      {:ok? false
       :error :fullscreen/missing-target
       :permission/decision decision}

      :else
      {:ok? true
       :result (cond-> {:node/id node-id}
                 (:fullscreen/options request)
                 (assoc :fullscreen/options (:fullscreen/options request)))
       :permission/decision decision})))

(defn- media-constraints
  [request]
  (or (:media/constraints request) {}))

(defn- media-required-capabilities
  [constraints]
  (cond-> []
    (:video constraints) (conj :media/camera)
    (:audio constraints) (conj :media/microphone)))

(defn- media-capture-result
  [state request]
  (let [constraints (media-constraints request)
        capabilities (media-required-capabilities constraints)
        decisions (mapv #(permission-decision-for state request %) capabilities)
        denied (first (filter #(not= :allow (:permission/decision %)) decisions))]
    (cond
      (empty? capabilities)
      {:ok? false
       :error :media/no-tracks-requested
       :permission/decisions decisions}

      denied
      {:ok? false
       :error (:reason denied)
       :permission/decisions decisions}

      :else
      (let [stream (cond-> {:stream/id (str "media-stream-" (inc (count (:media/streams state))))
                            :tracks []}
                     (:video constraints)
                     (update :tracks conj {:kind :video
                                           :capability :media/camera
                                           :constraints (:video constraints)})
                     (:audio constraints)
                     (update :tracks conj {:kind :audio
                                           :capability :media/microphone
                                           :constraints (:audio constraints)}))]
        {:ok? true
         :result stream
         :permission/decisions decisions}))))

(defn- websocket-permission-decision
  [state request]
  (let [context (:net/context state)
        profile (:profile context)
        request-origin (origin/origin (:url request))]
    (if profile
      (profile/permission-decision profile request-origin :websocket/connect)
      {:permission/decision :deny
       :origin request-origin
       :capability :websocket/connect
       :profile/id nil
       :reason :permission/no-profile})))

(defn- websocket-connection-result
  [request id]
  (cond-> {:websocket/id id
           :url (:url request)
           :ready-state :open}
    (:websocket/protocols request)
    (assoc :websocket/protocols (:websocket/protocols request))))

(defn- websocket-connect-result
  "Decide + (if a real `:websocket-fn` is injected -- see `new-state`) open
  a REAL WebSocket connection for a `:websocket/connect` request.

  Without a `:websocket-fn` (the default -- e.g. every existing test that
  never injects one), behaves exactly as before this change: a bare, host-
  side-only fabrication, `:ready-state :open` from the moment the request
  is applied, no real socket. This keeps every pre-existing caller and test
  byte-for-byte unaffected -- the real path below is opt-in.

  With a real `:websocket-fn` injected, calls it with `{:op :connect ...}`
  (see `browser.net.websocket/websocket-fn` for the real, JVM/Node-backed
  implementations). On success, the PUBLIC result map handed back to
  `apply-capability` is identical in shape to the fabricated path (so
  `:context/requests`/`:capability/results` -- the audit-visible log --
  never has to know or care whether the connection behind it is real); the
  real, opaque connection handle is returned separately as `:handle`, for
  `apply-capability` to stash under `:websocket/handles` (kept OUT of the
  audit-visible result map on purpose -- a live socket/Listener object has
  no business in a JSON-safe, printable event log)."
  [state request]
  (let [decision (websocket-permission-decision state request)]
    (if (= :allow (:permission/decision decision))
      (let [id (or (:websocket/id request)
                   (str "websocket-" (inc (count (:websocket/connections state)))))
            websocket-fn (:websocket-fn state)]
        (if websocket-fn
          (let [real (websocket-fn {:op :connect
                                     :url (:url request)
                                     :protocols (:websocket/protocols request)})]
            (if (:ok? real)
              {:ok? true
               :result (websocket-connection-result request id)
               :handle (:handle real)
               :permission/decision decision}
              {:ok? false
               :error (or (:error real) :websocket/connect-failed)
               :permission/decision decision}))
          {:ok? true
           :result (websocket-connection-result request id)
           :permission/decision decision}))
      {:ok? false
       :error (:reason decision)
       :permission/decision decision})))

(def zero-uuid "00000000-0000-4000-8000-000000000000")

(defn- take-random-bytes
  [state length]
  (let [source (vec (take length (concat (:crypto/random-bytes state)
                                         (repeat 0))))]
    [source (update state :crypto/random-bytes #(vec (drop length %)))]))

(defn- take-random-uuid
  [state]
  (let [uuid (or (first (:crypto/random-uuids state)) zero-uuid)]
    [uuid (update state :crypto/random-uuids #(vec (drop 1 %)))]))

(defn- worker-permission-decision
  [state request]
  (let [context (:net/context state)
        profile (:profile context)
        request-origin (origin/origin (:url request))]
    (if profile
      (profile/permission-decision profile request-origin :worker/create)
      {:permission/decision :deny
       :origin request-origin
       :capability :worker/create
       :profile/id nil
       :reason :permission/no-profile})))

(defn- worker-connection-result
  [request id]
  (cond-> {:worker/id id
           :url (:url request)
           :state :running}
    (:worker/options request)
    (assoc :worker/options (:worker/options request))))

(defn- fetch-response-ok?
  [response]
  (and (map? response)
       (not (:error response))
       (integer? (:status response))
       (<= 200 (:status response) 299)))

(defn- worker-create-result
  "Decide + (if a real `:worker-fn` is injected -- see `new-state`) REALLY
  fetch the worker's script source and REALLY evaluate it in a second, real
  QuickJS context, for a `:worker/create` request.

  Without a `:worker-fn` (the default -- e.g. every existing test that
  never injects one, and every JVM caller since the JVM `quickjs-wasm`
  engine has no real second context to offer -- see
  `browser.compat.quickjs-wasm/engine!`), behaves exactly as before this
  change: a bare, host-side-only fabrication, `:state :running` from the
  moment the request is applied, no real script ever runs. This keeps
  every pre-existing caller and test byte-for-byte unaffected -- the real
  path below is opt-in.

  With a real `:worker-fn` injected (see
  `browser.compat.quickjs-wasm/worker-fn`), this REUSES the state's
  existing `:fetch-fn` (the same real, opt-in HTTP capability
  `:net/fetch` already uses -- a worker script is just another URL) to
  really fetch `(:url request)`, then calls `:worker-fn` with `{:op
  :create :url ... :source <real fetched body>}` to really evaluate it in
  a brand-new, real, independent QuickJS context. A missing `:fetch-fn`, a
  real fetch failure, or a real non-2xx status are all real, honest
  failures here (`:worker/no-fetch-fn` / the fetch response's own `:error`
  or `:worker/script-fetch-failed` / `:worker/create-failed`) -- once
  `:worker-fn` opts a caller INTO real mode, this never silently falls
  back to fabricating success the way `:websocket/connect` never does
  either.

  On success, the PUBLIC result map handed back to `apply-capability` is
  identical in shape to the fabricated path (`:context/requests`/
  `:capability/results` -- the audit-visible log -- never has to know or
  care whether the worker behind it is real); the real, opaque worker
  context handle is returned separately as `:handle` (kept OUT of the
  audit log, mirroring `websocket-connect-result`'s `:handle`), and any
  messages the worker script's OWN synchronous top-level execution already
  `postMessage`d are returned as `:messages`, for `apply-capability` to
  queue into `:worker/outbox` (see `worker-snapshot`)."
  [state request]
  (let [decision (worker-permission-decision state request)]
    (if (= :allow (:permission/decision decision))
      (let [id (or (:worker/id request)
                   (str "worker-" (inc (count (:worker/instances state)))))
            worker-fn (:worker-fn state)
            fetch-fn (:fetch-fn state)]
        (cond
          (not worker-fn)
          {:ok? true
           :result (worker-connection-result request id)
           :permission/decision decision}

          (not fetch-fn)
          {:ok? false
           :error :worker/no-fetch-fn
           :permission/decision decision}

          :else
          (let [fetch-response (fetch-fn {:url (:url request) :method :get})]
            (if (fetch-response-ok? fetch-response)
              (let [real (worker-fn {:op :create
                                      :url (:url request)
                                      :source (:body fetch-response)})]
                (if (:ok? real)
                  {:ok? true
                   :result (worker-connection-result request id)
                   :handle (:handle real)
                   :messages (:messages real)
                   :permission/decision decision}
                  {:ok? false
                   :error (or (:error real) :worker/create-failed)
                   :permission/decision decision}))
              {:ok? false
               :error (or (:error fetch-response) :worker/script-fetch-failed)
               :permission/decision decision}))))
      {:ok? false
       :error (:reason decision)
       :permission/decision decision})))

(defn- broadcast-open-result
  [state request]
  {:broadcast/id (or (:broadcast/id request)
                     (str "broadcast-" (inc (count (:broadcast/channels state)))))
   :broadcast/name (:broadcast/name request)
   :state :open})

(defn- beacon-permission-decision
  [state request]
  (let [context (:net/context state)
        profile (:profile context)
        request-origin (origin/origin (:url request))]
    (if profile
      (profile/permission-decision profile request-origin :beacon/send)
      {:permission/decision :deny
       :origin request-origin
       :capability :beacon/send
       :profile/id nil
       :reason :permission/no-profile})))

(defn- beacon-send-result
  [state request]
  (let [decision (beacon-permission-decision state request)]
    (if (= :allow (:permission/decision decision))
      {:ok? true
       :result (cond-> {:url (:url request)}
                 (contains? request :data)
                 (assoc :data (:data request)))
       :permission/decision decision}
      {:ok? false
       :error (:reason decision)
       :permission/decision decision})))

(defn- cookie-context
  [state]
  (let [{:keys [store profile page-url]} (:net/context state)]
    (when (and store profile page-url)
      {:store store
       :profile profile
       :page-url page-url})))

(defn- cookie-get-result
  [state]
  (if-let [{:keys [store profile page-url]} (cookie-context state)]
    {:ok? true
     :result {:cookie/header (or (net/script-cookie-header store profile page-url) "")}}
    {:ok? false
     :error :cookie/no-net-context}))

(defn- cookie-set-result
  [state request]
  (if-let [{:keys [store profile page-url]} (cookie-context state)]
    (let [store (net/script-set-cookie store profile page-url (:cookie/value request))
          header (or (net/script-cookie-header store profile page-url) "")]
      {:ok? true
       :store store
       :result {:cookie/value (:cookie/value request)
                :cookie/header header}})
    {:ok? false
     :error :cookie/no-net-context}))

(defn- history-entry
  [request]
  {:url (:url request)
   :title (:title request)
   :state (:state request)})

(defn- history-push-state-result
  [state request]
  (let [current-index (:history/index state)
        entries (subvec (vec (:history/entries state)) 0 (inc current-index))
        entry (history-entry request)
        entries (conj entries entry)
        index (dec (count entries))]
    {:entries entries
     :index index
     :result {:entry entry
              :index index
              :length (count entries)}}))

(defn- history-replace-state-result
  [state request]
  (let [entry (history-entry request)
        entries (vec (:history/entries state))
        current-index (:history/index state)
        index (if (neg? current-index) 0 current-index)
        entries (if (seq entries)
                  (assoc entries index entry)
                  [entry])]
    {:entries entries
     :index index
     :result {:entry entry
              :index index
              :length (count entries)}}))

(defn- history-traverse-result
  [state request]
  (let [entries (vec (:history/entries state))
        current-index (:history/index state)
        delta (:delta request)
        target-index (+ current-index delta)]
    (if (and (seq entries)
             (<= 0 target-index)
             (< target-index (count entries)))
      {:entries entries
       :index target-index
       :result {:delta delta
                :traversed? true
                :index target-index
                :length (count entries)
                :entry (nth entries target-index)}}
      {:entries entries
       :index current-index
       :result {:delta delta
                :traversed? false
                :index current-index
                :length (count entries)}})))

(defn- location-url
  [state]
  (or (:location/url state)
      (get-in state [:net/context :page-url])
      "about:blank"))

(defn- location-change-result
  [state request kind]
  {:url (:url request)
   :previous-url (location-url state)
   :location/kind kind})

(defn- location-reload-result
  [state]
  {:url (location-url state)
   :location/kind :reload})

(def console-levels
  #{:log :info :warn :error :debug})

(defn- console-log-result
  [request]
  {:console/level (:console/level request)
   :args (:args request)})

(defn- valid-dom-mutation?
  [request]
  (case (:dom/op request)
    :create-element (present? request :tag)
    :create-fragment true
    :clone-node (or (present? request :source/id)
                    (present? request :source/client-id))
    :create-text (contains? request :text)
    :set-title (contains? request :title)
    :set-text (and (or (present? request :node/id)
                       (present? request :node/client-id)
                       (present? request :node/selector))
                   (contains? request :text))
    :split-text (and (or (present? request :node/id)
                         (present? request :node/client-id)
                         (present? request :node/selector))
                     (contains? request :offset))
    :normalize (or (present? request :node/id)
                   (present? request :node/client-id)
                   (present? request :node/selector))
    :set-inner-html (and (or (present? request :node/id)
                             (present? request :node/client-id)
                             (present? request :node/selector))
                         (contains? request :html))
    :set-outer-html (and (or (present? request :node/id)
                             (present? request :node/client-id)
                             (present? request :node/selector))
                         (contains? request :html))
    :set-attribute (and (or (present? request :node/id)
                            (present? request :node/client-id)
                            (present? request :node/selector))
                        (present? request :attr)
                        (contains? request :value))
    :remove-attribute (and (or (present? request :node/id)
                               (present? request :node/client-id)
                               (present? request :node/selector))
                           (present? request :attr))
    :append-child (and (or (present? request :parent/id)
                           (present? request :parent/client-id)
                           (present? request :parent/selector))
                       (or (present? request :child/id)
                           (present? request :child/client-id)))
    :remove-child (and (or (present? request :parent/id)
                           (present? request :parent/client-id)
                           (present? request :parent/selector))
                       (or (present? request :child/id)
                           (present? request :child/client-id)))
    :insert-before (and (or (present? request :parent/id)
                            (present? request :parent/client-id)
                            (present? request :parent/selector))
                        (or (present? request :child/id)
                            (present? request :child/client-id))
                        (or (present? request :before/id)
                            (present? request :before/client-id)
                            (nil? (:before/client-id request))))
    :remove-children (or (present? request :node/id)
                         (present? request :node/client-id)
                         (present? request :node/selector))
    :focus-node (or (present? request :node/id)
                    (present? request :node/client-id)
                    (present? request :node/selector))
    :blur-node (or (present? request :node/id)
                   (present? request :node/client-id)
                   (present? request :node/selector))
    false))

(defn- node-ref?
  [request]
  (or (present? request :node/id)
      (present? request :node/client-id)
      (present? request :node/selector)))

(defn- valid-event-request?
  [request]
  (let [global-target? (contains? #{:window :document "window" "document"}
                                  (:event/target request))]
    (case (:capability request)
    :event/listen (and (or (node-ref? request) global-target?)
                       (present? request :event/type)
                       (present? request :handler/id))
    :event/remove (and (or (node-ref? request) global-target?)
                       (present? request :event/type)
                       (present? request :handler/id))
    :event/dispatch (and (or (node-ref? request) global-target?)
                         (map? (:event request))
                         (present? (:event request) :event/type))
    false)))

(defn- capability-request-error
  [request]
  (case (:capability request)
    :dom/query (case (:dom/query request)
                 :query-selector (when-not (string? (:selector request))
                                   :quickjs/invalid-capability-request)
                 :query-selector-all (when-not (string? (:selector request))
                                       :quickjs/invalid-capability-request)
                 :get-element-by-id (when-not (string? (:id request))
                                      :quickjs/invalid-capability-request)
                 :node (when-not (present? request :node/id)
                         :quickjs/invalid-capability-request)
                 :document nil
                 :quickjs/invalid-capability-request)
    :dom/mutate (when-not (valid-dom-mutation? request)
                  :quickjs/invalid-capability-request)
    :event/listen (when-not (valid-event-request? request)
                    :quickjs/invalid-capability-request)
    :event/remove (when-not (valid-event-request? request)
                    :quickjs/invalid-capability-request)
    :event/dispatch (when-not (valid-event-request? request)
                      :quickjs/invalid-capability-request)
    :console/log (when-not (and (contains? console-levels (:console/level request))
                                (vector? (:args request)))
                   :quickjs/invalid-capability-request)
    :net/fetch (when-not (string? (:url request))
                 :quickjs/invalid-capability-request)
    :storage/get (when-not (present? request :storage/key)
                   :quickjs/invalid-capability-request)
    :storage/put (when-not (and (present? request :storage/key)
                                (contains? request :storage/value))
                   :quickjs/invalid-capability-request)
    :storage/delete (when-not (present? request :storage/key)
                      :quickjs/invalid-capability-request)
    :cookie/get (when-not (= :get (:cookie/op request))
                  :quickjs/invalid-capability-request)
    :cookie/set (when-not (and (= :set (:cookie/op request))
                               (string? (:cookie/value request)))
                  :quickjs/invalid-capability-request)
    :clipboard/read (when-not (= :text (:clipboard/format request))
                      :quickjs/invalid-capability-request)
    :clipboard/write (when-not (and (= :text (:clipboard/format request))
                                    (string? (:text request)))
                       :quickjs/invalid-capability-request)
    :window/open (when-not (and (string? (:url request))
                                (or (not (contains? request :target))
                                    (string? (:target request)))
                                (or (not (contains? request :window/features))
                                    (string? (:window/features request))))
                   :quickjs/invalid-capability-request)
    :location/assign (when-not (string? (:url request))
                       :quickjs/invalid-capability-request)
    :location/replace (when-not (string? (:url request))
                        :quickjs/invalid-capability-request)
    :location/reload (when-not (= :reload (:location/op request))
                       :quickjs/invalid-capability-request)
    :permissions/query (when-not (present? request :permission/name)
                         :quickjs/invalid-capability-request)
    :geolocation/read (when-not (= :current-position (:geolocation/op request))
                        :quickjs/invalid-capability-request)
    :notification/request-permission (when-not (= :request-permission (:notification/op request))
                                       :quickjs/invalid-capability-request)
    :notification/show (when-not (string? (:title request))
                         :quickjs/invalid-capability-request)
    :fullscreen/request (when-not (node-ref? request)
                          :quickjs/invalid-capability-request)
    :fullscreen/exit (when-not (= :exit (:fullscreen/op request))
                       :quickjs/invalid-capability-request)
    :media/capture (when-not (and (= :get-user-media (:media/op request))
                                  (map? (:media/constraints request)))
                     :quickjs/invalid-capability-request)
    :websocket/connect (when-not (string? (:url request))
                         :quickjs/invalid-capability-request)
    :websocket/send (when-not (and (present? request :websocket/id)
                                   (contains? request :data))
                      :quickjs/invalid-capability-request)
    :websocket/close (when-not (present? request :websocket/id)
                       :quickjs/invalid-capability-request)
    :crypto/random-values (when-not (and (= :random-values (:crypto/op request))
                                         (integer? (:length request))
                                         (not (neg? (:length request))))
                            :quickjs/invalid-capability-request)
    :crypto/random-uuid (when-not (= :random-uuid (:crypto/op request))
                          :quickjs/invalid-capability-request)
    :worker/create (when-not (string? (:url request))
                     :quickjs/invalid-capability-request)
    :worker/post-message (when-not (and (present? request :worker/id)
                                        (contains? request :message))
                           :quickjs/invalid-capability-request)
    :worker/terminate (when-not (present? request :worker/id)
                        :quickjs/invalid-capability-request)
    :broadcast/open (when-not (present? request :broadcast/name)
                      :quickjs/invalid-capability-request)
    :broadcast/post-message (when-not (and (present? request :broadcast/id)
                                           (contains? request :message))
                              :quickjs/invalid-capability-request)
    :broadcast/close (when-not (present? request :broadcast/id)
                       :quickjs/invalid-capability-request)
    :beacon/send (when-not (string? (:url request))
                   :quickjs/invalid-capability-request)
    :history/push-state (when-not (and (string? (:url request))
                                       (string? (:title request))
                                       (contains? request :state))
                          :quickjs/invalid-capability-request)
    :history/replace-state (when-not (and (string? (:url request))
                                          (string? (:title request))
                                          (contains? request :state))
                             :quickjs/invalid-capability-request)
    :history/traverse (when-not (integer? (:delta request))
                        :quickjs/invalid-capability-request)
    :timer/schedule (when-not (and (present? request :callback/id)
                                   (number? (:delay-ms request))
                                   (not (neg? (:delay-ms request))))
                      :quickjs/invalid-capability-request)
    :timer/cancel (when-not (present? request :callback/id)
                    :quickjs/invalid-capability-request)
    :timer/microtask (when-not (present? request :callback/id)
                       :quickjs/invalid-capability-request)
    :js/call (when-not (present? request :js/call)
               :quickjs/invalid-capability-request)
    nil))

(defn- apply-capability
  [{:keys [binding document fetch-fn storage] :as state} request]
  (letfn [(record-result [state result]
            (update state :capability/results conj
                    (merge {:capability (:capability request)
                            :request/id (:request/id request)}
                           result)))]
  (if (= false (:compat/request request))
    (-> state
        (assoc :last-error (:error request) :last-denied request)
        (record-result {:ok? false :error (:error request)}))
    (if-let [error (capability-request-error request)]
      (-> state
          (assoc :last-error error)
          (record-result {:ok? false
                          :error error
                          :detail {:request request}}))
    (case (:capability request)
      :dom/query
      (let [{:keys [document result error]} (dom-bridge/handle-request document request)]
        (-> state
            (assoc :document document :last-result result :last-error error)
            (record-result {:ok? (nil? error) :result result :error error})))

    :dom/mutate
    (let [request (cond-> request
                    (:node/client-id request)
                    (assoc :node/id (get-in state [:dom/client-ids (:node/client-id request)]))
                    (:node/selector request)
                    (assoc :node/id (dom-bridge/query-selector document (:node/selector request)))
                    (:parent/client-id request)
                    (assoc :parent/id (get-in state [:dom/client-ids (:parent/client-id request)]))
                    (:child/client-id request)
                    (assoc :child/id (get-in state [:dom/client-ids (:child/client-id request)]))
                    (:before/client-id request)
                    (assoc :before/id (get-in state [:dom/client-ids (:before/client-id request)]))
                    (:source/client-id request)
                    (assoc :source/id (get-in state [:dom/client-ids (:source/client-id request)]))
                    (:parent/selector request)
                    (assoc :parent/id (dom-bridge/query-selector document (:parent/selector request))))
          {:keys [document node/id error]} (dom-bridge/handle-request document request)
          state (cond-> state
                  (:client/id request)
                  (assoc-in [:dom/client-ids (:client/id request)] id))]
      (-> state
          (assoc :document document :last-result id :last-error error)
          (record-result {:ok? (nil? error) :result id :error error})))

    (:event/listen :event/remove :event/dispatch)
    (if-let [target (some-> (:event/target request) keyword)]
      (let [result (cond-> {:event/target target
                            :event/type (or (:event/type request)
                                            (get-in request [:event :event/type]))}
                     (:handler/id request) (assoc :handler/id (:handler/id request))
                     (contains? request :event) (assoc :event/dispatched? true
                                                       :event (:event request)))]
        (-> state
            (cond->
              (= :event/listen (:capability request))
              (assoc-in [:global/listeners target (:event/type request) (:handler/id request)] true)
              (= :event/remove (:capability request))
              (update-in [:global/listeners target (:event/type request)] dissoc (:handler/id request))
              (= :event/dispatch (:capability request))
              (update :global/events conj result))
            (assoc :last-result result)
            (record-result {:ok? true :result result})))
      (let [request (cond-> request
                      (:node/client-id request)
                      (assoc :node/id (get-in state [:dom/client-ids (:node/client-id request)]))
                      (:node/selector request)
                      (assoc :node/id (dom-bridge/query-selector document (:node/selector request))))
            response (dom-bridge/handle-request document request)
            document (:document response)
            error (:error response)
            result (cond-> {:node/id (:node/id response)}
                     (:handler/id response) (assoc :handler/id (:handler/id response))
                     (= :event/remove (:capability request)) (assoc :removed? true)
                     (contains? request :event) (assoc :event/dispatched? (:event/dispatched? response)))]
        (-> state
            (assoc :document document :last-result result :last-error error)
            (record-result {:ok? (nil? error) :result result :error error}))))

    :console/log
    (let [result (console-log-result request)]
      (-> state
          (update :console/messages conj result)
          (assoc :last-result result)
          (record-result {:ok? true :result result})))

    :net/fetch
    (let [context (:net/context state)
          fetch-request {:url (:url request)
                         :method (get-in request [:request :method] :get)
                         :request (:request request)}
          result (if context
                   (net/fetch-resource (assoc context :fetch-fn fetch-fn) fetch-request)
                   (when fetch-fn (fetch-fn fetch-request)))
          response (if context (:response result) result)
          request-id (:request/id request)]
      (cond-> state
        context (assoc :net/context (assoc context :store (:store result)))
        true (assoc :last-result response)
        (:error response) (assoc :last-error (:error response))
        ;; Eager capture, deferred delivery -- mirrors worker-create-result's
        ;; reasoning: a real :fetch-fn's HTTP call already blocked and
        ;; returned by the time we're here, so the real response already
        ;; exists in full. Stash it for fetch-snapshot to hand to the NEXT
        ;; script tag's webapi shim install, which resolves/rejects the
        ;; calling script's still-pending fetch() promise (see
        ;; quickjs-wasm/webapi-shim-source's fetch delivery IIFE). Guarded
        ;; on `response` so fabricated mode (no fetch-fn, no context ->
        ;; response is nil) never touches :net/fetch-responses, and on
        ;; request-id so a request with no id (never happens from the real
        ;; JS shim, which always assigns one, but could from a hand-rolled
        ;; :engine test double) has nowhere to be delivered to.
        (and response request-id) (assoc-in [:net/fetch-responses request-id] response)
        true (record-result {:ok? (boolean (and response (not (:error response))))
                             :result response
                             :error (:error response)})))

    :storage/get
    (let [result (get @storage (:storage/key request))]
      (-> state
          (assoc :last-result result)
          (record-result {:ok? true :result result})))

    :storage/put
    (do
      (swap! storage assoc (:storage/key request) (:storage/value request))
      (-> state
          (assoc :last-result true)
          (record-result {:ok? true :result true})))

    :storage/delete
    (do
      (swap! storage dissoc (:storage/key request))
      (-> state
          (assoc :last-result true)
          (record-result {:ok? true :result true})))

    :cookie/get
    (let [cookie-result (cookie-get-result state)
          ok? (:ok? cookie-result)
          result (:result cookie-result)
          error (:error cookie-result)]
      (cond-> state
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error}
                              (nil? error) (dissoc :error)))))

    :cookie/set
    (let [cookie-result (cookie-set-result state request)
          ok? (:ok? cookie-result)
          result (:result cookie-result)
          error (:error cookie-result)]
      (cond-> state
        (:store cookie-result) (assoc-in [:net/context :store] (:store cookie-result))
        result (update :context/requests conj (assoc result :capability :cookie/set))
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error}
                              (nil? error) (dissoc :error)))))

    :clipboard/read
    (let [clipboard-result (clipboard-read-result state request)
          ok? (:ok? clipboard-result)
          result (:result clipboard-result)
          error (:error clipboard-result)
          decision (:permission/decision clipboard-result)]
      (cond-> state
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error
                                     :permission/decision decision}
                              (nil? error) (dissoc :error)))))

    :clipboard/write
    (let [clipboard-result (clipboard-write-result state request)
          ok? (:ok? clipboard-result)
          error (:error clipboard-result)
          decision (:permission/decision clipboard-result)
          _ (when ok? (swap! (:clipboard state) assoc :text (:text request)))]
      (cond-> state
        true (assoc :last-result ok?)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result ok?
                                     :error error
                                     :permission/decision decision}
                              (nil? error) (dissoc :error)))))

    :window/open
    (let [result (window-open-result request)]
      (-> state
          (update :context/requests conj (assoc result :capability :window/open))
          (assoc :last-result result)
          (record-result {:ok? true :result result})))

    :location/assign
    (let [result (location-change-result state request :assign)]
      (-> state
          (assoc :location/url (:url result)
                 :last-result result)
          (update :context/requests conj (assoc result :capability :location/assign))
          (record-result {:ok? true :result result})))

    :location/replace
    (let [result (location-change-result state request :replace)]
      (-> state
          (assoc :location/url (:url result)
                 :last-result result)
          (update :context/requests conj (assoc result :capability :location/replace))
          (record-result {:ok? true :result result})))

    :location/reload
    (let [result (location-reload-result state)]
      (-> state
          (assoc :last-result result)
          (update :context/requests conj (assoc result :capability :location/reload))
          (record-result {:ok? true :result result})))

    :permissions/query
    (let [result (permission-query-result state request)]
      (-> state
          (assoc :last-result result)
          (record-result {:ok? true :result result})))

    :geolocation/read
    (let [geo-result (geolocation-result state request)
          ok? (:ok? geo-result)
          result (:result geo-result)
          error (:error geo-result)
          decision (:permission/decision geo-result)]
      (cond-> state
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error
                                     :permission/decision decision}
                              (nil? error) (dissoc :error)))))

    :notification/request-permission
    (let [result (notification-permission-result state request)]
      (-> state
          (assoc :last-result result)
          (record-result {:ok? true :result result})))

    :notification/show
    (let [notification-result (notification-show-result state request)
          ok? (:ok? notification-result)
          result (:result notification-result)
          error (:error notification-result)
          decision (:permission/decision notification-result)]
      (cond-> state
        result (update :notification/requests conj result)
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error
                                     :permission/decision decision}
                              (nil? error) (dissoc :error)))))

    :fullscreen/request
    (let [fullscreen-result (fullscreen-request-result state request)
          ok? (:ok? fullscreen-result)
          result (:result fullscreen-result)
          error (:error fullscreen-result)
          decision (:permission/decision fullscreen-result)]
      (cond-> state
        result (assoc :fullscreen/element (:node/id result))
        result (update :context/requests conj (assoc result :capability :fullscreen/request))
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error
                                     :permission/decision decision}
                              (nil? error) (dissoc :error)))))

    :fullscreen/exit
    (let [result {:exited? true
                  :previous-node/id (:fullscreen/element state)}]
      (-> state
          (dissoc :fullscreen/element)
          (update :context/requests conj (assoc result :capability :fullscreen/exit))
          (assoc :last-result result)
          (record-result {:ok? true :result result})))

    :media/capture
    (let [media-result (media-capture-result state request)
          ok? (:ok? media-result)
          result (:result media-result)
          error (:error media-result)
          decisions (:permission/decisions media-result)]
      (cond-> state
        result (update :media/streams conj result)
        result (update :context/requests conj (assoc result :capability :media/capture))
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error
                                     :permission/decisions decisions}
                              (nil? error) (dissoc :error)))))

    :websocket/connect
    (let [connect-result (websocket-connect-result state request)
          ok? (:ok? connect-result)
          result (:result connect-result)
          handle (:handle connect-result)
          error (:error connect-result)
          decision (:permission/decision connect-result)]
      (cond-> state
        result (assoc-in [:websocket/connections (:websocket/id result)] result)
        (and result handle) (assoc-in [:websocket/handles (:websocket/id result)] handle)
        result (update :context/requests conj (assoc result :capability :websocket/connect))
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error
                                     :permission/decision decision}
                              (nil? error) (dissoc :error)))))

    :websocket/send
    (let [connection (get-in state [:websocket/connections (:websocket/id request)])
          handle (get-in state [:websocket/handles (:websocket/id request)])
          websocket-fn (:websocket-fn state)
          result {:websocket/id (:websocket/id request)
                  :data (:data request)}]
      (if connection
        ;; A real handle only exists in real (:websocket-fn-injected) mode
        ;; -- see websocket-connect-result. In fabricated mode (the
        ;; default), this send-result is always {:ok? true}, exactly the
        ;; pre-existing behavior.
        (let [send-result (if (and websocket-fn handle)
                            (websocket-fn {:op :send :handle handle :data (:data request)})
                            {:ok? true})]
          (if (:ok? send-result)
            (-> state
                (update :websocket/messages conj result)
                (assoc :last-result result)
                (record-result {:ok? true :result result}))
            (-> state
                (assoc :last-error (or (:error send-result) :websocket/send-failed))
                (record-result {:ok? false
                                :result nil
                                :error (or (:error send-result) :websocket/send-failed)}))))
        (-> state
            (assoc :last-error :websocket/not-connected)
            (record-result {:ok? false
                            :result nil
                            :error :websocket/not-connected}))))

    :websocket/close
    (let [connection (get-in state [:websocket/connections (:websocket/id request)])
          handle (get-in state [:websocket/handles (:websocket/id request)])
          websocket-fn (:websocket-fn state)
          result (cond-> {:websocket/id (:websocket/id request)
                          :closed? true}
                   (:code request) (assoc :code (:code request))
                   (:reason request) (assoc :reason (:reason request)))]
      (if connection
        (do
          (when (and websocket-fn handle)
            (websocket-fn {:op :close :handle handle
                           :code (:code request) :reason (:reason request)}))
          (-> state
              (assoc-in [:websocket/connections (:websocket/id request) :ready-state] :closed)
              (update :context/requests conj (assoc result :capability :websocket/close))
              (assoc :last-result result)
              (record-result {:ok? true :result result})))
        (-> state
            (assoc :last-error :websocket/not-connected)
            (record-result {:ok? false
                            :result nil
                            :error :websocket/not-connected}))))

    :crypto/random-values
    (let [[bytes state] (take-random-bytes state (:length request))
          result {:bytes bytes}]
      (-> state
          (assoc :last-result result)
          (record-result {:ok? true :result result})))

    :crypto/random-uuid
    (let [[uuid state] (take-random-uuid state)
          result {:uuid uuid}]
      (-> state
          (assoc :last-result result)
          (record-result {:ok? true :result result})))

    :worker/create
    (let [create-result (worker-create-result state request)
          ok? (:ok? create-result)
          result (:result create-result)
          handle (:handle create-result)
          messages (:messages create-result)
          error (:error create-result)
          decision (:permission/decision create-result)]
      (cond-> state
        result (assoc-in [:worker/instances (:worker/id result)] result)
        (and result handle) (assoc-in [:worker/handles (:worker/id result)] handle)
        (and result (seq messages)) (update-in [:worker/outbox (:worker/id result)]
                                                (fnil into []) messages)
        result (update :context/requests conj (assoc result :capability :worker/create))
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error
                                     :permission/decision decision}
                              (nil? error) (dissoc :error)))))

    :worker/post-message
    (let [worker (get-in state [:worker/instances (:worker/id request)])
          handle (get-in state [:worker/handles (:worker/id request)])
          worker-fn (:worker-fn state)
          result {:worker/id (:worker/id request)
                  :message (:message request)}]
      (if worker
        ;; A real handle only exists in real (:worker-fn-injected) mode --
        ;; see worker-create-result. In fabricated mode (the default), this
        ;; deliver-result is always {:ok? true}, exactly the pre-existing
        ;; behavior: no real second context to deliver into, no reply ever
        ;; comes back.
        (let [deliver-result (if (and worker-fn handle)
                                (worker-fn {:op :post-message :handle handle
                                            :data (:message request)})
                                {:ok? true})]
          (if (:ok? deliver-result)
            (-> state
                (update :worker/messages conj result)
                ;; Real replies the worker's script synchronously
                ;; `self.postMessage`d back are captured RIGHT NOW (see
                ;; worker-create-result's docstring for why this is safe
                ;; to do synchronously, unlike WebSocket's inbound data)
                ;; but deliberately queued for delivery at the NEXT script
                ;; tag's snapshot install, never into THIS request's own
                ;; script (already finished running) -- see worker-snapshot.
                ;; Guarded by `seq` so fabricated mode (no real handle, so
                ;; `:messages` is always nil here) never even touches
                ;; `:worker/outbox`, keeping it byte-for-byte absent/empty
                ;; exactly as before this change.
                (cond-> (seq (:messages deliver-result))
                  (update-in [:worker/outbox (:worker/id request)]
                             (fnil into []) (:messages deliver-result)))
                (assoc :last-result result)
                (record-result {:ok? true :result result}))
            (-> state
                (assoc :last-error (or (:error deliver-result) :worker/post-message-failed))
                (record-result {:ok? false
                                :result nil
                                :error (or (:error deliver-result) :worker/post-message-failed)}))))
        (-> state
            (assoc :last-error :worker/not-running)
            (record-result {:ok? false
                            :result nil
                            :error :worker/not-running}))))

    :worker/terminate
    (let [worker (get-in state [:worker/instances (:worker/id request)])
          handle (get-in state [:worker/handles (:worker/id request)])
          worker-fn (:worker-fn state)
          result {:worker/id (:worker/id request)
                  :terminated? true}]
      (if worker
        (do
          (when (and worker-fn handle)
            (worker-fn {:op :terminate :handle handle}))
          (-> state
              (assoc-in [:worker/instances (:worker/id request) :state] :terminated)
              (update :context/requests conj (assoc result :capability :worker/terminate))
              (assoc :last-result result)
              (record-result {:ok? true :result result})))
        (-> state
            (assoc :last-error :worker/not-running)
            (record-result {:ok? false
                            :result nil
                            :error :worker/not-running}))))

    :broadcast/open
    (let [result (broadcast-open-result state request)]
      (-> state
          (assoc-in [:broadcast/channels (:broadcast/id result)] result)
          (update :context/requests conj (assoc result :capability :broadcast/open))
          (assoc :last-result result)
          (record-result {:ok? true :result result})))

    :broadcast/post-message
    (let [channel (get-in state [:broadcast/channels (:broadcast/id request)])
          result {:broadcast/id (:broadcast/id request)
                  :message (:message request)}]
      (if channel
        (-> state
            (update :broadcast/messages conj result)
            (assoc :last-result result)
            (record-result {:ok? true :result result}))
        (-> state
            (assoc :last-error :broadcast/not-open)
            (record-result {:ok? false
                            :result nil
                            :error :broadcast/not-open}))))

    :broadcast/close
    (let [channel (get-in state [:broadcast/channels (:broadcast/id request)])
          result {:broadcast/id (:broadcast/id request)
                  :closed? true}]
      (if channel
        (-> state
            (assoc-in [:broadcast/channels (:broadcast/id request) :state] :closed)
            (update :context/requests conj (assoc result :capability :broadcast/close))
            (assoc :last-result result)
            (record-result {:ok? true :result result}))
        (-> state
            (assoc :last-error :broadcast/not-open)
            (record-result {:ok? false
                            :result nil
                            :error :broadcast/not-open}))))

    :beacon/send
    (let [beacon-result (beacon-send-result state request)
          ok? (:ok? beacon-result)
          result (:result beacon-result)
          error (:error beacon-result)
          decision (:permission/decision beacon-result)]
      (cond-> state
        result (update :beacon/requests conj result)
        result (update :context/requests conj (assoc result :capability :beacon/send))
        true (assoc :last-result result)
        error (assoc :last-error error)
        true (record-result (cond-> {:ok? ok?
                                     :result result
                                     :error error
                                     :permission/decision decision}
                              (nil? error) (dissoc :error)))))

    :history/push-state
    (let [{:keys [entries index result]} (history-push-state-result state request)]
      (-> state
          (assoc :history/entries entries
                 :history/index index
                 :last-result result)
          (update :context/requests conj (assoc result :capability :history/push-state))
          (record-result {:ok? true :result result})))

    :history/replace-state
    (let [{:keys [entries index result]} (history-replace-state-result state request)]
      (-> state
          (assoc :history/entries entries
                 :history/index index
                 :last-result result)
          (update :context/requests conj (assoc result :capability :history/replace-state))
          (record-result {:ok? true :result result})))

    :history/traverse
    (let [{:keys [entries index result]} (history-traverse-result state request)]
      (-> state
          (assoc :history/entries entries
                 :history/index index
                 :last-result result)
          (update :context/requests conj (assoc result :capability :history/traverse))
          (record-result {:ok? true :result result})))

    :timer/schedule
    (-> state
        (assoc :binding (binding/schedule-timeout! binding
                                                   (:callback/id request)
                                                   (:delay-ms request)
                                                   (:payload request)))
        (record-result {:ok? true :result {:callback/id (:callback/id request)}}))

    :timer/cancel
    (-> state
        (assoc :binding (binding/cancel-timeout! binding (:callback/id request)))
        (record-result {:ok? true :result {:callback/id (:callback/id request)
                                           :cancelled? true}}))

    :timer/microtask
    (-> state
        (assoc :binding (binding/queue-microtask! binding
                                                  (:callback/id request)
                                                  (:payload request)))
        (record-result {:ok? true :result {:callback/id (:callback/id request)
                                           :queued? true}}))

    :js/call
    (-> state
        (update :calls conj request)
        (record-result {:ok? true :result :queued}))

      (-> state
          (assoc :last-error :quickjs/unsupported-capability)
          (record-result {:ok? false :error :quickjs/unsupported-capability})))))))

(defn- apply-requests
  [state requests]
  (reduce apply-capability state requests))

(defn evaluate!
  [state script]
  (let [[capability-results state] (take-capability-results state)
        [worker-snap state] (take-worker-snapshot state)
        [fetch-snap state] (take-fetch-snapshot state)
        state (update state :binding binding/evaluate! script)
        response (normalize-response
                  (invoke-engine (:engine state)
                                 (invocation-with-snapshots :js/evaluate
                                                            script
                                                            capability-results
                                                            (:document state)
                                                            (:storage state)
                                                            (clipboard-snapshot state)
                                                            (geolocation-snapshot state)
                                                            (websocket-snapshot state)
                                                            worker-snap
                                                            fetch-snap)))
        state (record-response-errors state response)
        state (apply-requests state (:requests response))]
    (-> state
        (assoc :result (:result response))
        (update :results conj response)
        (audit-call :js/evaluate script response))))

(defn load-module!
  [state specifier referrer]
  (if-let [cached (get-in state [:binding :quickjs/modules specifier])]
    (-> state
        (assoc :result (:result cached)
               :module/cache-hit? true)
        (update :results conj (assoc cached :cached? true)))
    (let [payload {:specifier specifier :referrer referrer}
          [capability-results state] (take-capability-results state)
          [worker-snap state] (take-worker-snapshot state)
          [fetch-snap state] (take-fetch-snapshot state)
          state (update state :binding binding/module-load! specifier referrer)
          response (normalize-response
                    (invoke-engine (:engine state)
                                   (invocation-with-snapshots :js/module-load
                                                              payload
                                                              capability-results
                                                              (:document state)
                                                              (:storage state)
                                                              (clipboard-snapshot state)
                                                              (geolocation-snapshot state)
                                                              (websocket-snapshot state)
                                                              worker-snap
                                                              fetch-snap)))
          state (record-response-errors state response)
          state (apply-requests state (:requests response))]
      (-> state
          (assoc :result (:result response)
                 :module/cache-hit? false)
          (assoc-in [:binding :quickjs/modules specifier] response)
          (update :results conj response)
          (audit-call :js/module-load payload response)))))

(defn drain-event-loop!
  [state now-ms]
  (let [state (update state :binding binding/drain-jobs! now-ms)
        tasks (-> state :binding :quickjs/results last :job-drain/tasks)]
    (reduce (fn [state task]
              (let [payload {:task task}
                    [capability-results state] (take-capability-results state)
                    response (normalize-response
                              (invoke-engine (:engine state) (invocation :js/job payload capability-results)))
                    state (record-response-errors state response)]
                (-> state
                    (apply-requests (:requests response))
                    (update :results conj response)
                    (audit-call :js/job payload response))))
            state
            tasks)))

(defn new-state
  [{:keys [binding document engine fetch-fn websocket-fn worker-fn storage clipboard geolocation
           crypto-random-bytes crypto-random-uuids audit net-context]}]
  {:binding binding
   :document document
   :engine engine
   :fetch-fn fetch-fn
   :websocket-fn websocket-fn
   :worker-fn worker-fn
   :storage (or storage (atom {}))
   :clipboard (or clipboard (atom {:text ""}))
   :geolocation (or geolocation (atom {:latitude 0.0
                                       :longitude 0.0
                                       :accuracy 0.0}))
   :notification/requests []
   :fullscreen/element nil
   :media/streams []
   :websocket/connections {}
   :websocket/handles {}
   :websocket/messages []
   :crypto/random-bytes (vec (or crypto-random-bytes []))
   :crypto/random-uuids (vec (or crypto-random-uuids []))
   :worker/instances {}
   :worker/handles {}
   :worker/outbox {}
   :worker/messages []
   :net/fetch-responses {}
   :broadcast/channels {}
   :broadcast/messages []
   :beacon/requests []
   :history/entries []
   :history/index -1
   :location/url (or (get-in net-context [:page-url]) "about:blank")
   :console/messages []
   :global/listeners {}
   :global/events []
   :audit audit
   :net/context net-context
   :dom/client-ids {}
   :calls []
   :context/requests []
   :capability/results []
   :response/errors []
   :results []})
