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
   real :browser/ops for us.

   ## Real networking: WebSocket, Worker, fetch()

   A later session on this repo built three more real capabilities into the
   underlying engine (browser.net.websocket, quickjs-wasm's real second-
   context Worker execution, and quickjs-wasm's __kotobaMakeDeferred-backed
   real fetch() delivery), each individually proven via isolated Node-based
   CLJS smoke tests (test-cljs/browser/compat/quickjs_websocket_smoke_test.cljs,
   quickjs_worker_smoke_test.cljs, quickjs_fetch_smoke_test.cljs). This demo
   now exercises all three live, in a real browser tab, against a REAL small
   local server (scripts/demo-server.js -- serves the SAME static public/
   files shadow-cljs's own devtools http server does, PLUS a /worker.js
   script, a /api/fetch-data endpoint, and a /ws-echo RFC6455 echo, the
   real-server analogue of what each smoke test stood up for itself):

     - `init!` derives the demo server's own origin from
       `window.location.origin` (this page IS served by that same real
       server -- see scripts/demo-server.js), then performs a REAL, native
       `js/fetch` (the host browser's own fetch client, NOT the QuickJS-
       guest-shimmed `fetch()` below) against `/worker.js` and
       `/api/fetch-data` before the QuickJS engine ever starts. This repo's
       `:fetch-fn` contract is synchronous end-to-end (see
       `browser.net.http`'s `:cljs` branch docstring), so exactly like
       `quickjs_worker_smoke_test.cljs`/`quickjs_fetch_smoke_test.cljs`'s own
       `build-session`, the synchronous `:fetch-fn` this demo injects
       (`synchronous-fetch-fn` below) is a thin wrapper around these
       ALREADY-real-fetched responses, not a second, separate fabrication.
     - `sample-html`'s real `<script>` tags then really `new Worker(...)`
       (really re-fetches + really evaluates `/worker.js`'s real source in a
       SECOND, independent QuickJS context) and really `fetch(...)`
       (`:net/fetch` capability, delivered via the pre-fetched response
       above) -- both replies are eagerly captured synchronously and
       delivered into the calling script's `onmessage`/`.then()` chain by
       the NEXT `<script>` tag's snapshot install, exactly as
       `browser.compat.quickjs-runner`'s namespace docstring describes, so
       both round trips complete within ONE `session/load-html!` call.
     - WebSocket's real echo genuinely has to travel over a real socket to
       the real server and back, so it CANNOT complete within a single
       synchronous `run-page-scripts!` reduce over `sample-html`'s tags (see
       `quickjs_websocket_smoke_test.cljs`'s own docstring on exactly this
       point) -- `init!` instead drives it directly via two
       `quickjs-runner/run-script!` calls sandwiching a real
       `js/Promise`+`js/setTimeout` wait, the same real-wall-clock-time
       technique that smoke test uses, sharing the SAME
       `:script/generation` (hence the SAME page-lifetime runtime state,
       including `:websocket/handles`) as the page `sample-html` already
       loaded.

   Each proof lands in its own real DOM element (`#ws-proof`,
   `#worker-proof`, `#fetch-proof`) via a real `document.getElementById(...)
   .textContent = ...` mutation -- painted for real by the same WebGL
   pipeline as everything else on the page -- alongside the original
   `document.title` proof, which is left completely unchanged.

   ## Real generated content: ::before/::after, attr(), counter()

   A later session on kotoba-lang/cssom added real ::before/::after
   generated-content support to `cssom.core/apply-cascade`: a `content`
   declaration resolves quoted-string literals, `attr(name)` references
   (the real element's own real HTML attribute value), and `counter(name)`
   references (a real running per-document counter, mutated by
   `counter-reset`/`counter-increment` declarations walked in real
   document tree order -- e.g. CSS-only sequential `<li>` numbering), each
   individually proven via Clojure tests directly against cssom.core/
   cssom.layout (test/cssom/core_test.clj, test/cssom/layout_test.clj in
   that repo), but none were exercised by this demo's own `sample-html` at
   all.

   Wiring this up surfaced a real gap in THIS demo's own pipeline:
   pseudo-elements need an actual CSS RULE with a selector (an inline
   `style=\"...\"` attribute can only ever target the real element itself,
   never a generated pseudo-element box), so a real `<style>` block is
   required -- but `browser.session/load-html!` (the fn `init!` below
   already calls) was silently DROPPING its own `:css` argument before
   forwarding to `browser.core/load-html` (which already accepted and
   cascaded one), and `htmldom.core` treats a literal `<style>` tag as an
   ordinary non-rendered raw-text element (see `cssom.layout/
   non-rendered-tags`) -- its text is never extracted back out and fed to
   `cssom.core/parse-rules`. Concretely: writing a real `<style>` block
   into this demo's HTML would, by itself, do nothing at all through this
   demo's existing session/cascade path. Fixed `browser.session/load-html!`
   to actually forward `:css` (see that fn's own docstring for the small,
   additive, backward-compatible fix and how it was verified not to
   regress the one existing test that happened to already pass a `:css`
   string through it).

   `sample-html`'s new `#generated-content-proof` section below embeds a
   REAL `<style>` block (`generated-content-css`) -- syntactically the
   exact same real CSS text any real browser would also honor when
   rendering this exact page -- and `init!` ALSO passes that identical
   string as `session/load-html!`'s (now real) `:css` argument, so the
   SAME text drives the SAME cascade this demo's underlying engine
   actually runs, not a second, divergent copy. `#status-badge`'s
   `data-status=\"live\"` attribute is rendered through `[data-status]
   ::before { content: attr(data-status) \": \"; }`; `#step-counter`'s
   four real sibling `<li>` elements are numbered \"1. \"/\"2. \"/
   \"3. \"/\"4. \" purely by `counter-reset`/`counter-increment` +
   `content: counter(step) \". \";`.

   Unlike the WS/Worker/fetch proofs above (each a real DOM mutation,
   readable through `browser.dom-bridge`'s `:text-content`), a pseudo-
   element's generated content is never a DOM node or mutation at all --
   `cssom.core/apply-cascade` instead decorates the REAL element's own
   `:attrs` with a synthetic `:pseudo/before`/`:pseudo/after` style map
   (see that namespace's docstring), so this demo reads it back the same
   real way cssom's own tests do: `browser.dom-bridge/node-snapshot`'s
   `:attrs` key (not `:text-content`) -- see `pseudo-content` below."
  (:require [browser.compat.quickjs-runner :as quickjs-runner]
            [browser.dom-bridge :as dom-bridge]
            [browser.net.websocket :as ws]
            [browser.origin :as origin]
            [browser.profile :as profile]
            [browser.session :as session]
            [clojure.string :as str]
            [kotoba.wasm.host :as host]
            [kotoba.wasm.host.webgl :as webgl]))

(defonce demo-state (atom nil))

(def viewport-width 760)
(def viewport-height 1250)

(def generated-content-css
  "The real CSS text embedded, verbatim, in `sample-html`'s real `<style>`
   block below -- see this namespace's docstring's \"Real generated
   content\" section for why `init!` ALSO passes this exact same string as
   `session/load-html!`'s `:css` argument: this repo's own
   `browser.session/load-html!` -> `browser.core/load-html` pipeline does
   not itself extract a `<style>` tag's text back out of parsed HTML (see
   `browser.session/load-html!`'s own docstring), so the `<style>` tag
   below is real, honest HTML (any real browser would also honor it
   rendering this exact page) but needs this same text handed to the
   cascade explicitly for THIS engine to actually apply it too."
  (str "[data-status]::before { content: attr(data-status) \": \"; color: #7fce7f; font-size: 12 } "
       "#step-counter { counter-reset: step } "
       "#step-counter li { counter-increment: step } "
       "#step-counter li::before { content: counter(step) \". \"; color: #e0a458; font-size: 12 }"))

(defn sample-html
  "The demo page's real HTML, parameterized on `worker-url`/`fetch-url` (the
   real local demo server's own /worker.js and /api/fetch-data endpoints --
   see `init!`, which derives them from `window.location.origin` and passes
   them in). Three real `<script>` tags:

     1. (unchanged from before this session) mutates `document.title`
        through the real dom/mutate capability path.
     2. really opens a Worker at `worker-url` and really calls
        `fetch(fetch-url)`, registering real `onmessage`/`.then()` callbacks
        that mutate `#worker-proof`/`#fetch-proof`'s real `textContent`.
     3. an intentionally empty script whose sole purpose is to BE the next
        `<script>` tag: its own eval-dispose/snapshot-install phase is what
        actually delivers script 2's queued real Worker reply and real
        fetch() response into those still-registered callbacks (see
        `browser.compat.quickjs-runner`'s namespace docstring, \"Real Worker
        execution\"/\"Real fetch() response delivery\" sections) -- mirroring
        `quickjs_worker_smoke_test.cljs`/`quickjs_fetch_smoke_test.cljs`'s own
        empty `script2`.

   Also embeds a real `<style>` block (`generated-content-css`) and a
   `#generated-content-proof` section exercising real ::before/::after
   generated content (`content: attr(...)` on `#status-badge`, `content:
   counter(...)` numbering `#step-counter`'s four real `<li>` siblings) --
   see this namespace's own docstring, \"Real generated content\" section,
   for why `init!` must ALSO pass `generated-content-css` as
   `session/load-html!`'s `:css` argument for this to actually cascade."
  [worker-url fetch-url]
  (str
   "<head><title>Kotoba Browser Demo (before script)</title>"
   "<style>" generated-content-css "</style>"
   "</head>"
   "<main style=\"display:flex; flex-direction:column; gap:12px; padding:16px; background:#0b0e14\">"
   "<h1 style=\"color:#e6ebf5; font-size:20\">Kotoba Browser -- real pipeline demo</h1>"
   "<section id=\"multi-listener-proof\" style=\"display:flex; flex-direction:column; gap:8\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real multi-listener addEventListener below -- previously a SECOND "
   "listener on the same (element, event-type) pair silently overwrote "
   "the first, so only the most-recently-added listener ever fired. "
   "Self-triggered via a real script-side button.click() below (this demo "
   "has no real canvas pointer-event wiring yet, so a real mouse click "
   "here wouldn't reach the document)."
   "</p>"
   "<button id=\"multi-listener-button\">Auto-clicked by script</button>"
   "<div id=\"multi-listener-result\">multi-listener proof: pending...</div>"
   "</section>"
   "<section id=\"quoted-gt-proof\" style=\"display:flex; flex-direction:column; gap:8\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real literal &gt; inside a quoted attribute value below -- previously "
   "truncated the tag at the INNER &gt;, corrupting every attribute after "
   "it and leaking the rest of the value as stray text that could hide "
   "later siblings entirely."
   "</p>"
   "<div id=\"gt-source\" title=\"a &gt; b\" data-onclick-demo=\"if (a&gt;b) foo()\" "
   "style=\"display:none\">source element (hidden, read by script below)</div>"
   "<div id=\"gt-result\">quoted-&gt; proof: pending...</div>"
   "<div id=\"gt-sibling-proof\">sibling after the quoted-&gt; tag: rendered correctly</div>"
   "</section>"
   "<section id=\"insert-adjacent-proof\" style=\"display:flex; flex-direction:column; gap:8\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real insertAdjacentHTML below -- previously entirely missing, one of "
   "the most common real-world DOM-mutation APIs (dynamic list/row "
   "insertion without wiping existing children)."
   "</p>"
   "<ul id=\"insert-adjacent-list\"><li id=\"middle-item\">middle</li></ul>"
   "</section>"
   "<section id=\"template-proof\" style=\"display:flex; flex-direction:column; gap:8\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real &lt;template&gt; below -- previously rendered its content exactly "
   "like an ordinary element; a real &lt;template&gt; row prototype for "
   "later JS cloning must never visibly render on its own."
   "</p>"
   "<ul id=\"template-list\">"
   "<template id=\"row-proto\"><li>PROTOTYPE ROW -- must NOT render</li></template>"
   "<li>real, visible row</li>"
   "</ul>"
   "</section>"
   "<section id=\"constraint-invalid-proof\" style=\"display:flex; flex-direction:column; gap:8\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real element.matches(':invalid')/':valid' below, evaluated inside the "
   "real QuickJS VM against inputs with min/max -- previously this JS-side "
   "copy of constraint validation had no range check at all, so an "
   "out-of-range number reported :valid here even though it already "
   "painted :invalid styling and already blocked real form submission."
   "</p>"
   "<input id=\"c-invalid-input\" type=\"number\" min=\"1\" max=\"10\" value=\"15\" style=\"display:none\">"
   "<input id=\"c-valid-input\" type=\"number\" min=\"1\" max=\"10\" value=\"5\" style=\"display:none\">"
   "<div id=\"constraint-invalid-result\">constraint-invalid proof: pending...</div>"
   "</section>"
   "<section id=\"absolute-right-bottom-proof\" style=\"position:relative; width:220; height:80; background:#16202f; border-width:2; border-color:#4fb3a6\">"
   "<p style=\"color:#9fb0c9; font-size:13; margin:8\">"
   "Real position:absolute; right/bottom below -- previously silently "
   "ignored (only left/top were read), so a corner-pinned badge always "
   "landed at the box's top-left corner instead."
   "</p>"
   "<div id=\"corner-badge\" style=\"position:absolute; right:8; bottom:8; width:64; height:24; background:#e0a458; color:#121724\">badge</div>"
   "</section>"
   "<section id=\"hidden-attribute-proof\" style=\"display:flex; flex-direction:column; gap:8\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real hidden attribute below -- previously never consulted at all, so "
   "&lt;div hidden&gt; rendered exactly like an ordinary, visible div."
   "</p>"
   "<div id=\"hidden-marker\" hidden=\"\">MUST NOT RENDER -- hidden attribute</div>"
   "<div id=\"visible-sibling-marker\">visible sibling renders normally</div>"
   "</section>"
   "<section id=\"replace-child-proof\" style=\"display:flex; flex-direction:column; gap:8\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real Node.replaceChild below -- previously entirely missing, one of "
   "the oldest and most common DOM Level 1 mutation methods."
   "</p>"
   "<ul id=\"replace-child-list\"><li>first</li><li id=\"replace-target\">to be replaced</li><li>last</li></ul>"
   "</section>"
   "<section id=\"duplicate-attribute-proof\" style=\"display:flex; flex-direction:column; gap:8\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real duplicate class attribute below -- previously the LAST "
   "occurrence silently won; real HTML5 tokenization keeps the FIRST."
   "</p>"
   "<div id=\"dup-attr-source\" class=\"box-first\" class=\"box-second\" style=\"display:none\">source (hidden, read by script below)</div>"
   "<div id=\"dup-attr-result\">duplicate-attribute proof: pending...</div>"
   "</section>"
   "<section id=\"remove-listener-proof\" style=\"display:flex; flex-direction:column; gap:8\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real removeEventListener below -- previously the WASM host ABI had no "
   "case for this op at all, crashing the entire real commit the instant "
   "a real page called it."
   "</p>"
   "<button id=\"remove-listener-button\">Auto-clicked twice by script</button>"
   "<div id=\"remove-listener-result\">remove-listener proof: pending...</div>"
   "</section>"
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
   "<section style=\"display:flex; flex-direction:column; gap:8; margin-top:4\">"
   "<div id=\"ws-proof\" style=\"background:#101820; border-width:2; border-color:#3aa1c9; padding:8; color:#bfe4f2\">WebSocket proof: pending real echo round-trip...</div>"
   "<div id=\"worker-proof\" style=\"background:#181022; border-width:2; border-color:#b06fe0; padding:8; color:#e6d3fa\">Worker proof: pending real second-QuickJS-context reply...</div>"
   "<div id=\"fetch-proof\" style=\"background:#101f10; border-width:2; border-color:#7fce7f; padding:8; color:#d3f2d3\">fetch() proof: pending real HTTP response...</div>"
   "</section>"
   "<section id=\"generated-content-proof\" style=\"display:flex; flex-direction:column; gap:8; margin-top:4\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real cssom ::before/::after generated content below -- content: "
   "attr(...) and content: counter(...), resolved by the real &lt;style&gt; "
   "block's cascade this session's cssom.core/apply-cascade added, not "
   "mocked here."
   "</p>"
   "<div id=\"status-badge\" data-status=\"live\" class=\"status-badge\" style=\"background:#101f1a; border-width:2; border-color:#4fb3a6; padding:8; color:#d3f2d3\">Kotoba engine</div>"
   "<ol id=\"step-counter\" style=\"display:flex; flex-direction:column; gap:4; background:#181818; border-width:2; border-color:#e0a458; padding:8\">"
   "<li id=\"step-1\" class=\"step\" style=\"color:#e6ebf5; font-size:13\">Real HTML parsed into a real kotoba.wasm.dom document</li>"
   "<li id=\"step-2\" class=\"step\" style=\"color:#e6ebf5; font-size:13\">Real cssom cascade resolves ::before/::after generated content</li>"
   "<li id=\"step-3\" class=\"step\" style=\"color:#e6ebf5; font-size:13\">Real box-model layout produces real draw-ops</li>"
   "<li id=\"step-4\" class=\"step\" style=\"color:#e6ebf5; font-size:13\">Real WebGL rasterization paints the canvas</li>"
   "</ol>"
   "</section>"
   "<section id=\"input-caret-proof\" style=\"display:flex; flex-direction:column; gap:8; margin-top:4\">"
   "<p style=\"color:#9fb0c9; font-size:13\">"
   "Real &lt;input&gt; caret/selection painting below -- previously a "
   "literal, garbled word \"null\" instead of a cursor bar / highlight, "
   "since the draw-op had no :text key at all and the host's paint case "
   "unconditionally called fillText with it."
   "</p>"
   "<input id=\"caret-proof\" value=\"kotoba\" selection-start=\"3\" selection-end=\"3\" "
   "style=\"font-size:16\">"
   "<input id=\"selection-proof\" value=\"kotoba engine\" selection-start=\"3\" selection-end=\"9\" "
   "style=\"font-size:16\">"
   "</section>"
   "<script>"
   "document.title = 'Kotoba Browser: real QuickJS + real cssom layout + real WebGL paint';"
   "</script>"
   "<script>"
   "var btn = document.getElementById('multi-listener-button');"
   "var result = document.getElementById('multi-listener-result');"
   "btn.addEventListener('click', function() { result.textContent = 'multi-listener proof: handler A fired'; });"
   "btn.addEventListener('click', function() { result.textContent += ' + handler B fired'; });"
   "btn.click();"
   "</script>"
   "<script>"
   "var gtSource = document.getElementById('gt-source');"
   "var gtResult = document.getElementById('gt-result');"
   "gtResult.textContent = 'quoted->  proof: title=\"' + gtSource.getAttribute('title') + '\" data-onclick-demo=\"' + "
   "gtSource.getAttribute('data-onclick-demo') + '\"';"
   "</script>"
   "<script>"
   "var list = document.getElementById('insert-adjacent-list');"
   "list.insertAdjacentHTML('afterbegin', '<li id=\"first-item\">first</li>');"
   "list.insertAdjacentHTML('beforeend', '<li id=\"last-item\">last</li>');"
   "</script>"
   "<script>"
   "var cInvalid = document.getElementById('c-invalid-input');"
   "var cValid = document.getElementById('c-valid-input');"
   "var cResult = document.getElementById('constraint-invalid-result');"
   "cResult.textContent = 'constraint-invalid proof: value=15 (max=10) matches(:invalid)=' + "
   "cInvalid.matches(':invalid') + ', value=5 (in [1,10]) matches(:valid)=' + cValid.matches(':valid');"
   "</script>"
   "<script>"
   "var rcList = document.getElementById('replace-child-list');"
   "var rcOld = document.getElementById('replace-target');"
   "var rcNew = document.createElement('li');"
   "rcNew.textContent = 'replaced';"
   "rcList.replaceChild(rcNew, rcOld);"
   "</script>"
   "<script>"
   "var dupSource = document.getElementById('dup-attr-source');"
   "var dupResult = document.getElementById('dup-attr-result');"
   "dupResult.textContent = 'duplicate-attribute proof: class=\"' + "
   "dupSource.getAttribute('class') + '\"';"
   "</script>"
   "<script>"
   "var rlBtn = document.getElementById('remove-listener-button');"
   "var rlResult = document.getElementById('remove-listener-result');"
   "var rlLog = [];"
   "function rlHandlerA() { rlLog.push('A'); }"
   "function rlHandlerB() { rlLog.push('B'); }"
   "rlBtn.addEventListener('click', rlHandlerA);"
   "rlBtn.addEventListener('click', rlHandlerB);"
   "rlBtn.click();"
   "rlBtn.removeEventListener('click', rlHandlerA);"
   "rlBtn.click();"
   "rlResult.textContent = 'remove-listener proof: ' + rlLog.join(',');"
   "</script>"
   "<script>"
   "var w = new Worker(" (pr-str worker-url) ");"
   "w.onmessage = function(e) { document.getElementById('worker-proof').textContent = "
   "'Worker proof: real 2nd QuickJS context computed 21 * 2 -> ' + e.data; };"
   "w.postMessage(21);"
   "fetch(" (pr-str fetch-url) ")"
   ".then(function(r) { return r.text(); })"
   ".then(function(t) { document.getElementById('fetch-proof').textContent = "
   "'fetch() proof: real HTTP response body -> \"' + t + '\"'; });"
   "</script>"
   "<script>"
   "void 0;"
   "</script>"
   "</main>"))

(defn ws-script1-source
  "Real JS source for the FIRST WebSocket round-trip script (see `init!`):
   opens a real connection to `ws-url`, registers a real `onmessage` that
   mutates `#ws-proof`'s real `textContent`, and sends real data. Mirrors
   `quickjs_websocket_smoke_test.cljs`'s `run-script1!` script source."
  [ws-url]
  (str "var ws = new WebSocket(" (pr-str ws-url) ");"
       "ws.onmessage = function(e) { document.getElementById('ws-proof').textContent = "
       "'WebSocket proof: real echo round-trip -> \"' + e.data + '\"'; };"
       "ws.send('hello from the real kotoba-lang/browser demo');"))

(def ws-script2-source
  "Real JS source for the SECOND WebSocket round-trip script: deliberately
   empty, exactly like `quickjs_websocket_smoke_test.cljs`'s `run-script2!`
   -- its own eval-dispose/snapshot-install phase is what delivers the real
   echoed reply into script 1's still-registered `ws.onmessage`."
  "void 0;")

(defn grant-demo-permissions
  "A real `browser.profile`, granting exactly the three capabilities this
   demo's networking proofs need, each at the real origin the corresponding
   request actually targets (see `browser.origin/origin` -- WebSocket's
   `ws://` origin differs from Worker/fetch's `http://` origin even though
   all three point at the SAME real local server, since origin includes
   scheme). Public (not `defn-`) so `test-cljs/browser/demo_smoke_test.cljs`
   can drive this demo's exact real permission wiring against its own real
   local Node server, not a re-typed copy."
  [worker-url fetch-url ws-url]
  (-> (profile/new-profile {:id "browser-demo"})
      (profile/grant-permission (origin/origin worker-url) :worker/create)
      (profile/grant-permission (origin/origin fetch-url) :net/fetch)
      (profile/grant-permission (origin/origin ws-url) :websocket/connect)))

(defn synchronous-fetch-fn
  "A synchronous `:fetch-fn` (the shape `browser.compat.quickjs-execution`'s
   `:net/fetch` case, AND `worker-create-result`'s bare `:fetch-fn` call for
   fetching a Worker's own script, both need) backed by REAL responses
   `init!` already fetched for real, natively (see `real-prefetch!` below),
   before the QuickJS engine's guest scripts ever ran. Mirrors
   `quickjs_worker_smoke_test.cljs`/`quickjs_fetch_smoke_test.cljs`'s own
   `build-session` `:fetch-fn` -- see those namespaces' docstrings for why
   this repo's `:fetch-fn` contract is synchronous end-to-end while a real
   fetch is not. Public (not `defn-`), same reason as
   `grant-demo-permissions` above -- reused verbatim by
   `test-cljs/browser/demo_smoke_test.cljs`, keyed by that test's own real,
   Node-`http.get`-prefetched responses instead of `real-prefetch!`'s
   browser-`js/fetch` ones (only the real HTTP client differs -- see that
   test's own docstring)."
  [prefetched]
  (fn demo-fetch-fn [{:keys [url]}]
    (get prefetched url {:status 0 :error :net/fetch-not-prefetched})))

(defn- real-prefetch!
  "A REAL, native `js/fetch` call (the actual host browser's own network
   client -- NOT the QuickJS-guest-shimmed `fetch()` `quickjs-wasm`'s webapi
   shim installs, see this namespace's docstring) against `url`. Same-origin
   with this very page (both served by scripts/demo-server.js), so no CORS
   concern for this real, outer fetch. Returns a Promise of a
   browser.net-shaped response map ({:status :headers :body}), the same
   shape `browser.net.http/fetch!` and the `:clj` `fetch-fn` produce."
  [url]
  (-> (js/fetch url)
      (.then (fn [resp]
               (-> (.text resp)
                   (.then (fn [text]
                            {:status (.-status resp)
                             :headers (into {}
                                            (map (fn [pair] [(aget pair 0) (aget pair 1)]))
                                            (es6-iterator-seq (.entries (.-headers resp))))
                             :body text})))))))

(defn- wait-ms
  "A real `js/Promise`+`js/setTimeout` wait -- genuine wall-clock time has
   to pass here for the real WebSocket echo to genuinely travel to the real
   local server and back before script 2 asks for it (see this namespace's
   docstring and `quickjs_websocket_smoke_test.cljs`)."
  [ms]
  (js/Promise. (fn [resolve _reject] (js/setTimeout resolve ms))))

(defn- text-draw-op-count
  [draw-ops]
  (count (filter #(= :text (:draw/op %)) draw-ops)))

(defn- gl-pixel-summary
  "Real WebGL pixel readback, taken synchronously in the SAME JS turn as
   the paint call (immediately after the WebSocket round trip's second
   script -- `run-script!` -- returns below, the LAST real paint this
   demo's init! triggers) -- deliberately not deferred to a later tick/
   animation-frame. The webgl host's GL context is created without
   `preserveDrawingBuffer` (see dom-gpu's
   `kotoba.wasm.host.webgl/create-host!`), so a real browser is allowed to
   clear the drawing buffer the next time it composites a frame; reading it
   back right here, before yielding back to the event loop, is what makes
   this a genuine proof of the pixels this session's real box-model/
   flexbox/grid/text-wrap layout + real WebGL rasterization just produced,
   rather than a snapshot that could race a later compositor clear."
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

(defn- element-text
  "Host-side read of a real element's real, current `textContent` (the
   SAME thing a real `document.getElementById(id).textContent` read from
   inside the QuickJS VM would see), via `browser.dom-bridge`'s already-
   public `get-element-by-id`/`node-snapshot` -- used here only for the
   demo's own console/status logging, not a new capability."
  [document id]
  (some->> (dom-bridge/get-element-by-id document id)
           (dom-bridge/node-snapshot document)
           :text-content))

(defn- pseudo-content
  "Host-side read of a real element's real, cascade-resolved `::before`
   generated content -- UNLIKE `element-text` above, a pseudo-element is
   never a DOM node or mutation (see this namespace's docstring, \"Real
   generated content\" section), so there is no `:text-content` to read for
   it. `cssom.core/apply-cascade` instead decorates the real element's own
   `:attrs` with a synthetic `:pseudo/before` style map holding the
   resolved `content` string (already substituted: a literal stays
   literal, `attr(name)` becomes that element's own real attribute value,
   `counter(name)` becomes that counter's real running value at this exact
   point in document tree order) -- read here via the SAME
   `browser.dom-bridge/get-element-by-id`+`node-snapshot` `element-text`
   uses, just its `:attrs` key instead of `:text-content`, exactly how
   `cssom.core-test`/`cssom.layout-test` verify this feature directly."
  [document id]
  (some->> (dom-bridge/get-element-by-id document id)
           (dom-bridge/node-snapshot document)
           :attrs
           :pseudo/before
           :content))

(defn ^:export init!
  []
  (let [gl-canvas (.getElementById js/document "kotoba-gl")
        text-canvas (.getElementById js/document "kotoba-text")
        host (webgl/create-host! {:gl-canvas gl-canvas
                                  :text-canvas text-canvas
                                  :width viewport-width
                                  :height viewport-height})
        server-origin (.. js/window -location -origin)
        worker-url (str server-origin "/worker.js")
        fetch-url (str server-origin "/api/fetch-data")
        ws-url (str (str/replace server-origin #"^http" "ws") "/ws-echo")]
    (js/console.log "browser.demo: real prefetching (native js/fetch, same-origin"
                     "with this very page -- see scripts/demo-server.js) the worker"
                     "script + fetch data from" server-origin "...")
    (-> (js/Promise.all #js [(real-prefetch! worker-url) (real-prefetch! fetch-url)])
        (.then
         (fn [results]
           (let [worker-response (aget results 0)
                 fetch-response (aget results 1)]
             (js/console.log "browser.demo: real prefetch results ->"
                              (pr-str {:worker worker-response :fetch fetch-response}))
             (let [prefetched {worker-url worker-response fetch-url fetch-response}
                   demo-profile (grant-demo-permissions worker-url fetch-url ws-url)
                   base-session (session/new-session
                                 (quickjs-runner/quickjs-session-opts
                                  {:host host
                                   :viewport [viewport-width viewport-height]
                                   :profile demo-profile
                                   :fetch-fn (synchronous-fetch-fn prefetched)
                                   :websocket-fn (ws/websocket-fn)}))]
               (js/console.log "browser.demo: starting real QuickJS script engine...")
               (session/ensure-script-engine! base-session)))))
        (.then
         (fn [ready-session]
           (js/console.log "browser.demo: script engine status ->"
                            (pr-str (get-in ready-session
                                            [:browser.session/script-engine
                                             :script-engine/status])))
           (let [after (session/load-html!
                        ready-session
                        {:url "kotoba://browser-demo/index"
                         :html (sample-html worker-url fetch-url)
                         :css generated-content-css})
                 generation (:browser.session/page-generation after)
                 after-document (get-in after [:browser.session/page :browser/document])]
             (js/console.log "browser.demo: document.title after real"
                              "<script>document.title = '...'</script> ->"
                              (pr-str (get-in after [:browser.session/page :browser/title])))
             (js/console.log "browser.demo: real Worker + fetch() proofs after page"
                              "scripts -> worker:"
                              (pr-str (element-text after-document "worker-proof"))
                              "fetch:" (pr-str (element-text after-document "fetch-proof")))
             ;; Generated content (::before/::after, attr(), counter()) is
             ;; fully resolved synchronously as part of the real cascade
             ;; `session/load-html!` just ran above -- unlike the WS/Worker/
             ;; fetch proofs, it needs no wall-clock wait or second script
             ;; turn at all, so it's read back and logged right here.
             (js/console.log "browser.demo: real ::before attr() proof --"
                              "#status-badge's real data-status attribute"
                              "resolved by [data-status]::before ->"
                              (pr-str (pseudo-content after-document "status-badge")))
             (js/console.log "browser.demo: real ::before counter() proof --"
                              "#step-counter's four real sibling <li>s"
                              "numbered purely by CSS counters ->"
                              (pr-str (mapv #(pseudo-content after-document %)
                                            ["step-1" "step-2" "step-3" "step-4"])))
             ;; WebSocket's real echo genuinely has to travel over a real
             ;; socket to our real local server and back -- unlike Worker/
             ;; fetch (whose replies are already eagerly captured
             ;; synchronously by the time their own <script> tag finishes,
             ;; see browser.compat.quickjs-runner's namespace docstring), so
             ;; this cannot be embedded as more <script> tags in the SAME
             ;; synchronous run-page-scripts! reduce sample-html just went
             ;; through above -- it needs a genuine, separate real-wall-
             ;; clock-time boundary, driven directly here exactly the way
             ;; quickjs_websocket_smoke_test.cljs does.
             (let [opened (quickjs-runner/run-script!
                           after
                           {:script/type :classic
                            :script/url "kotoba://browser-demo/index/ws-open.js"
                            :script/generation generation
                            :script/source (ws-script1-source ws-url)})]
               (-> (wait-ms 500)
                   (.then (fn [_]
                            (quickjs-runner/run-script!
                             opened
                             {:script/type :classic
                              :script/url "kotoba://browser-demo/index/ws-deliver.js"
                              :script/generation generation
                              :script/source ws-script2-source}))))))))
        (.then
         (fn [final-session]
           (let [pixel-proof (gl-pixel-summary gl-canvas)
                 page (:browser.session/page final-session)
                 doc (:browser/document page)
                 draw-ops (:browser/draw-ops page)
                 quickjs-events (filter #(= :script/quickjs-run (:event %))
                                        (:browser.session/history final-session))
                 ws-proof (element-text doc "ws-proof")
                 worker-proof (element-text doc "worker-proof")
                 fetch-proof (element-text doc "fetch-proof")
                 constraint-invalid-proof (element-text doc "constraint-invalid-result")
                 duplicate-attribute-proof (element-text doc "dup-attr-result")
                 remove-listener-proof (element-text doc "remove-listener-result")
                 status-badge-proof (pseudo-content doc "status-badge")
                 step-proofs (mapv #(pseudo-content doc %)
                                   ["step-1" "step-2" "step-3" "step-4"])]
             (js/console.log "browser.demo: real WebGL pixel readback"
                              "(gl.readPixels, taken synchronously right"
                              "after the final real paint) ->" (pr-str pixel-proof))
             (set! (.-__kotobaDemoPixelProof js/window) (clj->js pixel-proof))
             (js/console.log "browser.demo: document.title ->" (pr-str (:browser/title page)))
             (js/console.log "browser.demo: #ws-proof ->" (pr-str ws-proof))
             (js/console.log "browser.demo: #worker-proof ->" (pr-str worker-proof))
             (js/console.log "browser.demo: #fetch-proof ->" (pr-str fetch-proof))
             (js/console.log "browser.demo: #constraint-invalid-result ->" (pr-str constraint-invalid-proof))
             (js/console.log "browser.demo: #dup-attr-result ->" (pr-str duplicate-attribute-proof))
             (js/console.log "browser.demo: #remove-listener-result ->" (pr-str remove-listener-proof))
             (js/console.log "browser.demo: real ::before generated content ->"
                              "#status-badge:" (pr-str status-badge-proof)
                              "#step-counter lis:" (pr-str step-proofs))
             (js/console.log "browser.demo: document ready-state ->"
                              (pr-str (get-in page [:browser/document :ready-state])))
             (js/console.log "browser.demo: real script/quickjs-run history events ->"
                              (pr-str quickjs-events))
             (js/console.log "browser.demo: real cssom.layout draw-ops ("
                              (count draw-ops) "ops,"
                              (text-draw-op-count draw-ops) "text ops -- the"
                              "wrapped paragraph and the generated-content"
                              "proofs below should together contribute more"
                              "than one) ->" (pr-str draw-ops))
             (reset! demo-state {:host host :session final-session})
             (when-let [status-el (.getElementById js/document "status")]
               (set! (.-textContent status-el)
                     (str "document.title -> " (:browser/title page)
                          " | " ws-proof
                          " | " worker-proof
                          " | " fetch-proof
                          " | " status-badge-proof
                          " | " (str/join "" step-proofs)
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
