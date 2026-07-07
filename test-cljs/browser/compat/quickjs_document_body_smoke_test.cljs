(ns browser.compat.quickjs-document-body-smoke-test
  "Real page-script round-trip proof that `document.body` -- previously a
  plain, once-evaluated DATA property (the only non-getter, non-null-safe
  accessor on the document object literal, unlike every sibling such as
  `documentElement`/`head`) -- now correctly returns `null` for a real
  document with no <body> element at all, matching every sibling
  accessor's own established null-safe getter shape."
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
                                                {:url "kotoba://document-body-round-trip"
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

(deftest quickjs-real-document-body-is-null-when-no-body-element-exists-test
  (async done
    (-> (run-page-and-read-title!
         {:html (str "<main id=\"root\">no body element anywhere in this document</main>"
                     "<script>"
                     "document.title = String(document.body === null);"
                     "</script>")})
        (.then (fn [title]
                 (println "quickjs real document.body === null on a bodyless document ->" (pr-str title))
                 (is (= "true" title)
                     (str "expected document.body to be null when no real <body> element exists at all, "
                          "got " (pr-str title)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "QuickJS WASM engine initialization / page load failed: "
                                 (or (.-message err) err)))
                  (done))))))

(deftest quickjs-real-document-body-returns-the-real-body-element-test
  (async done
    (-> (run-page-and-read-title!
         {:html (str "<html><body id=\"real-body\"><p>hello</p></body></html>"
                     "<script>"
                     "document.title = (document.body !== null) + ':' + document.body.tagName + ':' + "
                     "document.body.id;"
                     "</script>")})
        (.then (fn [title]
                 (println "quickjs real document.body on a document with a real <body> ->" (pr-str title))
                 (is (= "true:BODY:real-body" title)
                     (str "expected document.body to be the real <body> element, got " (pr-str title)))
                 (done)))
        (.catch (fn [err]
                  (is false (str "QuickJS WASM engine initialization / page load failed: "
                                 (or (.-message err) err)))
                  (done))))))
