(ns browser.webcomponent-test
  (:require [browser.compat.webcomponent :as webcomponent]
            [clojure.test :refer [deftest is]]))

(deftest valid-name-requires-a-hyphen
  (is (webcomponent/valid-name? "my-widget"))
  (is (not (webcomponent/valid-name? "widget"))))

(deftest valid-name-rejects-names-that-do-not-start-with-a-lowercase-ascii-letter
  ;; Real Custom Elements spec (PotentialCustomElementName): the name must
  ;; start with a lowercase ASCII letter. Previously only the hyphen
  ;; requirement was checked, so a digit-led or uppercase-led name
  ;; incorrectly passed.
  (is (not (webcomponent/valid-name? "1-foo")))
  (is (not (webcomponent/valid-name? "-foo")))
  (is (not (webcomponent/valid-name? "Foo-bar"))))

(deftest valid-name-rejects-uppercase-ascii-letters-anywhere-in-the-name
  ;; Real Custom Elements spec: PCENChar (the character class allowed
  ;; after the first letter) explicitly excludes A-Z, so an uppercase
  ;; letter anywhere in the name is invalid, not just at the start.
  ;; Previously unchecked entirely -- "foo-Bar" incorrectly passed.
  (is (not (webcomponent/valid-name? "foo-Bar")))
  (is (not (webcomponent/valid-name? "foo-bAr")))
  (is (webcomponent/valid-name? "foo-bar")))

(deftest valid-name-rejects-xml-prefix-case-insensitively
  ;; Real Custom Elements spec: a name must not start with an ASCII
  ;; case-insensitive match for "xml" (reserved by the XML
  ;; specification) -- not just a lowercase-only "xml" prefix.
  (is (not (webcomponent/valid-name? "xml-widget")))
  (is (not (webcomponent/valid-name? "XML-Widget")))
  (is (not (webcomponent/valid-name? "Xml-Foo"))))

(deftest valid-name-rejects-explicitly-reserved-names
  ;; Real Custom Elements spec: these pre-existing SVG/MathML element
  ;; names contain a hyphen but are explicitly carved out as invalid,
  ;; regardless of the hyphen/xml-prefix checks above.
  (is (not (webcomponent/valid-name? "annotation-xml")))
  (is (not (webcomponent/valid-name? "color-profile")))
  (is (not (webcomponent/valid-name? "font-face")))
  (is (not (webcomponent/valid-name? "font-face-src")))
  (is (not (webcomponent/valid-name? "font-face-uri")))
  (is (not (webcomponent/valid-name? "font-face-format")))
  (is (not (webcomponent/valid-name? "font-face-name")))
  (is (not (webcomponent/valid-name? "missing-glyph"))))

(deftest define-rejects-invalid-names-with-an-error
  (let [registry (-> (webcomponent/empty-registry)
                     (webcomponent/define "font-face" {:constructor/id "c1"}))]
    (is (nil? (webcomponent/definition registry "font-face")))
    (is (= {:reason :invalid-custom-element-name :name "font-face"}
           (:custom-elements/error registry)))))

(deftest define-accepts-a-valid-name
  (let [registry (-> (webcomponent/empty-registry)
                     (webcomponent/define "my-widget" {:constructor/id "c1"
                                                       :observed-attributes ["open"]}))]
    (is (= "my-widget" (:custom-element/name (webcomponent/definition registry "my-widget"))))
    (is (nil? (:custom-elements/error registry)))))

(deftest define-rejects-a-duplicate-name-and-preserves-the-first-definition
  ;; Real spec: a second define() call reusing an already-registered NAME
  ;; must throw NotSupportedError, not silently overwrite the first
  ;; definition -- previously this function unconditionally assoc-in'd
  ;; over any existing entry with no check at all, and its own live JS
  ;; sibling (globalThis.customElements.define, quickjs_wasm.cljc) already
  ;; correctly rejects this exact case. Confirmed via direct REPL
  ;; evaluation before touching source.
  (let [registry (-> (webcomponent/empty-registry)
                     (webcomponent/define "my-widget" {:constructor/id "c1"})
                     (webcomponent/define "my-widget" {:constructor/id "c2"}))]
    (is (= "c1" (:constructor/id (webcomponent/definition registry "my-widget")))
        "the first definition must survive unchanged")
    (is (= {:reason :already-defined :name "my-widget"}
           (:custom-elements/error registry)))))

(deftest define-rejects-a-constructor-already-used-for-a-different-name
  ;; Real spec: a class/constructor already used to define ONE name can
  ;; never be reused to define ANOTHER name -- mirrors the JS registrar's
  ;; own "this constructor has already been used to define ..." check.
  (let [registry (-> (webcomponent/empty-registry)
                     (webcomponent/define "my-widget" {:constructor/id "c1"})
                     (webcomponent/define "my-other-widget" {:constructor/id "c1"}))]
    (is (nil? (webcomponent/definition registry "my-other-widget"))
        "the second, constructor-colliding name must never be registered")
    (is (= {:reason :constructor-already-used :name "my-other-widget"}
           (:custom-elements/error registry)))))

(deftest define-allows-two-distinct-names-with-distinct-constructors
  ;; Regression guard: the new duplicate checks must not falsely reject
  ;; two genuinely independent, valid registrations.
  (let [registry (-> (webcomponent/empty-registry)
                     (webcomponent/define "my-widget" {:constructor/id "c1"})
                     (webcomponent/define "my-other-widget" {:constructor/id "c2"}))]
    (is (some? (webcomponent/definition registry "my-widget")))
    (is (some? (webcomponent/definition registry "my-other-widget")))
    (is (nil? (:custom-elements/error registry)))))

(deftest define-allows-two-names-with-no-constructor-id-at-all
  ;; Regression guard: the constructor-collision check must only fire when
  ;; BOTH sides genuinely provide a :constructor/id -- two definitions
  ;; that never supply one must not falsely collide against each other via
  ;; a nil-equals-nil comparison.
  (let [registry (-> (webcomponent/empty-registry)
                     (webcomponent/define "my-widget" {})
                     (webcomponent/define "my-other-widget" {}))]
    (is (some? (webcomponent/definition registry "my-widget")))
    (is (some? (webcomponent/definition registry "my-other-widget")))
    (is (nil? (:custom-elements/error registry)))))
