(ns browser.dom-bridge-test
  (:require [browser.core :as browser]
            [browser.dom-bridge :as bridge]
            [clojure.test :refer [deftest is]]
            [kotoba.wasm.dom :as dom]))

(deftest query-selector-uses-css-selector-subset
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><button id=\"go\" class=\"primary\">Go</button><button>Cancel</button></main>"})
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        by-id (bridge/get-element-by-id document "go")
        buttons (bridge/query-selector-all document "button")]
    (is go)
    (is (= go by-id))
    (is (= 2 (count buttons)))
    (is (= "go" (get-in document [:nodes go :attrs :id])))))

(deftest get-element-by-id-matches-the-literal-id-attribute-not-a-css-selector
  ;; Real document.getElementById(id) is a literal, unescaped match against
  ;; the id attribute's exact string value -- never CSS selector parsing.
  ;; An id containing a CSS-special character (a period here) is common in
  ;; real-world HTML (e.g. ids mirroring backend field names like
  ;; "2fa.token") and must still be found, unlike (query-selector (str "#" id))
  ;; which would misinterpret the period as a class-selector separator.
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><input id=\"2fa.token\" value=\"otp\"><input id=\"a:b\" value=\"colon\"></main>"})
        document (:browser/document page)
        dotted (bridge/get-element-by-id document "2fa.token")
        coloned (bridge/get-element-by-id document "a:b")]
    (is (some? dotted))
    (is (= "2fa.token" (get-in document [:nodes dotted :attrs :id])))
    (is (some? coloned))
    (is (= "a:b" (get-in document [:nodes coloned :attrs :id])))
    (is (nil? (bridge/get-element-by-id document "does-not-exist")))))

(deftest query-selector-supports-descendant-and-child-combinators
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\"><section><p id=\"nested\" class=\"note\">Nested</p></section><p id=\"direct\" class=\"note\">Direct</p></main>"})
        document (:browser/document page)
        nested (bridge/query-selector document "main section .note")
        direct (bridge/query-selector document "main > .note")
        all-notes (bridge/query-selector-all document "main .note")]
    (is (= "nested" (get-in document [:nodes nested :attrs :id])))
    (is (= "direct" (get-in document [:nodes direct :attrs :id])))
    (is (= ["nested" "direct"]
           (mapv #(get-in document [:nodes % :attrs :id]) all-notes)))))

(deftest document-snapshot-includes-ready-state
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<head><base href=\"/assets/\"><title>Ready page</title></head><main>Ready</main>"})
        snapshot (bridge/document-snapshot (:browser/document page))]
    (is (= "loading" (:ready-state snapshot)))
    (is (= "kotoba://dom" (:url snapshot)))
    (is (= "kotoba://dom/assets/" (:base-uri snapshot)))
    (is (= "Ready page" (:title snapshot)))))

(deftest mutation-bridge-sets-document-title
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main>Untitled</main>"})
        result (bridge/apply-mutation (:browser/document page)
                                      {:dom/op :set-title
                                       :title "Updated"})
        document (:document result)]
    (is (= "Updated" (bridge/document-title document)))
    (is (= "Updated" (:title (bridge/document-snapshot document))))
    (is (= :title (get-in document [:nodes (bridge/title-node-id document) :tag])))))

