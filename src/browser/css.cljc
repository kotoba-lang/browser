(ns browser.css
  "Small CSS selector/cascade subset for kotoba browser documents."
  (:require [clojure.string :as str]
            [kotoba.wasm.dom :as dom]))

(defn- parse-style-value
  [v]
  (let [v (str/trim (str v))]
    (cond
      (re-matches #"-?\d+" v) #?(:clj (Long/parseLong v) :cljs (js/parseInt v 10))
      (re-matches #"-?\d+px" v) #?(:clj (Long/parseLong (subs v 0 (- (count v) 2)))
                                   :cljs (js/parseInt v 10))
      :else v)))

(defn parse-declarations
  [text]
  (->> (str/split (or text "") #";")
       (keep (fn [decl]
               (let [[k v] (map str/trim (str/split decl #":" 2))]
                 (when (and (seq k) (seq v))
                   [(keyword k) (parse-style-value (str/replace v #"(?i)\s*!important\s*$" ""))]))))
       (into {})))

(defn- parse-declarations-with-importance
  [text]
  (->> (str/split (or text "") #";")
       (keep (fn [decl]
               (let [[k v] (map str/trim (str/split decl #":" 2))]
                 (when (and (seq k) (seq v))
                   (let [important? (boolean (re-find #"(?i)!important\s*$" v))
                         value (str/replace v #"(?i)\s*!important\s*$" "")]
                     [(keyword k) {:value (parse-style-value value)
                                   :important? important?}])))))
       (into {})))

(defn- parse-attribute-selector
  [text]
  (when-let [[_ attr operator double-quoted single-quoted unquoted]
             (re-matches #"\[\s*([A-Za-z_][-A-Za-z0-9_]*)\s*(?:(~=|\|=|\^=|\$=|\*=|=)\s*(?:\"([^\"]*)\"|'([^']*)'|([^\]\s]+)))?\s*\]" text)]
    {:attr/name (keyword attr)
     :attr/operator operator
     :attr/value (or double-quoted single-quoted unquoted)}))

(def attribute-selector-pattern
  #"\[\s*[A-Za-z_][-A-Za-z0-9_]*\s*(?:(?:~=|\|=|\^=|\$=|\*=|=)\s*(?:\"[^\"]*\"|'[^']*'|[^\]\s]+))?\s*\]")

(def pseudo-class-pattern
  #":([A-Za-z_][-A-Za-z0-9_]*)")

(defn- append-token
  [tokens token]
  (cond-> tokens
    (not (str/blank? token)) (conj token)))

(defn selector-tokens
  [selector]
  (let [s (str selector)
        n (count s)]
    (loop [idx 0
           start 0
           bracket-depth 0
           quote-char nil
           tokens []]
      (if (= idx n)
        (append-token tokens (str/trim (subs s start idx)))
        (let [ch (nth s idx)
              escaped? (and (pos? idx) (= \\ (nth s (dec idx))))]
          (cond
            (and quote-char (= ch quote-char) (not escaped?))
            (recur (inc idx) start bracket-depth nil tokens)

            quote-char
            (recur (inc idx) start bracket-depth quote-char tokens)

            (or (= ch \") (= ch \'))
            (recur (inc idx) start bracket-depth ch tokens)

            (= ch \[)
            (recur (inc idx) start (inc bracket-depth) quote-char tokens)

            (= ch \])
            (recur (inc idx) start (max 0 (dec bracket-depth)) quote-char tokens)

            (and (= ch \>) (zero? bracket-depth))
            (recur (inc idx)
                   (inc idx)
                   bracket-depth
                   quote-char
                   (-> tokens
                       (append-token (str/trim (subs s start idx)))
                       (conj ">")))

            (and (Character/isWhitespace ch) (zero? bracket-depth))
            (recur (inc idx)
                   (inc idx)
                   bracket-depth
                   quote-char
                   (append-token tokens (str/trim (subs s start idx))))

            :else
            (recur (inc idx) start bracket-depth quote-char tokens)))))))

(defn parse-simple-selector
  [selector]
  (let [s (str/trim selector)
        selector-without-attrs (str/replace s attribute-selector-pattern "")
        selector-without-pseudos (str/replace selector-without-attrs pseudo-class-pattern "")
        tag (second (re-find #"^([A-Za-z][A-Za-z0-9_-]*)" selector-without-attrs))
        id (second (re-find #"#([A-Za-z_][-A-Za-z0-9_]*)" selector-without-pseudos))
        classes (mapv second (re-seq #"\.([A-Za-z_][-A-Za-z0-9_]*)" selector-without-pseudos))
        attrs (mapv parse-attribute-selector
                    (re-seq attribute-selector-pattern s))
        pseudos (mapv (comp keyword str/lower-case second)
                      (re-seq pseudo-class-pattern selector-without-attrs))]
    {:selector/raw s
     :selector/tag (when (seq tag) (keyword (str/lower-case tag)))
     :selector/id id
     :selector/classes classes
     :selector/attrs (filterv some? attrs)
     :selector/pseudos pseudos}))

(defn parse-selector
  [selector]
  (let [tokens (selector-tokens selector)
        [_ parts] (reduce (fn [[combinator parts] token]
                            (case token
                              ">" [:child parts]
                              [nil (conj parts
                                         (assoc (parse-simple-selector token)
                                                :selector/combinator
                                                (if (seq parts)
                                                  (or combinator :descendant)
                                                  nil)))]))
                          [nil []]
                          (remove str/blank? tokens))]
    {:selector/raw (str/trim (or selector ""))
     :selector/parts parts}))

(defn split-selector-list
  [selector-list]
  (let [s (str selector-list)
        n (count s)]
    (loop [idx 0
           start 0
           bracket-depth 0
           quote-char nil
           selectors []]
      (if (= idx n)
        (->> (conj selectors (subs s start idx))
             (map str/trim)
             (remove str/blank?)
             vec)
        (let [ch (nth s idx)
              escaped? (and (pos? idx) (= \\ (nth s (dec idx))))]
          (cond
            (and quote-char (= ch quote-char) (not escaped?))
            (recur (inc idx) start bracket-depth nil selectors)

            quote-char
            (recur (inc idx) start bracket-depth quote-char selectors)

            (or (= ch \") (= ch \'))
            (recur (inc idx) start bracket-depth ch selectors)

            (= ch \[)
            (recur (inc idx) start (inc bracket-depth) quote-char selectors)

            (= ch \])
            (recur (inc idx) start (max 0 (dec bracket-depth)) quote-char selectors)

            (and (= ch \,) (zero? bracket-depth))
            (recur (inc idx) (inc idx) bracket-depth quote-char (conj selectors (subs s start idx)))

            :else
            (recur (inc idx) start bracket-depth quote-char selectors)))))))

(defn specificity
  [selector]
  (let [parts (or (:selector/parts selector) [selector])]
    [(reduce + (map #(if (:selector/id %) 1 0) parts))
     (reduce + (map #(+ (count (:selector/classes %))
                        (count (:selector/attrs %))
                        (count (:selector/pseudos %)))
                    parts))
     (reduce + (map #(if (:selector/tag %) 1 0) parts))]))

(defn parse-rules
  [css]
  (->> (re-seq #"(?s)([^{}]+)\{([^{}]+)\}" (or css ""))
       (map-indexed
        (fn [idx [_ selector-text body]]
          {:rule/order idx
           :rule/selectors (mapv parse-selector (split-selector-list selector-text))
           :rule/declarations (parse-declarations body)
           :rule/declaration-meta (parse-declarations-with-importance body)}))
       vec))

(defn- classes
  [node]
  (set (remove str/blank? (str/split (str (get-in node [:attrs :class] "")) #"\s+"))))

(defn- truthy-attr? [v]
  (or (= true v)
      (= "true" v)
      (= "" v)
      (and (string? v)
           (not (str/blank? v))
           (not= "false" (str/lower-case v)))))

(defn- parse-int
  [v]
  (when (some? v)
    (let [s (str v)]
      (when (re-matches #"-?\d+" s)
        #?(:clj (Long/parseLong s)
           :cljs (js/parseInt s 10))))))

(def form-control-tags #{:button :input :select :textarea})

(def disabled-capable-tags #{:button :fieldset :input :optgroup :option :select :textarea})

(def editable-form-control-tags #{:input :textarea})

(defn- input-type
  [node]
  (str/lower-case (str (or (get-in node [:attrs :type]) "text"))))

(defn- hidden-input-control?
  [node]
  (and (= :input (:tag node))
       (= "hidden" (input-type node))))

(defn- file-input-control?
  [node]
  (and (= :input (:tag node))
       (= "file" (input-type node))))

(defn- editable-form-control?
  [node]
  (and (contains? editable-form-control-tags (:tag node))
       (not (hidden-input-control? node))
       (not (file-input-control? node))))

(defn- form-control?
  [node]
  (contains? form-control-tags (:tag node)))

(defn- disabled-capable-control?
  [node]
  (contains? disabled-capable-tags (:tag node)))

(defn- descendant-node-ids
  ([document node-id]
   (descendant-node-ids document node-id #{}))
  ([document node-id visited]
   (when-not (contains? visited node-id)
     (let [visited (conj visited node-id)]
       (mapcat (fn [child-id]
                 (cons child-id (descendant-node-ids document child-id visited)))
               (get-in document [:nodes node-id :children]))))))

(defn- parent-node-id
  [document child-id]
  (some (fn [[node-id node]]
          (when (some #{child-id} (:children node))
            node-id))
        (:nodes document)))

(defn- descendant-or-self?
  [document ancestor-id node-id]
  (or (= ancestor-id node-id)
      (some (fn [child-id]
              (descendant-or-self? document child-id node-id))
            (get-in document [:nodes ancestor-id :children]))))

(defn- first-legend-child-id
  [document fieldset-id]
  (first (filter #(= :legend (get-in document [:nodes % :tag]))
                 (get-in document [:nodes fieldset-id :children]))))

(defn- disabled-by-fieldset?
  [document node]
  (loop [parent-id (parent-node-id document (:node/id node))]
    (when parent-id
      (let [parent (get-in document [:nodes parent-id])]
        (if (and (= :fieldset (:tag parent))
                 (truthy-attr? (get-in parent [:attrs :disabled]))
                 (not (when-let [legend-id (first-legend-child-id document parent-id)]
                        (descendant-or-self? document legend-id (:node/id node)))))
          true
          (recur (parent-node-id document parent-id)))))))

(defn- disabled-by-optgroup?
  [document node]
  (when (= :option (:tag node))
    (loop [parent-id (parent-node-id document (:node/id node))]
      (when parent-id
        (let [parent (get-in document [:nodes parent-id])]
          (if (= :optgroup (:tag parent))
            (truthy-attr? (get-in parent [:attrs :disabled]))
            (recur (parent-node-id document parent-id))))))))

(defn- disabled-control?
  [document node]
  (and (disabled-capable-control? node)
       (or (truthy-attr? (get-in node [:attrs :disabled]))
           (disabled-by-fieldset? document node)
           (disabled-by-optgroup? document node))))

(defn- text-content
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (case (:node/type node)
      :text (:text node)
      :element (str/join "" (map #(text-content document %) (:children node)))
      "")))

(defn- option-value
  [document option-id]
  (let [attrs (get-in document [:nodes option-id :attrs])]
    (str (if (contains? attrs :value)
           (:value attrs)
           (text-content document option-id)))))

(defn- selected-option-id
  [document select-id]
  (let [options (->> (descendant-node-ids document select-id)
                     (filter #(= :option (get-in document [:nodes % :tag]))))
        selected-options (filter #(truthy-attr? (get-in document [:nodes % :attrs :selected]))
                                 options)
        enabled-option? #(not (disabled-control? document (get-in document [:nodes %])))
        multiple? (truthy-attr? (get-in document [:nodes select-id :attrs :multiple]))]
    (or (first (filter enabled-option? selected-options))
        (when (and (empty? selected-options) (not multiple?))
          (first (filter enabled-option? options))))))

(defn- select-value
  [document node]
  (when-let [option-id (and document (selected-option-id document (:node/id node)))]
    (option-value document option-id)))

(defn- radio-group-node-ids
  [document node]
  (let [name (get-in node [:attrs :name])
        form (get-in node [:attrs :form])
        root (:root document)]
    (->> (descendant-node-ids document root)
         (filter (fn [node-id]
                   (let [candidate (get-in document [:nodes node-id])]
                     (and (= :input (:tag candidate))
                          (= "radio" (str/lower-case (str (or (get-in candidate [:attrs :type]) "text"))))
                          (= (str name) (str (get-in candidate [:attrs :name])))
                          (= (str form) (str (get-in candidate [:attrs :form])))))))
         vec)))

(defn- radio-required-satisfied?
  [document node]
  (some #(truthy-attr? (get-in document [:nodes % :attrs :checked]))
        (radio-group-node-ids document node)))

(defn- control-value
  [document node]
  (let [type (str/lower-case (str (or (get-in node [:attrs :type]) "text")))]
    (str (or (get-in node [:attrs :text/value])
             (if (= :select (:tag node))
               (select-value document node)
               (get-in node [:attrs :value]))
             ""))))

(defn- validation-barred-control?
  [node]
  (or (hidden-input-control? node)
      (file-input-control? node)))

(defn- constraint-validation-barred-control?
  [node]
  (or (validation-barred-control? node)
      (and (editable-form-control? node)
           (truthy-attr? (get-in node [:attrs :readonly])))))

(defn- constraint-invalid?
  [document node]
  (let [attrs (:attrs node)
        type (str/lower-case (str (or (:type attrs) "text")))
        value (control-value document node)
        length (count value)
        minlength (parse-int (:minlength attrs))
        maxlength (parse-int (:maxlength attrs))]
    (and (not (constraint-validation-barred-control? node))
         (or (truthy-attr? (:invalid attrs))
             (and (truthy-attr? (:required attrs))
                  (cond
                    (and (= :input (:tag node)) (= "checkbox" type)) (not (truthy-attr? (:checked attrs)))
                    (and (= :input (:tag node)) (= "radio" type)) (not (radio-required-satisfied? document node))
                    :else (str/blank? value)))
             (and minlength
                  (pos? length)
                  (< length minlength))
             (and maxlength
                  (> length maxlength))))))

(defn- constraint-valid?
  [document node]
  (and (form-control? node)
       (not (disabled-control? document node))
       (not (constraint-validation-barred-control? node))
       (not (constraint-invalid? document node))))

(defn- matches-pseudo?
  [document node selector-pseudo]
  (case selector-pseudo
    :disabled (disabled-control? document node)
    :enabled (and (form-control? node)
                  (not (disabled-control? document node)))
    :checked (truthy-attr? (get-in node [:attrs :checked]))
    :required (and (form-control? node)
                   (not (disabled-control? document node))
                   (not (validation-barred-control? node))
                   (truthy-attr? (get-in node [:attrs :required])))
    :optional (and (form-control? node)
                   (not (disabled-control? document node))
                   (not (validation-barred-control? node))
                   (not (truthy-attr? (get-in node [:attrs :required]))))
    :read-only (and (not (validation-barred-control? node))
                    (or (not (editable-form-control? node))
                        (truthy-attr? (get-in node [:attrs :readonly]))))
    :read-write (and (editable-form-control? node)
                     (not (truthy-attr? (get-in node [:attrs :readonly])))
                     (not (disabled-control? document node)))
    :invalid (and (form-control? node)
                  (not (disabled-control? document node))
                  (not (constraint-validation-barred-control? node))
                  (constraint-invalid? document node))
    :valid (constraint-valid? document node)
    :focus (and document (= (:node/id node) (:focus document)))
    false))

(defn- matches-simple?
  ([node selector]
   (matches-simple? nil node selector))
  ([document node selector]
   (and (= :element (:node/type node))
        (or (nil? (:selector/tag selector))
            (= (:selector/tag selector) (:tag node)))
        (or (nil? (:selector/id selector))
            (= (:selector/id selector) (get-in node [:attrs :id])))
        (every? (classes node) (:selector/classes selector))
        (every? (fn [{:attr/keys [name operator value]}]
                  (let [actual (get-in node [:attrs name])]
                    (and (some? actual)
                         (case operator
                           nil true
                           "=" (= (str actual) value)
                           "~=" (contains? (set (remove str/blank?
                                                        (str/split (str actual) #"\s+")))
                                          value)
                           "^=" (str/starts-with? (str actual) value)
                           "$=" (str/ends-with? (str actual) value)
                           "*=" (str/includes? (str actual) value)
                           "|=" (or (= (str actual) value)
                                   (str/starts-with? (str actual) (str value "-")))
                           false))))
                (:selector/attrs selector))
        (every? #(matches-pseudo? document node %) (:selector/pseudos selector)))))

(defn- parent-index
  [document]
  (reduce-kv
   (fn [parents parent-id node]
     (reduce (fn [parents child-id]
               (assoc parents child-id parent-id))
             parents
             (:children node)))
   {}
   (:nodes document)))

(defn- matches-parts?
  [document parents node-id parts]
  (let [parts (vec parts)]
    (letfn [(match-at [node-id idx]
              (let [simple (nth parts idx)
                    node (get-in document [:nodes node-id])]
                (and (matches-simple? document node simple)
                     (if (zero? idx)
                       true
                       (case (:selector/combinator simple)
                         :child
                         (when-let [parent-id (get parents node-id)]
                           (match-at parent-id (dec idx)))

                         :descendant
                         (loop [ancestor-id (get parents node-id)]
                           (when ancestor-id
                             (if (match-at ancestor-id (dec idx))
                               true
                               (recur (get parents ancestor-id)))))

                         false)))))]
      (if (seq parts)
        (match-at node-id (dec (count parts)))
        false))))

(defn matches?
  ([node selector]
   (matches-simple? node (if (:selector/parts selector)
                           (last (:selector/parts selector))
                           selector)))
  ([document node selector]
   (let [selector (if (:selector/parts selector)
                    selector
                    {:selector/parts [selector]})
         node-id (:node/id node)]
     (matches-parts? document (parent-index document) node-id (:selector/parts selector)))))

(defn- inline-style
  [node]
  (or (get-in node [:attrs :style-inline])
      {}))

(defn- clear-style-attrs
  [document node-id]
  (update-in document [:nodes node-id :attrs]
             (fn [attrs]
               (into {}
                     (remove (fn [[k _]]
                               (= "style" (namespace k))))
                     attrs))))

(defn computed-style
  ([rules node]
   (computed-style nil rules node))
  ([document rules node]
   (let [declarations (for [{:rule/keys [selectors declarations declaration-meta order]} rules
                            selector selectors
                            :when (if document
                                    (matches? document node selector)
                                    (matches? node selector))
                            [property value] declarations]
                        (let [{:keys [important?]} (get declaration-meta property)]
                          {:property property
                           :value value
                           :important? (boolean important?)
                           :specificity (specificity selector)
                           :inline? false
                           :order order}))
         inline-declarations (map-indexed (fn [idx [property value]]
                                            {:property property
                                             :value value
                                             :important? false
                                             :specificity [1 0 0]
                                             :inline? true
                                             :order idx})
                                          (inline-style node))
         rule-style (reduce (fn [m {:keys [property value]}]
                              (assoc m property value))
                            {}
                            (sort-by (juxt :important? :inline? :specificity :order)
                                     (concat declarations inline-declarations)))]
     rule-style)))

(defn apply-cascade
  [document rules]
  (reduce-kv
   (fn [document node-id node]
     (if (= :element (:node/type node))
       (let [style (computed-style document rules node)]
         (reduce-kv (fn [d k v]
                      (dom/set-attribute d node-id (keyword "style" (name k)) v))
                    (clear-style-attrs document node-id)
                    style))
       document))
   document
   (:nodes document)))
