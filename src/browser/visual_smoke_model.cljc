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

;; `overflow` is CSS and `scroll-top` is a runtime attribute, and the two
;; must be written that way round: cssom.layout reads `:overflow` only out
;; of the style map (its own comment: "exclusively a CSS property in real
;; HTML/CSS -- nothing sets it as an attribute"), while `scroll-top` really
;; is host state and really is read as an attribute. This fixture carried
;; `overflow="auto"` as an ATTRIBUTE until 2026-08-04, so the engine saw no
;; overflow at all, emitted no clip, and the "scroll clipped content" case
;; this page exists to exercise was never once exercised -- unnoticed
;; because nothing here asserted on the ops.
(def page
  {:url "kotoba://smoke"
   :html "<main style=\"background: #ffffff; padding: 16px\"><h1>Browser document</h1><p style=\"color: #2057a7\">kotoba:dom committed</p><section scroll-top=\"8\" style=\"overflow: auto; height: 28px; width: 220px; background: #eef3ff; padding: 4px\"><p>Scroll clipped content</p></section></main>"})

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