(deftest query-selector-supports-attribute-selectors
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><input id=\"required\" required value=\"yes\"><input id=\"mode\" data-mode=\"edit\"><input id=\"other\" data-mode=\"view\"><a id=\"doc\" class=\"primary link\" lang=\"en-US\" href=\"https://example.com/admin/report.pdf\">Doc</a></main>"})
        document (:browser/document page)
        required (bridge/query-selector document "input[required]")
        edit (bridge/query-selector document "[data-mode=\"edit\"]")
        modes (bridge/query-selector-all document "input[data-mode]")
        class-token (bridge/query-selector document "[class~=\"primary\"]")
        lang-prefix (bridge/query-selector document "[lang|=\"en\"]")
        href-prefix (bridge/query-selector document "[href^=\"https://\"]")
        href-suffix (bridge/query-selector document "[href$=\".pdf\"]")
        href-substring (bridge/query-selector document "[href*=\"admin\"]")]
    (is (= "required" (get-in document [:nodes required :attrs :id])))
    (is (= "mode" (get-in document [:nodes edit :attrs :id])))
    (is (= ["mode" "other"]
           (mapv #(get-in document [:nodes % :attrs :id]) modes)))
    (is (= ["doc" "doc" "doc" "doc" "doc"]
           (mapv #(get-in document [:nodes % :attrs :id])
                 [class-token lang-prefix href-prefix href-suffix href-substring])))))

(deftest query-selector-supports-form-state-pseudo-classes
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><input id=\"disabled\" disabled><input id=\"checked\" type=\"checkbox\" checked><input id=\"hidden-required\" type=\"hidden\" required><input id=\"file-required\" type=\"file\" required><input id=\"required\" required><input id=\"focused\"><input id=\"plain\"><input id=\"readonly\" readonly><input id=\"readonly-required\" required readonly><input id=\"readonly-long\" value=\"abcde\" maxlength=\"4\" readonly><input id=\"readonly-number\" type=\"number\" readonly><input id=\"invalid\" invalid><select id=\"select-disabled-selected\" required><option value=\"locked\" selected disabled>Locked</option><option value=\"go\">Go</option></select><select id=\"select-optgroup-disabled\" required><optgroup disabled><option id=\"optgroup-locked\" value=\"locked\" selected>Locked</option></optgroup><option value=\"go\">Go</option></select><select id=\"select-multiple-empty\" multiple required><option value=\"one\">One</option><option value=\"two\">Two</option></select><fieldset disabled><legend><input id=\"legend-enabled\"></legend><input id=\"fieldset-disabled\" required></fieldset></main>"})
        document (:browser/document page)
        focused (bridge/query-selector document "#focused")
        document (assoc document :focus focused)
        disabled (bridge/query-selector document "input:disabled")
        enabled (bridge/query-selector document "input:enabled")
        checked (bridge/query-selector document "input:checked")
        required (bridge/query-selector document "input:required")
        optional (bridge/query-selector document "input:optional")
        readonly (bridge/query-selector document "input:read-only")
        readwrite (bridge/query-selector document "input:read-write")
        invalid (bridge/query-selector document "input:invalid")
        valid (bridge/query-selector document "input:valid")
        focus (bridge/query-selector document "input:focus")
        hidden-readwrite (bridge/query-selector document "#hidden-required:read-write")
        file-required (bridge/query-selector document "#file-required:required")
        file-readwrite (bridge/query-selector document "#file-required:read-write")
        readonly-number (bridge/query-selector document "#readonly-number:read-only")
        readonly-required (bridge/query-selector document "#readonly-required:required")
        readonly-required-invalid (bridge/query-selector document "#readonly-required:invalid")
        readonly-required-valid (bridge/query-selector document "#readonly-required:valid")
        readonly-long-invalid (bridge/query-selector document "#readonly-long:invalid")
        readonly-long-valid (bridge/query-selector document "#readonly-long:valid")
        disabled-selected-select-invalid (bridge/query-selector document "#select-disabled-selected:invalid")
        disabled-selected-select-valid (bridge/query-selector document "#select-disabled-selected:valid")
        optgroup-locked (bridge/query-selector document "#optgroup-locked:disabled")
        optgroup-disabled-select-invalid (bridge/query-selector document "#select-optgroup-disabled:invalid")
        optgroup-disabled-select-valid (bridge/query-selector document "#select-optgroup-disabled:valid")
        multiple-empty-select-invalid (bridge/query-selector document "#select-multiple-empty:invalid")
        multiple-empty-select-valid (bridge/query-selector document "#select-multiple-empty:valid")
        fieldset-disabled (bridge/query-selector document "#fieldset-disabled:disabled")
        legend-enabled (bridge/query-selector document "#legend-enabled:enabled")
        unsupported (bridge/query-selector document "input:hover")]
    (is (= "disabled" (get-in document [:nodes disabled :attrs :id])))
    (is (= "checked" (get-in document [:nodes enabled :attrs :id])))
    (is (= "checked" (get-in document [:nodes checked :attrs :id])))
    (is (= "required" (get-in document [:nodes required :attrs :id])))
    (is (= "checked" (get-in document [:nodes optional :attrs :id])))
    (is (= "readonly" (get-in document [:nodes readonly :attrs :id])))
    (is (= "checked" (get-in document [:nodes readwrite :attrs :id])))
    (is (= "required" (get-in document [:nodes invalid :attrs :id])))
    (is (= "checked" (get-in document [:nodes valid :attrs :id])))
    (is (= "focused" (get-in document [:nodes focus :attrs :id])))
    (is (nil? hidden-readwrite))
    (is (nil? file-required))
    (is (nil? file-readwrite))
    (is (= "readonly-number" (get-in document [:nodes readonly-number :attrs :id])))
    (is (= "readonly-required" (get-in document [:nodes readonly-required :attrs :id])))
    (is (nil? readonly-required-invalid))
    (is (nil? readonly-required-valid))
    (is (nil? readonly-long-invalid))
    (is (nil? readonly-long-valid))
    (is (= "select-disabled-selected" (get-in document [:nodes disabled-selected-select-invalid :attrs :id])))
    (is (nil? disabled-selected-select-valid))
    (is (= "optgroup-locked" (get-in document [:nodes optgroup-locked :attrs :id])))
    (is (= "select-optgroup-disabled" (get-in document [:nodes optgroup-disabled-select-invalid :attrs :id])))
    (is (nil? optgroup-disabled-select-valid))
    (is (= "select-multiple-empty" (get-in document [:nodes multiple-empty-select-invalid :attrs :id])))
    (is (nil? multiple-empty-select-valid))
    (is (= "fieldset-disabled" (get-in document [:nodes fieldset-disabled :attrs :id])))
    (is (= "legend-enabled" (get-in document [:nodes legend-enabled :attrs :id])))
    (is (nil? unsupported))))

