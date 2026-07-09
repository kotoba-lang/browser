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
