(ns browser.core
  "Kotoba-only browser R0 orchestration over the existing kotoba UI substrate."
  (:require [browser.dom-bridge :as dom-bridge]
            [clojure.string]
            [browser.page-script :as page-script]
            [cssom.core :as css]
            [cssom.layout :as layout]
            [htmldom.core :as html]
            [kotoba.wasm.dom :as dom]))

(defn render-document
  [{:keys [document css-rules viewport theme color-scheme] :or {viewport [800 600]}}]
  (let [document (cond-> document
                   (seq css-rules) (css/apply-cascade css-rules {:viewport-width (first viewport)
                                                                 :color-scheme color-scheme}))
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
  [page {:keys [document viewport theme color-scheme] :as opts}]
  (merge page
         (render-document {:document (or document (:browser/document page))
                           :css-rules (:browser/css-rules page)
                           :viewport (or viewport (:browser/viewport page))
                           :theme (or theme (:browser/theme page))
                           :color-scheme (or color-scheme (:browser/color-scheme page))})))

(defn document-style-text
  "Concatenated text of every `<style>` element in the document, in document
  order.

  `load-html` used to take CSS only out-of-band (`:css`), so a page that
  carried its own `<style>` — which is how essentially every real page ships
  its styling — loaded with **zero** rules applied and laid out on defaults.
  Measured 2026-08-04: kobo's workbench console produced 467 draw ops and
  `:browser/css-rules` was empty; the whole design system was sitting in a
  `<style>` element the engine never read.

  The parser already keeps the element and its text (htmldom emits
  `[:dom/create-text …]` for it) — nothing was missing but this walk."
  [document]
  (let [nodes (:nodes document)
        text-of (fn [id]
                  (let [n (get nodes id)]
                    (when (= :text (:node/type n)) (:text n))))]
    (->> (:ops document)
         (keep (fn [[op id tag]] (when (and (= :dom/create-element op) (= :style tag)) id)))
         (mapcat (fn [id] (keep text-of (:children (get nodes id)))))
         (clojure.string/join "\n"))))

(defn load-html
  [{:keys [url html css viewport theme color-scheme] :or {viewport [800 600]}}]
  (let [document (assoc (html/parse-into-document html)
                        :url url
                        :ready-state "loading")
        ;; Out-of-band `:css` first, the document's own `<style>` after: on
        ;; equal specificity the page's own rules win, which is the order a
        ;; browser gives a host stylesheet vs the document's.
        rules (css/parse-rules (str css "\n" (document-style-text document)))
        base-href (dom-bridge/document-base-href document)
        document (cond-> document
                   base-href (assoc :base-uri (page-script/resolve-src url base-href))
                   (seq rules) (css/apply-cascade rules {:viewport-width (first viewport)
                                                         :color-scheme color-scheme}))
        rendered (render-document {:document document
                                   :viewport viewport
                                   :theme theme
                                   :color-scheme color-scheme})]
    {:browser/url url
     :browser/document (:browser/document rendered)
     :browser/tree (:browser/tree rendered)
     :browser/title (:browser/title rendered)
     :browser/css-rules rules
     :browser/viewport viewport
     :browser/theme theme
     :browser/color-scheme color-scheme
     :browser/ops (:browser/ops rendered)
     :browser/draw-ops (:browser/draw-ops rendered)}))

(defn navigate
  "Navigate through an injected fetch capability.

  fetch-fn receives {:url ... :method :get} and returns {:status n :body html}."
  [{:keys [url fetch-fn viewport theme color-scheme]}]
  (let [{:keys [status body] :as response} (fetch-fn {:url url :method :get})]
    (if (<= 200 (or status 0) 299)
      (assoc (load-html {:url url :html body :viewport viewport :theme theme :color-scheme color-scheme})
             :browser/response response)
      {:browser/url url
       :browser/response response
       :browser/error :navigation/http-error})))