(deftest query-selector-supports-selector-groups
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><button id=\"first\" class=\"primary\">First</button><input id=\"second\" required><button id=\"third\" class=\"primary\">Third</button><p id=\"comma\" data-label=\"a,b\">Comma</p><p id=\"space\" title=\"hello world\">Space</p></main>"})
        document (:browser/document page)
        first-match (bridge/query-selector document "input[missing], button.primary")
        grouped (bridge/query-selector-all document "button.primary, input:required, button.primary")
        comma (bridge/query-selector document "[data-label=\"a,b\"], input[missing]")
        space (bridge/query-selector document "main [title=\"hello world\"]")]
    (is (= "first" (get-in document [:nodes first-match :attrs :id])))
    (is (= ["first" "second" "third"]
           (mapv #(get-in document [:nodes % :attrs :id]) grouped)))
    (is (= "comma" (get-in document [:nodes comma :attrs :id])))
    (is (= "space" (get-in document [:nodes space :attrs :id])))))

(deftest document-snapshot-includes-parent-children-and-text
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\"><p class=\"note\">Hello</p></main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        p (bridge/query-selector document ".note")
        snapshot (bridge/document-snapshot (assoc document :focus p))]
    (is (:root snapshot))
    (is (= p (:focus snapshot)))
    (is (= [p] (get-in snapshot [:nodes root :children])))
    (is (= root (get-in snapshot [:nodes p :parent/id])))
    (is (= "Hello" (get-in snapshot [:nodes p :text-content])))))

(deftest mutation-bridge-creates-and-appends-nodes
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\"></main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        created (bridge/apply-mutation document {:dom/op :create-element
                                                 :tag "p"
                                                 :attrs {:class "note"}})
        text (bridge/apply-mutation (:document created) {:dom/op :create-text
                                                         :text "Hello"})
        appended-text (bridge/apply-mutation (:document text) {:dom/op :append-child
                                                               :parent/id (:node/id created)
                                                               :child/id (:node/id text)})
        appended-p (bridge/apply-mutation (:document appended-text) {:dom/op :append-child
                                                                     :parent/id root
                                                                     :child/id (:node/id created)})
        document (:document appended-p)]
    (is (= "Hello" (dom/text-content document)))
    (is (= "note" (get-in document [:nodes (:node/id created) :attrs :class])))))

