(ns browser.smoke
  "Minimal real WebGL-backed browser.session demo: kotoba-shell's `ui smoke`
   gate for the :browser substrate (see shell's `ui-substrate-specs`).

   Unlike `browser.demo` (the full-feature manual dev showcase: real QuickJS
   scripts, WebSocket/Worker/fetch, generated content, 1350px page), this is
   deliberately small: `browser.visual-smoke-model` (already proven against a
   recording host in `test/browser/visual_smoke_model_test.clj`) drives a real
   `browser.session` wired to a real `kotoba.wasm.host.webgl` host, with no
   script engine involved (`model/page`'s `:html` has no `<script>` tags, so
   `session/load-html!` never needs `ensure-script-engine!`)."
  (:require [browser.session :as session]
            [browser.visual-smoke-model :as model]
            [kotoba.wasm.host.webgl :as webgl]))

(defonce session-state (atom nil))

(defn fail!
  [message]
  (when-let [el (.getElementById js/document "status")]
    (set! (.-textContent el) message)))

(defn ^:export init!
  []
  (let [gl-canvas (.getElementById js/document "kotoba-gl")
        text-canvas (.getElementById js/document "kotoba-text")
        [width height] model/viewport
        host (webgl/create-host! {:gl-canvas gl-canvas
                                  :text-canvas text-canvas
                                  :width width
                                  :height height})
        s (session/new-session {:host host
                                :viewport model/viewport
                                :theme model/theme
                                :surface (model/surface-model)})
        s (session/apply-surface-action! s (model/launch-action))
        s (session/load-html! s model/page)]
    (reset! session-state s)
    (fail! "WebGL host active")))

(defn ^:dev/after-load reload!
  []
  (when-let [s @session-state]
    (reset! session-state (session/load-html! s model/page))))
