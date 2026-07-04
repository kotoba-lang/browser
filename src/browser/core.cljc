(ns browser.core
  "Kotoba-only browser R0 orchestration over the existing kotoba UI substrate."
  (:require [browser.dom-bridge :as dom-bridge]
            [browser.page-script :as page-script]
            [cssom.core :as css]
            [cssom.layout :as layout]
            [htmldom.core :as html]
            [kotoba.wasm.dom :as dom]))

(defn render-document
  [{:keys [document css-rules viewport theme] :or {viewport [800 600]}}]
  (let [document (cond-> document
                   (seq css-rules) (css/apply-cascade css-rules))
        [ops document] (dom/consume-ops document)
        tree (dom/tree document)
        draw-ops (layout/draw-ops tree {:width (first viewport)
                                        :theme theme})]
    {:browser/document document
     :browser/tree tree
     :browser/title (dom-bridge/document-title document)
     :browser/ops ops
     :browser/draw-ops draw-ops}))

(defn refresh-page
  [page {:keys [document viewport theme] :as opts}]
  (merge page
         (render-document {:document (or document (:browser/document page))
                           :css-rules (:browser/css-rules page)
                           :viewport (or viewport (:browser/viewport page))
                           :theme (or theme (:browser/theme page))})))

(defn load-html
  [{:keys [url html css viewport theme] :or {viewport [800 600]}}]
  (let [rules (css/parse-rules css)
        document (assoc (html/parse-into-document html)
                        :url url
                        :ready-state "loading")
        base-href (dom-bridge/document-base-href document)
        document (cond-> document
                   base-href (assoc :base-uri (page-script/resolve-src url base-href))
                   (seq rules) (css/apply-cascade rules))
        rendered (render-document {:document document
                                   :viewport viewport
                                   :theme theme})]
    {:browser/url url
     :browser/document (:browser/document rendered)
     :browser/tree (:browser/tree rendered)
     :browser/title (:browser/title rendered)
     :browser/css-rules rules
     :browser/viewport viewport
     :browser/theme theme
     :browser/ops (:browser/ops rendered)
     :browser/draw-ops (:browser/draw-ops rendered)}))

(defn navigate
  "Navigate through an injected fetch capability.

  fetch-fn receives {:url ... :method :get} and returns {:status n :body html}."
  [{:keys [url fetch-fn viewport theme]}]
  (let [{:keys [status body] :as response} (fetch-fn {:url url :method :get})]
    (if (<= 200 (or status 0) 299)
      (assoc (load-html {:url url :html body :viewport viewport :theme theme})
             :browser/response response)
      {:browser/url url
       :browser/response response
       :browser/error :navigation/http-error})))
