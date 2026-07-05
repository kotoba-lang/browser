(ns browser.runtime
  "Generic WASM language runtime descriptors for kotoba/aiueos adapters.")

(def default-exports
  #{:runtime/eval :runtime/call :runtime/module-load :runtime/job-drain})

(defn descriptor
  [{:keys [id lang engine component imports exports capabilities effects limits]
    :as opts}]
  {:runtime/id id
   :runtime/lang lang
   :runtime/engine engine
   :runtime/component (or component :wasm)
   :runtime/imports (set imports)
   :runtime/exports (set (or exports default-exports))
   :runtime/capabilities (set capabilities)
   :runtime/effects (set effects)
   :runtime/limits (or limits {:memory-pages 64 :fuel 10000000})
   :runtime/no-ambient-access true
   :runtime/meta (dissoc opts :id :lang :engine :component :imports :exports
                         :capabilities :effects :limits)})

(defn component-manifest
  [runtime]
  {:component/id (:runtime/id runtime)
   :component/lang (:runtime/lang runtime)
   :component/engine (:runtime/engine runtime)
   :component/runtime (:runtime/component runtime)
   :component/imports (vec (:runtime/imports runtime))
   :component/exports (vec (:runtime/exports runtime))
   :component/effects (vec (:runtime/effects runtime))
   :component/limits (:runtime/limits runtime)
   :component/no-ambient-access (:runtime/no-ambient-access runtime)})

(def allowed-imports
  #{:dom/query
    :dom/mutate
    :event/listen
    :event/dispatch
    :net/fetch
    :storage/get
    :storage/put
    :storage/delete
    :timer/schedule
    :timer/cancel
    :timer/microtask
    :js/call
    :clock/monotonic
    :log/write})

(defn explicit-imports?
  [runtime]
  (and (:runtime/no-ambient-access runtime)
       (set? (:runtime/imports runtime))
       (not (contains? (:runtime/imports runtime) :ambient/all))))

(defn valid-manifest?
  [manifest]
  (and (:component/no-ambient-access manifest)
       (= :wasm (:component/runtime manifest))
       (every? allowed-imports (:component/imports manifest))
       (not-any? #{:ambient/all :fs/read :fs/write :process/spawn :native/call}
                 (:component/imports manifest))
       (seq (:component/exports manifest))
       (pos? (get-in manifest [:component/limits :memory-pages] 0))
       (pos? (get-in manifest [:component/limits :fuel] 0))))

(def common-safe-imports
  #{:clock/monotonic :log/write})

(defn quickjs
  []
  (descriptor {:id :browser.runtime/quickjs
               :lang :javascript
               :engine :quickjs-ng
               :imports #{:dom/query
                          :dom/mutate
                          :event/listen
                          :event/dispatch
                          :net/fetch
                          :storage/get
                          :storage/put
                          :storage/delete
                          :timer/schedule
                          :timer/cancel
                          :timer/microtask
                          :js/call}
               ;; :js/job is the real per-callback dispatch capability
               ;; browser.compat.quickjs-execution/drain-event-loop! actually
               ;; invokes (see quickjs-wasm's context-run-task-result, which
               ;; drives __kotobaRunTask by callback/id); :js/job-drain is
               ;; never handled by any dispatch anywhere in this codebase
               ;; (browser.compat.quickjs/job-drain-request builds a request
               ;; for it, but browser.compat.quickjs-binding/drain-jobs!'s
               ;; own caller ignores those requests entirely and dispatches
               ;; each drained task individually via :js/job instead) -- this
               ;; descriptor was stale, naming the unreachable capability
               ;; instead of the one actually exercised.
               :exports #{:js/evaluate :js/module-load :js/job}
               :capabilities #{:browser/web-compat}
               :effects #{:dom-read :dom-write :network :persistent-write}}))

(defn python
  []
  (descriptor {:id :browser.runtime/python
               :lang :python
               :engine :cpython-wasm
               :imports #{:net/fetch :storage/get :storage/put
                          :clock/monotonic :log/write}
               :exports default-exports
               :capabilities #{:runtime/python}
               :effects #{:network :persistent-write}}))

(defn lua
  []
  (descriptor {:id :browser.runtime/lua
               :lang :lua
               :engine :lua-wasm
               :imports common-safe-imports
               :exports #{:runtime/eval :runtime/call}
               :capabilities #{:runtime/lua}
               :effects #{}}))

(defn scheme
  []
  (descriptor {:id :browser.runtime/scheme
               :lang :scheme
               :engine :scheme-wasm
               :imports common-safe-imports
               :exports #{:runtime/eval :runtime/call}
               :capabilities #{:runtime/scheme}
               :effects #{}}))

(defn registry
  []
  {:quickjs (quickjs)
   :python (python)
   :lua (lua)
   :scheme (scheme)})
