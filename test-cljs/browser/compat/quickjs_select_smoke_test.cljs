(ns browser.compat.quickjs-select-smoke-test
  "Real page-script round-trip proof that `HTMLInputElement`/
  `HTMLTextAreaElement.select()` -- previously entirely missing (a
  repo-wide grep for a `select:` method on the generic element wrapper
  returned nothing, only the unrelated `<select>` tag/option-list
  machinery) -- now works through actual QuickJS WASM end-to-end,
  reusing the already-real `setSelectionRange`."
  (:require [cljs.test :refer [deftest is async]]
            [browser.compat.quickjs-runner :as quickjs-runner]
            [browser.session :as session]
            [kotoba.wasm.host :as host]))

(defn- dispose-engine!
  [session]
  (when-let [engine (get-in session [:browser.session/script-engine :script-engine/engine])]
    (when-let [dispose (:quickjs.engine/dispose engine)]
      (dispose engine))))

(defn- run-page-and-read-title!
  [{:keys [html]}]
  (js/Promise.
   (fn [resolve reject]
     (let [h (host/recording-host)
           base-session (session/new-session (quickjs-runner/quickjs-session-opts {:host h}))]
       (-> (session/ensure-script-engine! base-session)
           (.then
            (fn [ready-session]
              (try
                (let [after (session/load-html! ready-session
                                                {:url "kotoba://select-round-trip"
                                                 :html html})
                      title (get-in after [:browser.session/page :browser/title])]
                  (dispose-engine! after)
                  (resolve title))
                (catch :default e
                  (dispose-engine! ready-session)
                  (reject e)))))
           (.catch (fn [err]
                     (dispose-engine! base-session)
                     (reject err))))))))

(deftest quickjs-real-input-select-selects-all-of-its-own-text-test
  (async done
    (-> (run-page-and-read-title!
         {:html (str "<input id=\"note\" value=\"hello world\">"
                     "<script>"
                     "var note = document.getElementById('note');"
                     "note.select();"
                     "document.title = note.selectionStart + ':' + note.selectionEnd;"
                     "</script>")})
        (.then (fn [title]
                 (println "quickjs real input.select() ->" (pr-str title))
                 (is (= "0:11" title)
                     (str "expected select() to select the whole 11-character value (indices 0 to "
                          "11), got " (pr-str title)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "QuickJS WASM engine initialization / page load failed: "
                                 (or (.-message err) err)))
                  (done))))))

(deftest quickjs-real-textarea-select-selects-all-of-its-own-text-test
  (async done
    (-> (run-page-and-read-title!
         {:html (str "<textarea id=\"note\">hi</textarea>"
                     "<script>"
                     "var note = document.getElementById('note');"
                     "note.select();"
                     "document.title = note.selectionStart + ':' + note.selectionEnd;"
                     "</script>")})
        (.then (fn [title]
                 (println "quickjs real textarea.select() ->" (pr-str title))
                 (is (= "0:2" title)
                     (str "expected select() to select the whole 2-character textarea value, got "
                          (pr-str title)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "QuickJS WASM engine initialization / page load failed: "
                                 (or (.-message err) err)))
                  (done))))))

(deftest quickjs-real-select-on-an-empty-input-selects-nothing-not-a-crash-test
  (async done
    (-> (run-page-and-read-title!
         {:html (str "<input id=\"note\">"
                     "<script>"
                     "var note = document.getElementById('note');"
                     "note.select();"
                     "document.title = note.selectionStart + ':' + note.selectionEnd;"
                     "</script>")})
        (.then (fn [title]
                 (println "quickjs real select() on an empty input ->" (pr-str title))
                 (is (= "0:0" title)
                     (str "expected select() on an empty input to select an empty range, not "
                          "crash, got " (pr-str title)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "QuickJS WASM engine initialization / page load failed: "
                                 (or (.-message err) err)))
                  (done))))))