(deftest mutation-bridge-appends-document-fragment-children
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\"><p id=\"after\">After</p></main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        after (bridge/query-selector document "#after")
        fragment (bridge/apply-mutation document {:dom/op :create-fragment})
        a (bridge/apply-mutation (:document fragment) {:dom/op :create-text
                                                       :text "A"})
        b (bridge/apply-mutation (:document a) {:dom/op :create-text
                                                :text "B"})
        with-a (bridge/apply-mutation (:document b) {:dom/op :append-child
                                                     :parent/id (:node/id fragment)
                                                     :child/id (:node/id a)})
        with-b (bridge/apply-mutation (:document with-a) {:dom/op :append-child
                                                         :parent/id (:node/id fragment)
                                                         :child/id (:node/id b)})
        inserted (bridge/apply-mutation (:document with-b) {:dom/op :insert-before
                                                            :parent/id root
                                                            :child/id (:node/id fragment)
                                                            :before/id after})
        document (:document inserted)]
    (is (= [(:node/id a) (:node/id b) after]
           (get-in document [:nodes root :children])))
    (is (= [] (get-in document [:nodes (:node/id fragment) :children])))
    (is (= "ABAfter" (dom/text-content document)))))

(deftest mutation-bridge-clones-node-subtrees
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\"><p id=\"note\" class=\"copy\">Hello</p></main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        note (bridge/query-selector document "#note")
        cloned (bridge/apply-mutation document {:dom/op :clone-node
                                                :source/id note
                                                :deep? true})
        appended (bridge/apply-mutation (:document cloned) {:dom/op :append-child
                                                            :parent/id root
                                                            :child/id (:node/id cloned)})
        document (:document appended)
        clone-node (get-in document [:nodes (:node/id cloned)])]
    (is (= :p (:tag clone-node)))
    (is (= "copy" (get-in clone-node [:attrs :class])))
    (is (= "HelloHello" (dom/text-content document)))
    (is (not= (first (get-in document [:nodes note :children]))
              (first (:children clone-node))))))

(deftest mutation-bridge-sets-inner-html-with-trusted-parser
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\"><p>Old</p></main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        result (bridge/apply-mutation document {:dom/op :set-inner-html
                                                :node/id root
                                                :html "<section id=\"new\"><p class=\"note\">Hello</p></section>"})
        document (:document result)
        section (bridge/query-selector document "#new")
        note (bridge/query-selector document ".note")]
    (is (= "Hello" (dom/text-content document)))
    (is (= [section] (get-in document [:nodes root :children])))
    (is (= "note" (get-in document [:nodes note :attrs :class])))))

(deftest mutation-bridge-updates-text-node-data
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\">Old</main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        text-id (first (get-in document [:nodes root :children]))
        result (bridge/apply-mutation document {:dom/op :set-text
                                                :node/id text-id
                                                :text "New"})
        document (:document result)]
    (is (= "New" (get-in document [:nodes text-id :text])))
    (is (= "New" (dom/text-content document)))))

(deftest mutation-bridge-splits-text-node
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\">Hello</main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        text-id (first (get-in document [:nodes root :children]))
        result (bridge/apply-mutation document {:dom/op :split-text
                                                :node/id text-id
                                                :offset 2})
        new-id (:node/id result)
        document (:document result)]
    (is (= "He" (get-in document [:nodes text-id :text])))
    (is (= "llo" (get-in document [:nodes new-id :text])))
    (is (= [text-id new-id] (get-in document [:nodes root :children])))
    (is (= "Hello" (dom/text-content document)))))

(deftest mutation-bridge-normalizes-adjacent-text-nodes
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\"></main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        a (bridge/apply-mutation document {:dom/op :create-text :text "A"})
        empty (bridge/apply-mutation (:document a) {:dom/op :create-text :text ""})
        b (bridge/apply-mutation (:document empty) {:dom/op :create-text :text "B"})
        with-a (bridge/apply-mutation (:document b) {:dom/op :append-child
                                                     :parent/id root
                                                     :child/id (:node/id a)})
        with-empty (bridge/apply-mutation (:document with-a) {:dom/op :append-child
                                                              :parent/id root
                                                              :child/id (:node/id empty)})
        with-b (bridge/apply-mutation (:document with-empty) {:dom/op :append-child
                                                              :parent/id root
                                                              :child/id (:node/id b)})
        result (bridge/apply-mutation (:document with-b) {:dom/op :normalize
                                                          :node/id root})
        document (:document result)]
    (is (= [(:node/id a)] (get-in document [:nodes root :children])))
    (is (= "AB" (get-in document [:nodes (:node/id a) :text])))
    (is (= "AB" (dom/text-content document)))))

