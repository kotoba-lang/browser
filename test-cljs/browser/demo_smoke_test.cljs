(ns browser.demo-smoke-test
  "Real, non-browser verification of `browser.demo`'s own WebSocket/Worker/
  fetch() proofs -- the exact real `sample-html`/`ws-script1-source`/
  `ws-script2-source`/`grant-demo-permissions`/`synchronous-fetch-fn`
  functions `src/browser/demo.cljs`'s `init!` uses, imported directly (not
  a re-typed copy), driven through the real `browser.session`/
  `browser.compat.quickjs-runner` pipeline against a REAL local Node server
  -- exactly the way `quickjs_websocket_smoke_test.cljs`/
  `quickjs_worker_smoke_test.cljs`/`quickjs_fetch_smoke_test.cljs` already
  verify each capability in isolation.

  `browser-demo-real-generated-content-proof-test` below additionally
  drives the SAME real `sample-html` (plus `demo/generated-content-css`,
  the real CSS text `init!` now also passes as `session/load-html!`'s
  `:css` argument -- see that fn's own docstring for why) through the
  same real pipeline, proving `sample-html`'s real `<style>` block's
  `::before`/`::after` generated content (`content: attr(...)` on
  `#status-badge`, `content: counter(...)` numbering `#step-counter`'s
  four real sibling `<li>`s) is genuinely resolved by the real
  `cssom.core/apply-cascade` this repo depends on, and genuinely painted
  into the real draw-ops the WebGL canvas renders from.

  ## What this proves, and what it deliberately does NOT

  `browser.demo/init!` itself cannot run under Node -- it calls
  `(.getElementById js/document \"kotoba-gl\")` and
  `kotoba.wasm.host.webgl/create-host!` needs a real `<canvas>` + WebGL
  context, neither of which exist outside a real browser tab. This test
  does not attempt to fake a DOM/WebGL environment; instead, exactly like
  the three smoke tests it mirrors, it uses `kotoba.wasm.host/recording-host`
  (no WebGL) and drives the SAME real capability-wiring functions `init!`
  calls, in the SAME order, with the SAME real script sources -- proving
  the actual demo content genuinely round-trips through real WebSocket/
  Worker/fetch() when driven with a real local server. The only thing left
  genuinely unverified by this test (or by any Node-based test) is the
  real WebGL paint of the resulting DOM state -- that needs an actual
  browser tab (see this repo's own verification discipline for `init!`'s
  original flexbox/grid/paint proof, which was likewise only ever verified
  live in a browser).

  ## The real local server

  A single combined Node server (this test's own `start-demo-server!`,
  mirroring the THREE existing smoke tests' individually hand-rolled
  servers, combined into one process -- exactly the shape
  `scripts/demo-server.js` gives the real demo) serving:

    - `GET /worker.js`      -- real JS source, `self.onmessage = function(e)
                               { self.postMessage(e.data * 2); };` (same
                               body `scripts/demo-server.js` serves).
    - `GET /api/fetch-data` -- a real plain-text body (same body
                               `scripts/demo-server.js` serves).
    - `WS  /ws-echo`        -- a real, minimal, hand-rolled RFC6455 echo
                               server (real HTTP-Upgrade handshake incl. a
                               real Sec-WebSocket-Accept computation, real
                               frame parsing/framing) -- verbatim the same
                               algorithm as
                               `quickjs_websocket_smoke_test.cljs`'s
                               `websocket-echo-server`.

  Real Node `http.get` (not `js/fetch` -- matching
  `quickjs_worker_smoke_test.cljs`/`quickjs_fetch_smoke_test.cljs`'s own
  precedent exactly) pre-fetches `/worker.js` and `/api/fetch-data` ahead
  of building the session, then `browser.demo/synchronous-fetch-fn` wraps
  those ALREADY-real responses into the synchronous `:fetch-fn` this
  repo's contract needs (see that function's own docstring)."
  (:require ["net" :as net]
            ["http" :as http]
            ["crypto" :as crypto]
            [cljs.test :refer [deftest is async]]
            [clojure.string :as str]
            [browser.compat.quickjs-runner :as quickjs-runner]
            [browser.demo :as demo]
            [browser.dom-bridge :as dom-bridge]
            [browser.net.websocket :as ws]
            [browser.session :as session]
            [kotoba.wasm.host :as host]))

