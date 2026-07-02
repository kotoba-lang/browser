(ns browser.visual-smoke-model
  "Pure CLJC model for the browser WebGL visual smoke."
  (:require [browser.surface :as surface]))

(def viewport [760 460])

(def theme
  {:font-size 15
   :line-height 22
   :padding 7
   :gap 7
   :fg "#121826"
   :bg "#f5f7fb"
   :button-bg "#dce7f8"})

(def app-id "notes")

(def app-document
  [:article
   [:h1 "Kotoba browser"]
   [:p "WASM-only UI surface"]
   [:p "Rendered through kotoba:dom WebGL host"]])

(def page
  {:url "kotoba://smoke"
   :html "<main style=\"background: #ffffff; padding: 16px\"><h1>Browser document</h1><p style=\"color: #2057a7\">kotoba:dom committed</p><section overflow=\"auto\" scroll-top=\"8\" style=\"height: 28px; width: 220px; background: #eef3ff; padding: 4px\"><p>Scroll clipped content</p></section></main>"})

(defn surface-model
  []
  (-> (surface/empty-surface {:title "Kotoba Browser OS"
                              :viewport viewport
                              :theme theme})
      (surface/register-app {:app/id app-id
                             :app/title "Notes"
                             :app/default-rect [48 56 520 280]
                             :app/document app-document})))

(defn launch-action
  []
  {:action :app/launch
   :app-id app-id})
