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

  ## Known limitations of this first pass

  - Every script / lifecycle-event invocation builds a *fresh*
    `quickjs-execution/new-state`. The one piece of state that IS threaded
    across scripts within a page load is the authoritative `:document`
    (each call re-reads `:browser.session/page :browser/document`, which
    already reflects every prior script's committed mutations). Everything
    else `quickjs-execution/new-state` models as page-lifetime state --
    `:storage` (the script-local `storage/get|put|delete` capability; distinct
    from `browser.session`'s persistent script-source cache),
    `:dom/client-ids`, `:websocket/connections`, `:worker/instances`,
    `:broadcast/channels`, `:history/*`, `:clipboard`, `:geolocation`,
    `:global/listeners` -- resets on every `<script>` tag and on every
    lifecycle dispatch. A script that opens a WebSocket (or registers a
    `document.addEventListener`) in one `<script>` tag will not see it survive
    into a second `<script>` tag or into the `DOMContentLoaded` dispatch yet.
    Threading full runtime state across one page's script tags (keyed by page
    generation, reset on navigation) is follow-up work.
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

  Returns `session` unchanged (plus a `:script/skipped` history entry) if the
  engine isn't ready yet -- see the namespace docstring."
  [session script]
  (let [engine (get-in session [:browser.session/script-engine :script-engine/engine])]
    (if-not (execution/engine-ready? engine)
      (update session :browser.session/history conj
              (skip-event script :script/engine-not-ready))
      (let [document (get-in session [:browser.session/page :browser/document])
            state (execution/new-state
                   {:binding (binding/empty-binding (script-adapter session))
                    :document document
                    :engine engine
                    :audit (:browser.session/audit session)
                    :net-context (session/net-context session)})
            result (execution/evaluate! state (script-payload script))]
        (-> session
            (cond-> (:audit result) (assoc :browser.session/audit (:audit result)))
            (session/commit-script-state! result)
            (update :browser.session/history conj
                    {:event :script/quickjs-run
                     :script/type (:script/type script)
                     :script/url (:script/url script)
                     :script/lifecycle-event (:script/lifecycle-event script)
                     :result (:result result)
                     :error (:last-error result)}))))))

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
