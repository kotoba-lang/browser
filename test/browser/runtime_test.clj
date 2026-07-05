(ns browser.runtime-test
  (:require [browser.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(deftest runtime-descriptors-have-no-ambient-access
  (doseq [[id rt] (runtime/registry)]
    (is (runtime/explicit-imports? rt) (str id " has explicit imports"))
    (is (:runtime/no-ambient-access rt))
    (is (= :wasm (:runtime/component rt)))
    (is (runtime/valid-manifest? (runtime/component-manifest rt)))))

(deftest quickjs-runtime-descriptor-covers-browser-compat-imports
  (let [rt (runtime/quickjs)
        manifest (runtime/component-manifest rt)]
    (is (= :javascript (:runtime/lang rt)))
    (is (= :quickjs-ng (:runtime/engine rt)))
    (is (contains? (:runtime/imports rt) :dom/query))
    (is (contains? (:runtime/imports rt) :timer/schedule))
    (is (contains? (:runtime/imports rt) :timer/cancel))
    (is (contains? (:runtime/imports rt) :timer/microtask))
    (is (= :browser.runtime/quickjs (:component/id manifest)))
    (is (:component/no-ambient-access manifest))))

(deftest quickjs-runtime-descriptor-exports-the-real-dispatch-capability
  ;; :js/job is what browser.compat.quickjs-execution/drain-event-loop!
  ;; actually dispatches per drained timer/microtask task (see
  ;; quickjs-wasm's context-run-task-result); :js/job-drain is never
  ;; handled by any dispatch anywhere -- this descriptor previously named
  ;; the unreachable capability instead of the real one.
  (let [rt (runtime/quickjs)]
    (is (contains? (:runtime/exports rt) :js/job))
    (is (not (contains? (:runtime/exports rt) :js/job-drain)))))

(deftest runtime-manifest-validation-rejects-ambient-or-native-imports
  (is (not (runtime/valid-manifest?
            {:component/runtime :wasm
             :component/no-ambient-access true
             :component/imports [:ambient/all]
             :component/exports [:runtime/eval]
             :component/limits {:memory-pages 1 :fuel 1}})))
  (is (not (runtime/valid-manifest?
            {:component/runtime :wasm
             :component/no-ambient-access true
             :component/imports [:process/spawn]
             :component/exports [:runtime/eval]
             :component/limits {:memory-pages 1 :fuel 1}})))
  (is (not (runtime/valid-manifest?
            {:component/runtime :native
             :component/no-ambient-access true
             :component/imports [:log/write]
             :component/exports [:runtime/eval]
             :component/limits {:memory-pages 1 :fuel 1}}))))

(deftest placeholder-runtimes-use-same-shape
  (let [python (runtime/python)
        lua (runtime/lua)
        scheme (runtime/scheme)]
    (is (= :python (:runtime/lang python)))
    (is (contains? (:runtime/imports python) :net/fetch))
    (is (= #{:clock/monotonic :log/write} (:runtime/imports lua)))
    (is (= #{:runtime/eval :runtime/call} (:runtime/exports scheme)))))
