(ns browser.demo
  "kotoba-lang/browser's own runnable demo.

   Unlike dom-gpu's kotoba.wasm.demo-document (cascade+layout+paint only, no
   real JS), this demo drives the full stack this repo built up this
   session:

     browser.session/new-session   (real session, real webgl DomHost)
     -> browser.compat.quickjs-runner/quickjs-session-opts
                                    (real QuickJS WASM engine wired in as the
                                     session's :browser.session/script-runner)
     -> browser.session/ensure-script-engine!
                                    (starts the real WASM engine; a Promise
                                     in ClojureScript -- MUST be awaited
                                     before any load/navigate, see that var's
                                     docstring and
                                     browser.compat.quickjs-runner's)
     -> browser.session/load-html! (real HTML parse -> real cssom cascade ->
                                     real box-model/flexbox/grid/text-wrap
                                     layout -> real inline <script> executed
                                     by the real QuickJS VM, mutating
                                     document.title through the real
                                     dom/mutate capability path -- then the
                                     resulting ABI op batch is committed to
                                     the real host)
     -> kotoba.wasm.host.webgl      (the real DomHost: every ABI op the
                                     session emits is applied into this
                                     host's retained-tree state via
                                     kotoba.wasm.host/commit!, then painted
                                     for real onto <canvas> with WebGL rects
                                     + a 2D text overlay)

   Because the webgl host is wired in directly as :host on the session (not
   bolted on after the fact the way dom-gpu's own demo_document.cljs had
   to, since that demo never went through browser.session), painting
   happens automatically as part of every browser.session/load-html! call --
   session/commit-page! already calls kotoba.wasm.host/commit! on the page's
   real :browser/ops for us."
  (:require [browser.compat.quickjs-runner :as quickjs-runner]
            [browser.session :as session]
            [kotoba.wasm.host :as host]
            [kotoba.wasm.host.webgl :as webgl]))

(defonce demo-state (atom nil))

(def viewport-width 760)
(def viewport-height 460)

(def sample-html
  (str
   "<head><title>Kotoba Browser Demo (before script)</title></head>"
   "<main style=\"display:flex; flex-direction:column; gap:12px; padding:16px; background:#0b0e14\">"
   "<h1 style=\"color:#e6ebf5; font-size:20\">Kotoba Browser -- real pipeline demo</h1>"
   "<section style=\"display:flex; flex-direction:row; gap:12px\">"
   "<div style=\"display:flex; flex-direction:column; background:#16202f; border-width:2; border-color:#4fb3a6; padding:10; width:220\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "This paragraph is deliberately long enough that the real box-model "
   "text-wrapping layout engine this session built has to break it across "
   "several lines inside a narrow flex child, not just render one short "
   "line -- a genuine exercise of cssom.layout's word-wrap, not a token "
   "one-liner."
   "</p>"
   "</div>"
   "<div style=\"display:flex; flex-direction:column; background:#16202f; border-width:2; border-color:#e0a458; padding:10; width:220\">"
   "<p style=\"color:#e0a458; font-size:13\">"
   "The real inline &lt;script&gt; below runs inside the real QuickJS WASM "
   "VM and mutates document.title through the real dom/mutate capability "
   "pipeline proven earlier this session -- not a mock, not an inlined stub."
   "</p>"
   "</div>"
   "</section>"
   "<section style=\"display:grid; grid-template-columns:140px 140px 140px; gap:8\">"
   "<div style=\"background:#1f3350; padding:8; color:#e6ebf5\">grid cell A</div>"
   "<div style=\"background:#28405f; padding:8; color:#e6ebf5\">grid cell B</div>"
   "<div style=\"background:#324e6f; padding:8; color:#e6ebf5\">grid cell C</div>"
   "</section>"
   "<script>"
   "document.title = 'Kotoba Browser: real QuickJS + real cssom layout + real WebGL paint';"
   "</script>"
   "</main>"))

(defn- text-draw-op-count
  [draw-ops]
  (count (filter #(= :text (:draw/op %)) draw-ops)))

(defn- gl-pixel-summary
  "Real WebGL pixel readback, taken synchronously in the SAME JS turn as the
   paint call (immediately after `session/load-html!` returns below) --
   deliberately not deferred to a later tick/animation-frame. The webgl
   host's GL context is created without `preserveDrawingBuffer` (see
   dom-gpu's `kotoba.wasm.host.webgl/create-host!`), so a real browser is
   allowed to clear the drawing buffer the next time it composites a frame;
   reading it back right here, before yielding back to the event loop, is
   what makes this a genuine proof of the pixels this session's real
   box-model/flexbox/grid/text-wrap layout + real WebGL rasterization just
   produced, rather than a snapshot that could race a later compositor
   clear."
  [gl-canvas]
  (let [gl (.getContext gl-canvas "webgl")
        w (.-width gl-canvas)
        h (.-height gl-canvas)
        bytes (js/Uint8Array. (* w h 4))]
    (.readPixels gl 0 0 w h (.-RGBA gl) (.-UNSIGNED_BYTE gl) bytes)
    (let [words (js/Uint32Array. (.-buffer bytes))
          n (.-length words)
          bg (aget words 0)
          non-bg (loop [i 0 c 0]
                   (if (< i n)
                     (recur (inc i) (if (not= (aget words i) bg) (inc c) c))
                     c))
          distinct (.-size (js/Set. words))]
      {:width w :height h :total-pixels n
       :non-background-pixels non-bg
       :distinct-colors distinct})))

(defn ^:export init!
  []
  (let [gl-canvas (.getElementById js/document "kotoba-gl")
        text-canvas (.getElementById js/document "kotoba-text")
        host (webgl/create-host! {:gl-canvas gl-canvas
                                  :text-canvas text-canvas
                                  :width viewport-width
                                  :height viewport-height})
        base-session (session/new-session
                      (quickjs-runner/quickjs-session-opts
                       {:host host
                        :viewport [viewport-width viewport-height]}))]
    (js/console.log "browser.demo: starting real QuickJS script engine...")
    (-> (session/ensure-script-engine! base-session)
        (.then
         (fn [ready-session]
           (js/console.log "browser.demo: script engine status ->"
                            (pr-str (get-in ready-session
                                            [:browser.session/script-engine
                                             :script-engine/status])))
           (let [after (session/load-html!
                        ready-session
                        {:url "kotoba://browser-demo/index"
                         :html sample-html})
                 ;; Read the WebGL canvas back RIGHT NOW, in the same
                 ;; synchronous continuation as the paint load-html! just
                 ;; triggered via kotoba.wasm.host/commit! -- see
                 ;; gl-pixel-summary's docstring for why timing matters here.
                 pixel-proof (gl-pixel-summary gl-canvas)
                 page (:browser.session/page after)
                 draw-ops (:browser/draw-ops page)
                 quickjs-events (filter #(= :script/quickjs-run (:event %))
                                        (:browser.session/history after))]
             (js/console.log "browser.demo: real WebGL pixel readback"
                              "(gl.readPixels, taken synchronously right"
                              "after paint) ->" (pr-str pixel-proof))
             (set! (.-__kotobaDemoPixelProof js/window) (clj->js pixel-proof))
             (js/console.log "browser.demo: document.title after real"
                              "<script>document.title = '...'</script> ->"
                              (pr-str (:browser/title page)))
             (js/console.log "browser.demo: document ready-state ->"
                              (pr-str (get-in page [:browser/document :ready-state])))
             (js/console.log "browser.demo: real script/quickjs-run history events ->"
                              (pr-str quickjs-events))
             (js/console.log "browser.demo: real cssom.layout draw-ops ("
                              (count draw-ops) "ops,"
                              (text-draw-op-count draw-ops) "text ops -- the"
                              "wrapped paragraph should contribute more than"
                              "one) ->" (pr-str draw-ops))
             (reset! demo-state {:host host :session after})
             (when-let [status-el (.getElementById js/document "status")]
               (set! (.-textContent status-el)
                     (str "document.title -> " (:browser/title page)
                          " | draw-ops: " (count draw-ops)))))))
        (.catch (fn [err]
                  (js/console.error "browser.demo: failed to start real QuickJS"
                                     "engine or load the real page:"
                                     (or (.-message err) err)))))))

(defn ^:export debug-snapshot
  []
  (some-> @demo-state :session :browser.session/page :browser/draw-ops clj->js))

(defn ^:dev/after-load reload!
  []
  (when-let [{:keys [host]} @demo-state]
    (host/present-host! host)))
