(ns browser.visual-smoke-check
  "Build-artifact smoke checks for kotoba-lang/browser's compiled visual
   demos (kotoba-shell's `ui check`/`ui smoke` gate for the :browser
   substrate). Mirrors kotoba-lang/dom-gpu's scripts/wasm_ui_smoke.cljc:
   intentionally lightweight, verifying that the expected static entry
   points and generated JS modules exist (and are non-trivially sized, and
   contain the markers a real shadow-cljs compile of `browser.smoke` /
   `browser.smoke-webgpu` would emit) after compilation, rather than
   driving an actual browser."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def targets
  {"visual" [{:path "public/visual-smoke.html"
              :min-bytes 700
              :contains ["/js/visual-smoke.js" "kotoba-gl" "kotoba-text" "<canvas"]}
             {:path "public/js/visual-smoke.js"
              :min-bytes 1000000
              :contains ["browser.smoke"
                         "browser.visual_smoke_model"
                         "kotoba.wasm.host.webgl"]}]
   "webgpu" [{:path "public/webgpu.html"
              :min-bytes 700
              :contains ["/js/webgpu-smoke.js" "kotoba-gpu" "kotoba-text" "<canvas" "status"]}
             {:path "public/js/webgpu-smoke.js"
              :min-bytes 1000000
              :contains ["browser.smoke_webgpu"
                         "browser.visual_smoke_model"
                         "kotoba.wasm.host.webgpu"
                         "navigator.gpu"]}]})

(defn fail!
  [message data]
  (throw (ex-info message data)))

(defn check-artifact!
  [{:keys [path min-bytes contains]}]
  (let [file (io/file path)]
    (when-not (.exists file)
      (fail! "Missing artifact" {:path path}))
    (let [bytes (.length file)
          text (slurp file)]
      (when (and min-bytes (< bytes min-bytes))
        (fail! "Artifact is unexpectedly small"
               {:path path :bytes bytes :min-bytes min-bytes}))
      (doseq [needle contains]
        (when-not (str/includes? text needle)
          (fail! "Artifact does not contain expected marker"
                 {:path path :needle needle})))
      {:path path :bytes bytes})))

(defn check-target!
  [target]
  (if-let [artifacts (get targets target)]
    (let [results (mapv check-artifact! artifacts)]
      (doseq [{:keys [path bytes]} results]
        (println "ok" path bytes))
      (println "kotoba browser" target "smoke ok"))
    (fail! "Unknown browser smoke target" {:target target :known (keys targets)})))

(defn -main
  [& [target]]
  (check-target! (or target "visual")))
