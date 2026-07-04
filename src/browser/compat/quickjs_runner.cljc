(ns browser.compat.quickjs-runner
  "Real QuickJS-backed `:browser.session/script-runner`.

  `browser.session/run-page-scripts!` discovers `<script>` tags and calls an
  INJECTED `:browser.session/script-runner` function for each one, then again
  for the `DOMContentLoaded`/`load` lifecycle dispatches. Historically every
  caller supplied a test-double runner. This namespace supplies a real one: it
  evaluates the script's actual JS source through a live QuickJS WASM engine
  (`browser.compat.quickjs-wasm/engine-from-session!`), lets
  `browser.compat.quickjs-execution` turn the engine's capability requests
  into real mutations of the session's document via `browser.dom-bridge`, and
  commits the result back into `browser.session` state via
  `browser.session/commit-script-state!`.

  ## What's real vs. stubbed right now

  This runner does not add capability handling of its own -- it wires up the
  engine + document + commit path so that whatever
  `browser.compat.quickjs-execution/apply-capability` already implements runs
  for real instead of never being reached. As of this writing that already
  covers real handling for: `:dom/query`, `:dom/mutate`, `:event/listen`,
  `:event/remove`, `:event/dispatch`, `:console/log`, `:storage/get|put|delete`,
  `:cookie/get|set`, `:clipboard/read|write`, `:window/open`,
  `:location/assign|replace|reload`, `:permissions/query`,
  `:geolocation/read`, `:notification/request-permission|show`,
  `:fullscreen/request|exit`, `:media/capture`, `:websocket/connect|send|close`,
  `:crypto/random-values|random-uuid`, `:worker/create|post-message|terminate`,
  `:broadcast/open|post-message|close`, `:beacon/send`,
  `:history/push-state|replace-state|traverse`, and
  `:timer/schedule|cancel|microtask`. Anything else falls through to
  `apply-capability`'s `:quickjs/unsupported-capability` error response (see
  that namespace for the authoritative, current list -- this docstring
  summarizes it but that `case` is the source of truth).

  Genuinely proven end-to-end (see `test-cljs/browser/compat/
  quickjs_script_runner_smoke_test.cljs`): a `<script>` that assigns
  `document.title = \"...\"` runs as real JavaScript in the real QuickJS VM,
  produces a real `{:capability :dom/mutate :dom/op :set-title ...}` capability
  request, and that request is applied against the session's actual
  `browser.dom-bridge` document and lands in the committed page's
  `:browser/title` -- not a mock, not an inlined stub.

  ## Runtime state threading across a page's scripts

  Every script / lifecycle-event invocation still builds a fresh
  `quickjs-execution/new-state` map, but it is no longer *empty* apart from
  `:document`: `run-script!` now rehydrates the page-lifetime slice of that
  state (see `persistent-execution-keys`) from whatever the *previous*
  script/lifecycle invocation within the same page load left behind --
  `:storage` (the script-local `storage/get|put|delete` capability; distinct
  from `browser.session`'s persistent script-source cache), `:clipboard`,
  `:geolocation`, `:dom/client-ids`, `:websocket/connections`,
  `:worker/instances`, `:broadcast/channels`, and `:history/entries` /
  `:history/index`, `:global/listeners`. A script that calls
  `localStorage.setItem(...)` (or opens a WebSocket, or registers a
  `document.addEventListener`) in one `<script>` tag is visible to a later
  `<script>` tag and to the `DOMContentLoaded`/`load` lifecycle dispatch
  within the same page load.

  The persisted slice is stored on the session under
  `:browser.session/quickjs-runtime-state`, tagged with the
  `:browser.session/page-generation` it belongs to (mirroring how
  `browser.script-engine`'s pending-start tracking tags its handles with a
  generation to detect staleness -- see `pending-matches?` there). `run-script!`
  only reuses the persisted state when its generation still matches the
  current page generation; `browser.session/commit-page!` bumps the
  generation on every real navigation/reload, which makes the previous
  generation's entry unreachable and the next script starts from a fresh
  `quickjs-execution/new-state` again. The `:document` key is deliberately
  NOT part of the persisted slice -- it keeps using its existing, separate
  threading path (each call re-reads `:browser.session/page :browser/document`,
  which already reflects every prior script's committed mutations).

  As of this writing, `:storage` and `:clipboard` reads also round-trip back
  into the VM itself, not just the host-side view: `evaluate!`/`load-module!`
  (`browser.compat.quickjs-execution`) pass the current `:storage`/
  `:clipboard` snapshots alongside the document snapshot into every
  `quickjs-wasm` invocation (`:document/snapshot`, `:storage/snapshot`,
  `:clipboard/snapshot` on the request map), and `quickjs-wasm` installs all
  three as VM globals (`globalThis.__kotobaSnapshot` /
  `globalThis.__kotobaStorageSnapshot` / `globalThis.__kotobaClipboardSnapshot`)
  before each eval, the same way it already did for `document.*`. So
  `localStorage.getItem` and `navigator.clipboard.readText` genuinely return
  the real, current value synchronously -- a script that calls
  `localStorage.setItem('probe', 'x')` in one `<script>` tag makes a LATER
  `<script>` tag's `localStorage.getItem('probe')` observably return `x`
  to JS (proven via `document.title` in
  `test-cljs/browser/compat/quickjs_runtime_state_threading_smoke_test.cljs`,
  not just a host-side `:capability/results` log entry). The snapshot is
  frozen at eval start, so a script that does
  `setItem('x', 'y'); getItem('x')` within the SAME `<script>` tag still
  will not see its own write -- `setItem` only queues a `storage/put`
  request that the host applies via `apply-capability` *after* the script
  finishes, and the next fresh snapshot isn't installed until the next
  `<script>` tag's `eval-dispose!`. `setItem`/`removeItem`/`writeText` and
  the `storage/get`/`clipboard/read` requests `getItem`/`readText` still
  queue alongside the synchronous snapshot read are otherwise unchanged from
  before -- they keep landing in `:capability/results` for the audit trail.
  `geolocation` reads are NOT (yet) wired the same way -- `getCurrentPosition`
  is callback-based and permission-gated (see
  `quickjs-execution/geolocation-result`), which is a meaningfully different,
  riskier shape than a plain synchronous return value, so it remains
  host-side-only, left as follow-up work.

  ## Known limitations of this first pass

  - The QuickJS engine must already be started and `:ready`
    (i.e. the promise returned by `browser.session/ensure-script-engine!` has
    resolved) before `run-page-scripts!` runs. Starting the engine is
    asynchronous in ClojureScript (the WASM module load is a Promise) while
    `run-page-scripts!` is a synchronous reducer, so this runner does not --
    and structurally cannot -- lazily start the engine mid-reduce. If the
    engine isn't ready yet, scripts are recorded as skipped
    (history event `{:event :script/skipped :reason :script/engine-not-ready}`)
    rather than silently dropped or thrown. Callers must
    `(session/ensure-script-engine! session)` (and, in ClojureScript, await
    the resulting promise) before navigating / loading HTML."
  (:require [browser.compat.quickjs :as quickjs]
            [browser.compat.quickjs-binding :as binding]
            [browser.compat.quickjs-execution :as execution]
            [browser.compat.quickjs-wasm :as quickjs-wasm]
            [browser.session :as session]))

(defn- script-adapter
  [session]
  (quickjs/new-adapter
   {:origin (get-in session [:browser.session/page :browser/url])
    :profile-id (get-in session [:browser.session/profile :profile/id])}))

(defn- script-payload
  [script]
  (cond-> {:source (:script/source script)
           :url (:script/url script)
           :module? (= :module (:script/type script))}
    (:script/node-id script)
    (assoc :script/node-id (:script/node-id script))))

(defn- skip-event
  [script reason]
  {:event :script/skipped
   :reason reason
   :script/type (:script/type script)
   :script/url (:script/url script)
   :script/lifecycle-event (:script/lifecycle-event script)})

(def persistent-execution-keys
  "`quickjs-execution/new-state` keys that model page-lifetime runtime state
  (per this namespace's docstring): the script-local storage/clipboard/
  geolocation capabilities, plus the host-side capability registries a script
  can build up over a page load (open DOM client-id mappings, WebSocket/
  Worker/BroadcastChannel handles, the session-history navigation stack, and
  registered global event listeners). `:document` is deliberately excluded --
  it has its own, already-working threading path (re-read from
  `:browser.session/page` on every call)."
  [:storage :clipboard :geolocation
   :dom/client-ids :websocket/connections :worker/instances
   :broadcast/channels :history/entries :history/index
   :global/listeners])

(defn- script-generation
  [session script]
  (:script/generation script (:browser.session/page-generation session)))

(defn- runtime-state-for-generation
  "Previously persisted page-lifetime `quickjs-execution` state for
  `generation`, or nil if there isn't any yet, or what's persisted belongs to
  a stale generation (i.e. a navigation happened since)."
  [session generation]
  (let [entry (:browser.session/quickjs-runtime-state session)]
    (when (= generation (:quickjs-runtime/generation entry))
      (:quickjs-runtime/state entry))))

(defn- remember-runtime-state!
  "Persist the page-lifetime slice of `state` (`persistent-execution-keys`)
  on `session`, tagged with `generation`. A later call at the same
  `generation` (the next script tag or lifecycle dispatch within the same
  page load) picks this back up via `runtime-state-for-generation`; a
  navigation bumps `:browser.session/page-generation`, which makes this
  entry's generation stale and therefore unreachable -- the next script then
  starts from a fresh `quickjs-execution/new-state`."
  [session generation state]
  (assoc session :browser.session/quickjs-runtime-state
         {:quickjs-runtime/generation generation
          :quickjs-runtime/state (select-keys state persistent-execution-keys)}))

(defn run-script!
  "Real `:browser.session/script-runner`.

  Evaluates `script`'s real JS source through the session's already-ready
  QuickJS engine (see the `:script-engine/engine` under
  `:browser.session/script-engine`) and commits any resulting document
  mutation back into `session` via `browser.session/commit-script-state!`.
  Also folds any `quickjs-execution` audit events into the session's real
  audit log (`:browser.session/audit`) before committing, so QuickJS
  evaluations show up in the same append-only ledger as page commits and
  permission decisions.

  Rehydrates the page-lifetime slice of `quickjs-execution` runtime state
  (`persistent-execution-keys`) left behind by the previous script/lifecycle
  invocation at the same page generation, and persists the (possibly
  updated) slice back via `remember-runtime-state!` so the *next* invocation
  sees it too -- see the namespace docstring for the full picture, including
  which capability answers (`:storage`, `:clipboard`) now round-trip back
  into the QuickJS VM itself as real, synchronous JS return values, and
  which (`:geolocation`) remain a documented follow-up gap.

  Returns `session` unchanged (plus a `:script/skipped` history entry) if the
  engine isn't ready yet -- see the namespace docstring."
  [session script]
  (let [engine (get-in session [:browser.session/script-engine :script-engine/engine])]
    (if-not (execution/engine-ready? engine)
      (update session :browser.session/history conj
              (skip-event script :script/engine-not-ready))
      (let [document (get-in session [:browser.session/page :browser/document])
            generation (script-generation session script)
            persisted (runtime-state-for-generation session generation)
            state (merge (execution/new-state
                          {:binding (binding/empty-binding (script-adapter session))
                           :document document
                           :engine engine
                           :audit (:browser.session/audit session)
                           :net-context (session/net-context session)})
                         persisted)
            result (execution/evaluate! state (script-payload script))]
        (-> session
            (cond-> (:audit result) (assoc :browser.session/audit (:audit result)))
            (session/commit-script-state! result)
            (remember-runtime-state! generation result)
            (update :browser.session/history conj
                    {:event :script/quickjs-run
                     :script/type (:script/type script)
                     :script/url (:script/url script)
                     :script/lifecycle-event (:script/lifecycle-event script)
                     :result (:result result)
                     :error (:last-error result)
                     :capability/results (:capability/results result)}))))))

(defn quickjs-session-opts
  "Merge the `browser.session/new-session` options needed to run page scripts
  through a real QuickJS engine into `opts`.

  Adds `:script-runner` (`run-script!`) and `:engine-factory`
  (`browser.compat.quickjs-wasm/engine-from-session!`). Any `:script-runner`
  or `:engine-factory` already present in `opts` wins over these defaults, so
  callers can still override either half independently (e.g. to swap in a
  fake engine-factory for a JVM test while keeping the real runner logic)."
  [opts]
  (merge {:script-runner run-script!
          :engine-factory quickjs-wasm/engine-from-session!}
         opts))