(deftest mutation-bridge-sets-outer-html-with-trusted-parser
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\"><p id=\"old\">Old</p><p id=\"after\">After</p></main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        old (bridge/query-selector document "#old")
        after (bridge/query-selector document "#after")
        result (bridge/apply-mutation document {:dom/op :set-outer-html
                                                :node/id old
                                                :html "<section id=\"new\"><p class=\"note\">Hello</p></section><hr>"})
        document (:document result)
        section (bridge/query-selector document "#new")
        note (bridge/query-selector document ".note")
        hr (bridge/query-selector document "hr")]
    (is (= "HelloAfter" (dom/text-content document)))
    (is (= [section hr after] (get-in document [:nodes root :children])))
    (is (= "note" (get-in document [:nodes note :attrs :class])))
    (is (not (some #{old} (get-in document [:nodes root :children]))))
    (is (nil? (bridge/query-selector document "#old")))))

(deftest mutation-bridge-removes-attributes
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><p id=\"note\" data-temp=\"1\" class=\"note\">Hello</p></main>"})
        document (:browser/document page)
        note (bridge/query-selector document "#note")
        removed (bridge/apply-mutation document {:dom/op :remove-attribute
                                                 :node/id note
                                                 :attr "data-temp"})]
    (is (= "note" (get-in removed [:document :nodes note :attrs :class])))
    (is (not (contains? (get-in removed [:document :nodes note :attrs]) :data-temp)))))

(deftest mutation-bridge-set-and-remove-style-attribute-updates-inline-style-model
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><p id=\"note\">Hello</p></main>"})
        document (:browser/document page)
        note (bridge/query-selector document "#note")
        styled (bridge/apply-mutation document {:dom/op :set-attribute
                                                :node/id note
                                                :attr "style"
                                                :value "color: red; padding: 4px"})
        attrs (get-in styled [:document :nodes note :attrs])
        removed (bridge/apply-mutation (:document styled) {:dom/op :remove-attribute
                                                           :node/id note
                                                           :attr "style"})
        removed-attrs (get-in removed [:document :nodes note :attrs])]
    (is (= "color: red; padding: 4px" (:style attrs)))
    (is (= {:color "red" :padding 4} (:style-inline attrs)))
    (is (= "red" (:style/color attrs)))
    (is (= 4 (:style/padding attrs)))
    (is (not (contains? removed-attrs :style)))
    (is (not (contains? removed-attrs :style-inline)))
    (is (not (contains? removed-attrs :style/color)))
    (is (not (contains? removed-attrs :style/padding)))))

(deftest mutation-bridge-set-style-attribute-tracks-per-property-important
  ;; A script mutating element.style.cssText/setAttribute('style', ...)
  ;; goes through this same dom_bridge.cljc path, not htmldom.core/parse-
  ;; style (that only ever runs once, at initial HTML parse time) -- so it
  ;; needs its OWN !important handling to populate :style-inline/
  ;; :style-inline-important consistently with a page's initial inline
  ;; styles (see cssom.core/resolve-style-for's docstring). Before this
  ;; fix, the raw "!important" suffix leaked into :style-inline's value
  ;; (via css/parse-declarations, which strips it correctly, but the old
  ;; :style-inline-important attr didn't exist at all -- every mutated
  ;; inline declaration was silently treated as non-important).
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><p id=\"note\">Hello</p></main>"})
        document (:browser/document page)
        note (bridge/query-selector document "#note")
        styled (bridge/apply-mutation document {:dom/op :set-attribute
                                                :node/id note
                                                :attr "style"
                                                :value "color: red !important; padding: 4px"})
        attrs (get-in styled [:document :nodes note :attrs])
        removed (bridge/apply-mutation (:document styled) {:dom/op :remove-attribute
                                                           :node/id note
                                                           :attr "style"})
        removed-attrs (get-in removed [:document :nodes note :attrs])]
    (is (= {:color "red" :padding 4} (:style-inline attrs))
        "the real, uncorrupted values -- not \"red !important\"")
    (is (= #{:color} (:style-inline-important attrs)))
    (is (not (contains? removed-attrs :style-inline-important)))))