;; ---------------------------------------------------------------------
;; A single, combined REAL local server: /worker.js + /api/fetch-data over
;; plain http, plus a real, minimal RFC6455 echo on /ws-echo over the same
;; port's raw 'upgrade' socket -- see namespace docstring. Ports
;; test-cljs/browser/compat/quickjs_websocket_smoke_test.cljs's
;; websocket-echo-server verbatim (same algorithm), combined with
;; quickjs_worker_smoke_test.cljs's/quickjs_fetch_smoke_test.cljs's plain
;; http handlers.
;; ---------------------------------------------------------------------

(def worker-script-source
  "self.onmessage = function(e) { self.postMessage(e.data * 2); };")

(def fetch-data-body
  "hello real fetch, from the real kotoba-lang/browser demo server")

(def ^:private websocket-magic "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn- accept-value
  [key]
  (-> (.createHash crypto "sha1")
      (.update (str key websocket-magic))
      (.digest "base64")))

(defn- try-parse-frame
  [^js buf]
  (when (>= (.-length buf) 2)
    (let [b0 (.readUInt8 buf 0)
          b1 (.readUInt8 buf 1)
          opcode (bit-and b0 0x0F)
          masked? (not (zero? (bit-and b1 0x80)))
          len0 (bit-and b1 0x7F)
          header-extra (cond (= len0 126) 2 (= len0 127) 8 :else 0)
          mask-len (if masked? 4 0)]
      (when (>= (.-length buf) (+ 2 header-extra mask-len))
        (let [payload-len (cond
                             (= len0 126) (.readUInt16BE buf 2)
                             (= len0 127) (js/Number (.readBigUInt64BE buf 2))
                             :else len0)
              mask-offset (+ 2 header-extra)
              payload-offset (+ mask-offset mask-len)
              total (+ payload-offset payload-len)]
          (when (>= (.-length buf) total)
            (let [mask-key (when masked? (.slice buf mask-offset (+ mask-offset 4)))
                  raw (.slice buf payload-offset total)
                  payload (if masked?
                            (let [out (js/Buffer.alloc payload-len)]
                              (dotimes [i payload-len]
                                (.writeUInt8 out
                                             (bit-xor (.readUInt8 raw i)
                                                      (.readUInt8 mask-key (mod i 4)))
                                             i))
                              out)
                            raw)]
              {:opcode opcode :payload payload :consumed total})))))))

