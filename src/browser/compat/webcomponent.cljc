(ns browser.compat.webcomponent
  "Minimal Custom Elements registry model for compat JS adapters."
  (:require [browser.compat :as compat]
            [clojure.string :as str]))

(defn empty-registry
  []
  {:custom-elements/definitions {}
   :custom-elements/upgraded []})

(def reserved-names
  "Real HTML Custom Elements names explicitly reserved by the spec (pre-
   existing SVG/MathML element names that happen to contain a hyphen,
   carved out to avoid ambiguity) -- a customElements.define() call for
   any of these must be rejected even though each otherwise satisfies
   the hyphen requirement below."
  #{"annotation-xml" "color-profile" "font-face" "font-face-src"
    "font-face-uri" "font-face-format" "font-face-name" "missing-glyph"})

(defn valid-name?
  [name]
  (and (string? name)
       (str/includes? name "-")
       ;; Real spec (PotentialCustomElementName): the name must START with
       ;; a lowercase ASCII letter and contain NO uppercase ASCII letters
       ;; anywhere -- the PCENChar production explicitly excludes A-Z.
       ;; Deliberately ASCII-only: PCENChar's full grammar also permits a
       ;; long tail of non-ASCII Unicode ranges (middle dot, CJK blocks,
       ;; etc.) that real custom element names almost never use, left
       ;; unimplemented rather than risk a subtly wrong Unicode range
       ;; check for near-zero real-world value.
       (re-matches #"[a-z][^A-Z]*" name)
       ;; Real spec: must not start with an ASCII case-insensitive match
       ;; for "xml" (reserved by the XML specification), not just a
       ;; lowercase-only "xml" prefix.
       (not (str/starts-with? (str/lower-case name) "xml"))
       (not (contains? reserved-names name))))

(defn define
  "Real spec: a second `customElements.define()` call either reusing an
   already-registered NAME or an already-registered CONSTRUCTOR (a class
   used for one name can never be reused for another) must throw
   NotSupportedError, not silently redefine/overwrite. Mirrors the live JS
   registrar's own `globalThis.customElements.define` (quickjs_wasm.cljc)
   -- keep both in sync if either's rules change."
  [registry name definition]
  (let [constructor-id (:constructor/id definition)
        definitions (:custom-elements/definitions registry)]
    (cond
      (not (valid-name? name))
      (assoc registry :custom-elements/error {:reason :invalid-custom-element-name
                                               :name name})

      (contains? definitions name)
      (assoc registry :custom-elements/error {:reason :already-defined
                                               :name name})

      (and (some? constructor-id)
           (some #(= constructor-id (:constructor/id %)) (vals definitions)))
      (assoc registry :custom-elements/error {:reason :constructor-already-used
                                               :name name})

      :else
      (assoc-in registry [:custom-elements/definitions name]
                (merge {:custom-element/name name
                        :observed-attributes []}
                       definition)))))

(defn definition
  [registry name]
  (get-in registry [:custom-elements/definitions name]))

(defn upgrade-request
  [adapter registry node]
  (when-let [definition (definition registry (:tag node))]
    (compat/request adapter :js/call
                    {:js/call :custom-element/connected-callback
                     :constructor/id (:constructor/id definition)
                     :node/id (:node/id node)
                     :tag (:tag node)})))

(defn attribute-changed-request
  [adapter registry node attr old-value new-value]
  (when-let [definition (definition registry (:tag node))]
    (when (contains? (set (:observed-attributes definition)) attr)
      (compat/request adapter :js/call
                      {:js/call :custom-element/attribute-changed-callback
                       :constructor/id (:constructor/id definition)
                       :node/id (:node/id node)
                       :tag (:tag node)
                       :attr attr
                       :old-value old-value
                       :new-value new-value}))))

(defn upgrade-tree
  [adapter registry nodes]
  (vec (keep #(upgrade-request adapter registry %) nodes)))
