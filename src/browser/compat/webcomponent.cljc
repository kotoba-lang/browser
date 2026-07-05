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
       ;; Real spec: must not start with an ASCII case-insensitive match
       ;; for "xml" (reserved by the XML specification), not just a
       ;; lowercase-only "xml" prefix.
       (not (str/starts-with? (str/lower-case name) "xml"))
       (not (contains? reserved-names name))))

(defn define
  [registry name definition]
  (if (valid-name? name)
    (assoc-in registry [:custom-elements/definitions name]
              (merge {:custom-element/name name
                      :observed-attributes []}
                     definition))
    (assoc registry :custom-elements/error {:reason :invalid-custom-element-name
                                            :name name})))

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
