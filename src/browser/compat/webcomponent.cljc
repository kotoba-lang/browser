(ns browser.compat.webcomponent
  "Minimal Custom Elements registry model for compat JS adapters."
  (:require [browser.compat :as compat]
            [clojure.string :as str]))

(defn empty-registry
  []
  {:custom-elements/definitions {}
   :custom-elements/upgraded []})

(defn valid-name?
  [name]
  (and (string? name)
       (str/includes? name "-")
       (not (str/starts-with? name "xml"))))

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