(deftest mutation-bridge-focus-and-blur-updates-document-focus
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><input id=\"field\"></main>"})
        document (:browser/document page)
        field (bridge/query-selector document "#field")
        focused (bridge/apply-mutation document {:dom/op :focus-node
                                                 :node/id field})
        blurred (bridge/apply-mutation (:document focused) {:dom/op :blur-node
                                                            :node/id field})]
    (is (= field (get-in focused [:document :focus])))
    (is (= [:dom/focus field] (last (get-in focused [:document :ops]))))
    (is (nil? (get-in blurred [:document :focus])))
    (is (= [:dom/blur field] (last (get-in blurred [:document :ops]))))))

(deftest mutation-bridge-inserts-and-removes-child-nodes
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main id=\"root\"></main>"})
        document (:browser/document page)
        root (bridge/query-selector document "#root")
        a (bridge/apply-mutation document {:dom/op :create-text :text "A"})
        b (bridge/apply-mutation (:document a) {:dom/op :create-text :text "B"})
        c (bridge/apply-mutation (:document b) {:dom/op :create-text :text "C"})
        with-a (bridge/apply-mutation (:document c) {:dom/op :append-child
                                                     :parent/id root
                                                     :child/id (:node/id a)})
        with-c (bridge/apply-mutation (:document with-a) {:dom/op :append-child
                                                          :parent/id root
                                                          :child/id (:node/id c)})
        inserted-b (bridge/apply-mutation (:document with-c) {:dom/op :insert-before
                                                              :parent/id root
                                                              :child/id (:node/id b)
                                                              :before/id (:node/id c)})
        removed-a (bridge/apply-mutation (:document inserted-b) {:dom/op :remove-child
                                                                 :parent/id root
                                                                 :child/id (:node/id a)})
        document (:document removed-a)]
    (is (= "BC" (dom/text-content document)))
    (is (= [(:node/id b) (:node/id c)]
           (get-in document [:nodes root :children])))))

(deftest event-bridge-registers-and-dispatches-listeners
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><button id=\"go\">Go</button></main>"})
        document (:browser/document page)
        button (bridge/query-selector document "#go")
        listened (bridge/handle-request document {:capability :event/listen
                                                  :node/id button
                                                  :event/type "click"
                                                  :handler/id "handler-1"})
        dispatched (bridge/handle-request (:document listened)
                                          {:capability :event/dispatch
                                           :node/id button
                                           :event {:event/type "click"
                                                   :target/id button}})]
    (is (= "handler-1" (get-in listened [:document :listeners button :click])))
    (is (= true (:event/dispatched? dispatched)))
    (is (= [:dom/dispatch-event "handler-1" {:event/type "click" :target/id button}]
           (last (get-in dispatched [:document :ops]))))))

(deftest event-bridge-removes-listeners-before-dispatch
  (let [page (browser/load-html {:url "kotoba://dom"
                                 :html "<main><button id=\"go\">Go</button></main>"})
        document (:browser/document page)
        button (bridge/query-selector document "#go")
        listened (bridge/handle-request document {:capability :event/listen
                                                  :node/id button
                                                  :event/type "click"
                                                  :handler/id "handler-1"})
        removed (bridge/handle-request (:document listened)
                                       {:capability :event/remove
                                        :node/id button
                                        :event/type "click"
                                        :handler/id "handler-1"})
        before-count (count (get-in removed [:document :ops]))
        dispatched (bridge/handle-request (:document removed)
                                          {:capability :event/dispatch
                                           :node/id button
                                           :event {:event/type "click"
                                                   :target/id button}})]
    (is (nil? (get-in removed [:document :listeners button :click])))
    (is (= "handler-1" (:handler/id removed)))
    (is (= [:dom/remove-event-listener button :click "handler-1"]
           (last (get-in removed [:document :ops]))))
    (is (= false (:event/dispatched? dispatched)))
    (is (= before-count (count (get-in dispatched [:document :ops]))))))