(defn- write-frame!
  [^js socket opcode ^js payload]
  (let [len (.-length payload)
        header (cond
                 (< len 126)
                 (js/Buffer.from #js [(bit-or 0x80 opcode) len])

                 (< len 65536)
                 (let [b (js/Buffer.alloc 4)]
                   (.writeUInt8 b (bit-or 0x80 opcode) 0)
                   (.writeUInt8 b 126 1)
                   (.writeUInt16BE b len 2)
                   b)

                 :else
                 (let [b (js/Buffer.alloc 10)]
                   (.writeUInt8 b (bit-or 0x80 opcode) 0)
                   (.writeUInt8 b 127 1)
                   (.writeBigUInt64BE b (js/BigInt len) 2)
                   b))]
    (.write socket (js/Buffer.concat #js [header payload]))))

(defn- drain-frames!
  [^js socket state]
  (loop []
    (when-let [{:keys [opcode payload consumed]} (try-parse-frame (:buffer @state))]
      (swap! state update :buffer #(.slice ^js % consumed))
      (case opcode
        1 (write-frame! socket 1 payload)
        8 (write-frame! socket 8 (js/Buffer.alloc 0))
        9 (write-frame! socket 0xA payload)
        nil)
      (recur))))

(defn- handle-upgrade!
  [^js req ^js socket ^js head]
  (let [url-path (first (str/split (or (.-url req) "/") #"\?"))]
    (if (not= url-path "/ws-echo")
      (.destroy socket)
      (let [accept-key (aget (.-headers req) "sec-websocket-key")]
        (if-not accept-key
          (.destroy socket)
          (let [accept (accept-value accept-key)
                response (str "HTTP/1.1 101 Switching Protocols\r\n"
                              "Upgrade: websocket\r\n"
                              "Connection: Upgrade\r\n"
                              "Sec-WebSocket-Accept: " accept "\r\n"
                              "\r\n")
                state (atom {:buffer (if (and head (pos? (.-length head)))
                                        head
                                        (js/Buffer.alloc 0))})]
            (.write socket response "ascii")
            (drain-frames! socket state)
            (.on socket "data"
                 (fn [chunk]
                   (swap! state update :buffer #(js/Buffer.concat #js [% chunk]))
                   (drain-frames! socket state)))
            (.on socket "error" (fn [_] nil))))))))

(defn- start-demo-server!
  "Start a real, combined local server: /worker.js + /api/fetch-data over
   plain http, plus a real RFC6455 echo on /ws-echo -- see namespace
   docstring. Returns a Promise of
   {:server :http-origin \"http://127.0.0.1:<port>\"}."
  []
  (js/Promise.
   (fn [resolve _reject]
     (let [server (.createServer http
                                 (fn [^js req ^js res]
                                   (let [url-path (first (str/split (or (.-url req) "/") #"\?"))]
                                     (cond
                                       (and (= (.-method req) "GET") (= url-path "/worker.js"))
                                       (do (.writeHead res 200 #js {"Content-Type" "text/javascript"
                                                                    "Access-Control-Allow-Origin" "*"})
                                           (.end res worker-script-source))

                                       (and (= (.-method req) "GET") (= url-path "/api/fetch-data"))
                                       (do (.writeHead res 200 #js {"Content-Type" "text/plain"
                                                                    "Access-Control-Allow-Origin" "*"})
                                           (.end res fetch-data-body))

                                       :else
                                       (do (.writeHead res 404)
                                           (.end res "not found"))))))]
       (.on server "upgrade" handle-upgrade!)
       (.listen server 0 "127.0.0.1"
                (fn []
                  (let [port (.-port (.address server))]
                    (resolve {:server server
                              :http-origin (str "http://127.0.0.1:" port)}))))))))

(defn- stop-demo-server!
  [{:keys [server]}]
  (.close server))

(defn- real-http-get!
  "A REAL `http.get` (Node's own built-in client, real loopback TCP)
   against `url`. Unlike
   `quickjs_worker_smoke_test.cljs`/`quickjs_fetch_smoke_test.cljs`'s own
   `real-http-get!` (which can afford to drop response headers because
   those tests load their page at the SAME origin as the fetch target,
   sidestepping browser.net's CORS check entirely -- see
   `quickjs_fetch_smoke_test.cljs`'s `run-script1!` docstring), THIS test
   drives the real demo's OWN page origin (`kotoba://browser-demo/index`,
   genuinely cross-origin from the real local server), so it must also
   carry the real, server-sent `access-control-allow-origin` header through
   into the wrapped response -- exactly what `browser.demo/real-prefetch!`'s
   real, native `js/fetch` naturally captures via `Response.headers` in an
   actual browser. Returns a Promise of a browser.net-shaped response map
   ({:status :headers :body})."
  [url]
  (js/Promise.
   (fn [resolve reject]
     (-> (.get http url
               (fn [^js res]
                 (let [chunks (atom [])]
                   (.on res "data" (fn [chunk] (swap! chunks conj chunk)))
                   (.on res "end"
                        (fn []
                          (resolve {:status (.-statusCode res)
                                    :headers (js->clj (.-headers res))
                                    :body (.toString (js/Buffer.concat (clj->js @chunks)))}))))))
         (.on "error" reject)))))

;; ---------------------------------------------------------------------
;; Drive browser.demo's own real functions against the real server above.
;; ---------------------------------------------------------------------

(defn- wait-ms
  [ms]
  (js/Promise. (fn [resolve _reject] (js/setTimeout resolve ms))))

(defn- dispose-engine!
  [session]
  (when-let [engine (get-in session [:browser.session/script-engine :script-engine/engine])]
    (when-let [dispose (:quickjs.engine/dispose engine)]
      (dispose engine))))

(defn- element-text
  [document id]
  (some->> (dom-bridge/get-element-by-id document id)
           (dom-bridge/node-snapshot document)
           :text-content))

(defn- node-attr
  [document id k]
  (some->> (dom-bridge/get-element-by-id document id)
           (dom-bridge/node-snapshot document)
           :attrs
           k))

(defn- pseudo-content
  "Mirrors `browser.demo`'s own (private) `pseudo-content` helper: a
   pseudo-element's generated content is never a DOM node/mutation (see
   that namespace's docstring), so there is no `:text-content` to read --
   `cssom.core/apply-cascade` instead decorates the real element's own
   `:attrs` with a synthetic `:pseudo/before` style map holding the
   resolved `content` string, read here via the SAME
   `browser.dom-bridge/get-element-by-id`+`node-snapshot` `element-text`
   above uses, just its `:attrs` key instead of `:text-content`."
  [document id]
  (:content (node-attr document id :pseudo/before)))

(defn- index-of-text
  [texts marker]
  (first (keep-indexed (fn [i x] (when (= x marker) i)) texts)))

(defn- text-draw-ops
  [page]
  (mapv :text (filter #(= :text (:draw/op %)) (:browser/draw-ops page))))

(defn- text-immediately-before
  "The :text draw-op string immediately preceding the first draw-op whose
   :text is exactly `marker` in `texts` (the page's full, real, ordered
   `:draw/op :text` sequence -- what the WebGL canvas actually paints) --
   proves a ::before generated-content box was really painted right before
   its real sibling's own real text, in real document order. Mirrors
   cssom.layout-test's own adjacency checks (e.g. `(mapv :text text-ops)`
   compared against an exact expected ordered list), scoped to a known
   anchor instead of the whole page's exact list so this test stays
   robust to unrelated content elsewhere on the page."
  [texts marker]
  (when-let [idx (index-of-text texts marker)]
    (when (pos? idx) (nth texts (dec idx)))))

(deftest browser-demo-real-websocket-worker-fetch-proofs-test
  (async done
    (let [server-atom (atom nil)
          session-atom (atom nil)
          fail! (fn [err]
                  (is false (str "browser.demo real WS/Worker/fetch smoke test failed: "
                                 (or (.-message err) err))))
          cleanup! (fn []
                     (dispose-engine! @session-atom)
                     (when-let [server @server-atom] (stop-demo-server! server))
                     (done))]
      (-> (start-demo-server!)
          (.then (fn [server]
                   (reset! server-atom server)
                   (let [{:keys [http-origin]} server
                         worker-url (str http-origin "/worker.js")
                         fetch-url (str http-origin "/api/fetch-data")]
                     (js/Promise.all #js [(real-http-get! worker-url)
                                          (real-http-get! fetch-url)]))))
          (.then (fn [results]
                   (let [worker-response (aget results 0)
                         fetch-response (aget results 1)
                         {:keys [http-origin]} @server-atom
                         worker-url (str http-origin "/worker.js")
                         fetch-url (str http-origin "/api/fetch-data")
                         ws-url (str (str/replace http-origin #"^http" "ws") "/ws-echo")]
                     (is (= 200 (:status worker-response)))
                     (is (= worker-script-source (:body worker-response))
                         "expected the real server's real worker script body")
                     (is (= 200 (:status fetch-response)))
                     (is (= fetch-data-body (:body fetch-response))
                         "expected the real server's real fetch-data body")
                     (let [prefetched {worker-url worker-response fetch-url fetch-response}
                           demo-profile (demo/grant-demo-permissions worker-url fetch-url ws-url)
                           h (host/recording-host)
                           base-session (session/new-session
                                         (quickjs-runner/quickjs-session-opts
                                          {:host h
                                           :profile demo-profile
                                           :fetch-fn (demo/synchronous-fetch-fn prefetched)
                                           :websocket-fn (ws/websocket-fn)}))]
                       {:worker-url worker-url :fetch-url fetch-url :ws-url ws-url
                        :ready (session/ensure-script-engine! base-session)}))))
          (.then (fn [{:keys [worker-url fetch-url ws-url ready]}]
                   (-> ready
                       (.then (fn [ready-session]
                                (reset! session-atom ready-session)
                                (is (= :ready (get-in ready-session [:browser.session/script-engine
                                                                      :script-engine/status]))
                                    "engine should be ready before running page scripts")
                                {:worker-url worker-url :fetch-url fetch-url :ws-url ws-url
                                 :ready-session ready-session})))))
          (.then (fn [{:keys [worker-url fetch-url ws-url ready-session]}]
                   ;; The EXACT real HTML browser.demo/init! loads -- imported
                   ;; directly, not a re-typed copy.
                   (let [after (session/load-html!
                               ready-session
                               {:url "kotoba://browser-demo/index"
                                :html (demo/sample-html worker-url fetch-url)})
                         generation (:browser.session/page-generation after)
                         doc (get-in after [:browser.session/page :browser/document])]
                     (reset! session-atom after)
                     (println "browser.demo smoke: document.title after page scripts ->"
                              (pr-str (get-in after [:browser.session/page :browser/title])))
                     (println "browser.demo smoke: #worker-proof after page scripts ->"
                              (pr-str (element-text doc "worker-proof")))
                     (println "browser.demo smoke: #fetch-proof after page scripts ->"
                              (pr-str (element-text doc "fetch-proof")))
                     (is (= "Kotoba Browser: real QuickJS + real cssom layout + real WebGL paint"
                            (get-in after [:browser.session/page :browser/title]))
                         "the original document.title proof must still land unchanged")
                     (is (= "Worker proof: real 2nd QuickJS context computed 21 * 2 -> 42"
                            (element-text doc "worker-proof"))
                         (str "expected the real Worker's real reply (21 * 2 = 42), delivered by "
                              "script 3's snapshot install, in #worker-proof"))
                     (is (= (str "fetch() proof: real HTTP response body -> \"" fetch-data-body "\"")
                            (element-text doc "fetch-proof"))
                         "expected the real (pre-fetched) fetch() response body in #fetch-proof")
                     ;; WebSocket needs a real wall-clock wait -- see
                     ;; browser.demo's own namespace docstring and
                     ;; quickjs_websocket_smoke_test.cljs.
                     (let [opened (quickjs-runner/run-script!
                                  after
                                  {:script/type :classic
                                   :script/url "kotoba://browser-demo/index/ws-open.js"
                                   :script/generation generation
                                   :script/source (demo/ws-script1-source ws-url)})]
                       (-> (wait-ms 500)
                           (.then (fn [_]
                                    (quickjs-runner/run-script!
                                     opened
                                     {:script/type :classic
                                      :script/url "kotoba://browser-demo/index/ws-deliver.js"
                                      :script/generation generation
                                      :script/source demo/ws-script2-source}))))))))
          (.then (fn [final-session]
                   (reset! session-atom final-session)
                   (let [doc (get-in final-session [:browser.session/page :browser/document])
                         ws-proof (element-text doc "ws-proof")]
                     (println "browser.demo smoke: #ws-proof after real WS round-trip ->"
                              (pr-str ws-proof))
                     (is (= "WebSocket proof: real echo round-trip -> \"hello from the real kotoba-lang/browser demo\""
                            ws-proof)
                         "expected the real server's real echoed text in #ws-proof"))))
          (.catch fail!)
          (.finally cleanup!)))))

;; ---------------------------------------------------------------------
;; Real ::before/::after generated content: content: attr(...) and
;; content: counter(...), added to cssom.core/apply-cascade in a later
;; session on kotoba-lang/cssom and wired into this demo's own
;; sample-html/generated-content-css. Reuses the same real local server +
;; session-building helpers as the WS/Worker/fetch proof test above (this
;; demo's sample-html always includes its own real Worker/fetch <script>
;; tags, needing the same real server behind it), but does not need the
;; WebSocket round trip's real wall-clock wait at all -- generated content
;; is fully resolved synchronously as part of the real cascade
;; `session/load-html!` runs.
;; ---------------------------------------------------------------------

(deftest browser-demo-real-generated-content-proof-test
  (async done
    (let [server-atom (atom nil)
          session-atom (atom nil)
          fail! (fn [err]
                  (is false (str "browser.demo real generated-content smoke test failed: "
                                 (or (.-message err) err))))
          cleanup! (fn []
                     (dispose-engine! @session-atom)
                     (when-let [server @server-atom] (stop-demo-server! server))
                     (done))]
      (-> (start-demo-server!)
          (.then (fn [server]
                   (reset! server-atom server)
                   (let [{:keys [http-origin]} server
                         worker-url (str http-origin "/worker.js")
                         fetch-url (str http-origin "/api/fetch-data")]
                     (js/Promise.all #js [(real-http-get! worker-url)
                                          (real-http-get! fetch-url)]))))
          (.then (fn [results]
                   (let [worker-response (aget results 0)
                         fetch-response (aget results 1)
                         {:keys [http-origin]} @server-atom
                         worker-url (str http-origin "/worker.js")
                         fetch-url (str http-origin "/api/fetch-data")
                         ws-url (str (str/replace http-origin #"^http" "ws") "/ws-echo")
                         prefetched {worker-url worker-response fetch-url fetch-response}
                         demo-profile (demo/grant-demo-permissions worker-url fetch-url ws-url)
                         h (host/recording-host)
                         base-session (session/new-session
                                       (quickjs-runner/quickjs-session-opts
                                        {:host h
                                         :profile demo-profile
                                         :fetch-fn (demo/synchronous-fetch-fn prefetched)
                                         :websocket-fn (ws/websocket-fn)}))]
                     {:worker-url worker-url :fetch-url fetch-url
                      :ready (session/ensure-script-engine! base-session)})))
          (.then (fn [{:keys [worker-url fetch-url ready]}]
                   (-> ready
                       (.then (fn [ready-session]
                                (reset! session-atom ready-session)
                                {:worker-url worker-url :fetch-url fetch-url
                                 :ready-session ready-session})))))
          (.then (fn [{:keys [worker-url fetch-url ready-session]}]
                   ;; The EXACT real sample-html AND the EXACT real CSS text
                   ;; browser.demo/init! passes as :css -- imported directly,
                   ;; not a re-typed copy. See browser.session/load-html!'s
                   ;; own docstring for why :css must be passed explicitly
                   ;; here: this pipeline does not auto-extract a real
                   ;; <style> tag's own text back out of parsed HTML.
                   (let [after (session/load-html!
                               ready-session
                               {:url "kotoba://browser-demo/index"
                                :html (demo/sample-html worker-url fetch-url)
                                :css demo/generated-content-css})
                         page (:browser.session/page after)
                         doc (:browser/document page)
                         texts (text-draw-ops page)
                         step-ids ["step-1" "step-2" "step-3" "step-4"]
                         step-texts ["Real HTML parsed into a real kotoba.wasm.dom document"
                                     "Real cssom cascade resolves ::before/::after generated content"
                                     "Real box-model layout produces real draw-ops"
                                     "Real WebGL rasterization paints the canvas"]]
                     (reset! session-atom after)
                     (println "browser.demo smoke: real ::before attr() proof -- #status-badge ->"
                              (pr-str (pseudo-content doc "status-badge")))
                     (println "browser.demo smoke: real ::before counter() proof -- #step-counter lis ->"
                              (pr-str (mapv #(pseudo-content doc %) step-ids)))
                     (is (= "live" (node-attr doc "status-badge" :data-status))
                         "expected the real, served data-status=\"live\" HTML attribute")
                     (is (= "live: " (pseudo-content doc "status-badge"))
                         "content: attr(data-status) \": \" must resolve to the real element's own real attribute value")
                     (is (= "Kotoba engine" (element-text doc "status-badge"))
                         "the real child text itself must be untouched by the ::before rule")
                     (is (= ["1. " "2. " "3. " "4. "] (mapv #(pseudo-content doc %) step-ids))
                         "four real sibling <li>s must be numbered sequentially purely by CSS counters")
                     (is (= step-texts (mapv #(element-text doc %) step-ids))
                         "each real <li>'s own real text must be untouched by its ::before rule")
                     (is (= "live: " (text-immediately-before texts "Kotoba engine"))
                         "the real painted draw-ops (what the WebGL canvas actually renders) must carry the resolved ::before text immediately before the real element's own real text")
                     (doseq [[expected-number step-text] (map vector ["1. " "2. " "3. " "4. "] step-texts)]
                       (is (= expected-number (text-immediately-before texts step-text))
                           (str "expected " (pr-str expected-number) " painted immediately before " (pr-str step-text)))))))
          (.catch fail!)
          (.finally cleanup!)))))
