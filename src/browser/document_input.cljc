(ns browser.document-input
  "Document-scoped input reducer for form controls.

   This connects canonical browser.input events to kotoba.wasm.dom nodes without
   relying on an ambient browser DOM."
  (:require [browser.dom-bridge :as dom-bridge]
            [browser.input :as input]
            [browser.text-edit :as text-edit]
            [clojure.string :as str]
            [kotoba.wasm.dom :as dom]))

(def editable-tags #{:input :textarea})

(def disabled-control-tags #{:button :input :select :textarea})

(def text-input-types #{"" "text" "search" "url" "tel" "email" "password"})

(def value-input-types
  (into text-input-types #{"number" "range" "color" "date" "datetime-local"
                           "month" "week" "time"}))

(def scrollable-overflows #{"auto" "scroll"})

(def activation-keys #{" " "Space" "Spacebar" "Enter"})

(defn target-node-id
  [document event]
  (or (:node/id event)
      (when-let [selector (:node/selector event)]
        (dom-bridge/query-selector document selector))
      (:focus document)))

(defn- truthy-attr? [v]
  (or (= true v)
      (= "true" v)
      (= "" v)
      (and (string? v)
           (not (str/blank? v))
           (not= "false" (str/lower-case v)))))

(declare disabled-control?)
(declare ancestor-form-id)

(defn- parent-node-id
  [document child-id]
  (some (fn [[node-id node]]
          (when (some #{child-id} (:children node))
            node-id))
        (:nodes document)))

(defn- summary-control?
  [document node-id]
  (= :summary (get-in document [:nodes node-id :tag])))

(defn- details-parent-id
  "The `:details` node-id `node-id` (a clicked/activated `:summary`
   element) toggles -- but ONLY when `node-id` is itself a real, direct
   `:summary` child of a `:details` element (real HTML5: any OTHER
   `:summary` descendant deeper in a `<details>`'s subtree, or a
   `:summary` with no `:details` parent at all, is not interactive by
   default). Returns nil for anything else, so callers (the click
   handler AND, since this cycle, `focusable-control?`/
   `activatable-control?` for real keyboard activation) can gate the
   whole toggle behavior on a single truthy check. Moved here (ahead of
   `focusable-control?`, which now calls it) from its original spot right
   after `radio-control?`, purely to avoid a forward reference -- no
   behavior change.

   Deliberately narrower than a click-anywhere-inside-<summary> ancestor
   walk (the shape `closest-link-id` uses for `<a>`): this only ever
   fires when the click/activation TARGET itself is the `<summary>`
   element, not some nested child inside it (e.g. a `<span>` inside
   `<summary>text</span></summary>`) -- the single most common
   real-world shape (`<summary>` with no nested markup of its own),
   matching this codebase's existing 'most common case, not full spec
   coverage' convention (see `auto-close-tags` in the sibling htmldom
   repo for the same kind of documented, honest cut)."
  [document node-id]
  (when (summary-control? document node-id)
    (let [parent-id (parent-node-id document node-id)]
      (when (= :details (get-in document [:nodes parent-id :tag]))
        parent-id))))

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
  [document node-id]
  (loop [parent-id (parent-node-id document node-id)]
    (when parent-id
      (let [parent (get-in document [:nodes parent-id])]
        (if (and (= :fieldset (:tag parent))
                 (truthy-attr? (get-in parent [:attrs :disabled]))
                 (not (when-let [legend-id (first-legend-child-id document parent-id)]
                        (descendant-or-self? document legend-id node-id))))
          true
          (recur (parent-node-id document parent-id)))))))

(defn editable-node?
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (and (not (disabled-control? document node-id))
         (or (= :textarea (:tag node))
             (and (= :input (:tag node))
                  (contains? text-input-types
                             (str/lower-case (str (or (get-in node [:attrs :type]) "")))))))))

(defn- input-type
  [node]
  (str/lower-case (str (or (get-in node [:attrs :type]) ""))))

(defn- hidden-input-control?
  [node]
  (and (= :input (:tag node))
       (= "hidden" (input-type node))))

(defn- file-input-control?
  [node]
  (and (= :input (:tag node))
       (= "file" (input-type node))))

(defn- validation-barred-control?
  [node]
  (or (hidden-input-control? node)
      (file-input-control? node)))

(defn focusable-control?
  "A real, direct `:summary` child of a `:details` element is ALSO
   focusable (real HTML5: `<summary>` is one of the handful of elements
   that are focusable/tabbable by default with no `tabindex` needed at
   all) -- deliberately checked as a separate `or` branch, not folded
   into `disabled-control-tags`, since that set is also used for the
   `disabled` attribute check in `disabled-control?`/`activatable-
   control?`'s own gate, and `<summary>` has no `disabled` attribute in
   real HTML5 at all (folding it in would wrongly imply it does)."
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (or (and (contains? disabled-control-tags (:tag node))
             (not (hidden-input-control? node))
             (not (file-input-control? node))
             (not (disabled-control? document node-id)))
        (boolean (details-parent-id document node-id)))))

(defn readonly-control?
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (and (editable-node? document node-id)
         (truthy-attr? (get-in node [:attrs :readonly])))))

(defn checkbox-control?
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (and (= :input (:tag node))
         (= "checkbox" (str/lower-case (str (get-in node [:attrs :type])))))))

(defn radio-control?
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (and (= :input (:tag node))
         (= "radio" (input-type node)))))

(defn select-control?
  [document node-id]
  (= :select (get-in document [:nodes node-id :tag])))

(defn submit-control?
  [document node-id]
  (let [node (get-in document [:nodes node-id])
        type (input-type node)]
    (and (not (disabled-control? document node-id))
         (or (and (= :button (:tag node))
                  (contains? #{"" "submit"} type))
             (and (= :input (:tag node))
                  (contains? #{"submit" "image"} type))))))

(defn reset-control?
  [document node-id]
  (let [node (get-in document [:nodes node-id])
        type (input-type node)]
    (and (not (disabled-control? document node-id))
         (or (and (= :button (:tag node))
                  (= "reset" type))
             (and (= :input (:tag node))
                  (= "reset" type))))))

(defn button-control?
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (and (not (disabled-control? document node-id))
         (or (= :button (:tag node))
             (and (= :input (:tag node))
                  (= "button" (input-type node)))))))

(defn activatable-control?
  "A real, direct `:summary` child of a `:details` element is ALSO
   activatable via Space/Enter (real HTML5 keyboard behavior, matching
   its click behavior in `reduce-click-event` exactly -- both routes
   converge on the identical toggle logic there, since the keyboard path
   below re-dispatches through `reduce-click-event` as a synthesized
   `:pointer/click`, not a separate implementation). `disabled-control?`
   is a structural no-op for `:summary` either way -- it only ever
   checks `disabled-control-tags`, which `:summary` was deliberately kept
   OUT of (see `focusable-control?`), since `<summary>` has no real
   `disabled` attribute at all."
  [document node-id]
  (and (not (disabled-control? document node-id))
       (or (button-control? document node-id)
           (checkbox-control? document node-id)
           (radio-control? document node-id)
           (submit-control? document node-id)
           (reset-control? document node-id)
           (boolean (details-parent-id document node-id)))))

(defn- radio-group-node-ids
  "The HTML radio button group containing node-id: same (non-empty) `name`
   *and* same owner form (https://html.spec.whatwg.org/multipage/input.html#radio-button-group).
   A radio without a name is not part of any group and groups only with itself,
   and same-named radios owned by different forms are independent groups."
  [document node-id]
  (let [node (get-in document [:nodes node-id])
        group-name (get-in node [:attrs :name])
        named? (not (str/blank? (str group-name)))
        group-form-id (ancestor-form-id document node-id)]
    (->> (:nodes document)
         (keep (fn [[id candidate]]
                 (when (and (= :input (:tag candidate))
                            (= "radio" (str/lower-case (str (get-in candidate [:attrs :type]))))
                            (if named?
                              (and (= group-name (get-in candidate [:attrs :name]))
                                   (= group-form-id (ancestor-form-id document id)))
                              (= id node-id))
                            (not (disabled-control? document id)))
                   id))))))

(defn- readonly-mutation-event?
  [event]
  (or (contains? #{:text/input :composition/start :composition/update :composition/end}
                 (:event/type event))
      (and (= :key/down (:event/type event))
           (contains? #{"Backspace" "Delete"} (:key event)))))

(defn- activation-key?
  [event]
  (and (= :key/down (:event/type event))
       (contains? activation-keys (:key event))))

(defn- link-activation-key?
  [event]
  (and (= :key/down (:event/type event))
       (= "Enter" (:key event))))

(defn disabled-control?
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (and (contains? disabled-control-tags (:tag node))
         (or (truthy-attr? (get-in node [:attrs :disabled]))
             (disabled-by-fieldset? document node-id)))))

(defn- attr
  [node k]
  (get-in node [:attrs k]))

(defn- style
  [node k]
  (or (get-in node [:attrs (keyword "style" (name k))])
      (get-in node [:attrs :style k])))

(defn scrollable-node?
  [document node-id]
  (let [node (get-in document [:nodes node-id])
        overflow (str/lower-case (str (or (style node :overflow)
                                           (attr node :overflow)
                                           "")))]
    (contains? scrollable-overflows overflow)))

(defn- parse-int
  [value fallback]
  (cond
    (integer? value) value
    (number? value) (int value)
    (string? value) (let [parsed #?(:clj (try
                                           (Long/parseLong value)
                                           (catch NumberFormatException _ nil))
                                    :cljs (let [n (js/parseInt value 10)]
                                            (when-not (js/isNaN n) n)))]
                      (or parsed fallback))
    :else fallback))

(defn- parse-number
  "A `min`/`max`/numeric `value` as a real number for range-validation
   purposes (see `validation-reason`'s `:range-underflow`/`:range-
   overflow` cases) -- unlike `parse-int` above, this also accepts a
   decimal fraction (`\"3.5\"`), since real HTML5 `<input type=\"number\">`/
   `<input type=\"range\">` values/`min`/`max` are ordinary floating-
   point numbers, not just integers. Mirrors kotoba-lang/cssom's own
   identically-scoped `parse-number` (that repo's `:invalid`/`:valid` CSS
   pseudo-class matching needs the exact same range check as this file's
   real form-submission-blocking validation, so both were fixed together
   -- see that repo's own docstring for why scientific notation is
   deliberately out of scope)."
  [value]
  (when (some? value)
    (let [s (str/trim (str value))]
      (when (re-matches #"-?\d+(\.\d+)?" s)
        #?(:clj (Double/parseDouble s)
           :cljs (js/parseFloat s))))))

(defn- state-from-node
  [node]
  (let [attrs (:attrs node)
        value (str (get attrs :value ""))
        caret (count value)
        start (parse-int (get attrs :selection-start) caret)
        end (parse-int (get attrs :selection-end) start)]
    (cond-> {:text/value value
             :text/caret end
             :text/selection [start end]
             :text/composition nil}
      ;; A real, active IME composition can legitimately have an empty
      ;; provisional string (right after compositionstart, or after
      ;; backspacing every composed character back to nothing while still
      ;; composing) -- so "is composing" must be its own boolean signal
      ;; (`:composing`), not inferred from whether `:composition`'s text
      ;; value happens to be blank. Both a genuinely-ended composition and
      ;; a genuinely-active-but-empty one persist `:composition ""`
      ;; identically; only `:composing` tells them apart.
      (true? (get attrs :composing))
      (assoc :text/composition {:composition/text (str (get attrs :composition ""))}))))

(defn- apply-state
  [document node-id state]
  (let [state (text-edit/normalize-selection state)
        [start end] (:text/selection state)]
    (-> document
        (dom/set-attribute node-id :value (:text/value state))
        (dom/set-attribute node-id :selection-start start)
        (dom/set-attribute node-id :selection-end end)
        (dom/set-attribute node-id :composition (get-in state [:text/composition :composition/text] ""))
        (dom/set-attribute node-id :composing (some? (:text/composition state))))))

(defn- composing?
  "Real browsers suppress an input's own \"Enter submits the form\"
  default action for the entire span of an active IME composition
  (matching a real KeyboardEvent's own `isComposing` flag). Checked via
  the dedicated `:composing` boolean attribute `apply-state` maintains,
  never via `:composition`'s text value -- that text is legitimately
  \"\" both while genuinely still composing and once composition has
  ended, so it cannot tell the two states apart on its own."
  [document node-id]
  (true? (get-in document [:nodes node-id :attrs :composing])))

(defn- clear-node-validation-state
  [document node-id]
  (-> document
      (dom/set-attribute node-id :invalid false)
      (dom/set-attribute node-id :validation-reason "")))

(defn- input-event
  [node-id state]
  {:event/type "input"
   :target/id node-id
   :value (:text/value state)
   :selection/start (first (:text/selection state))
   :selection/end (second (:text/selection state))})

(defn- text-change-event
  [node-id value]
  {:event/type "change"
   :target/id node-id
   :value value})

(defn- beforeinput-event
  [node-id event input-type state]
  (cond-> {:event/type "beforeinput"
           :target/id node-id
           :inputType input-type
           :value (:text/value state)
           :selection/start (first (:text/selection state))
           :selection/end (second (:text/selection state))}
    (contains? event :text) (assoc :data (:text event))))

(defn- file-name
  [value]
  (let [value (str value)
        slash (.lastIndexOf value "/")
        backslash (.lastIndexOf value "\\")
        idx (max slash backslash)]
    (if (neg? idx) value (subs value (inc idx)))))

(defn- selected-file
  [file]
  (let [name (file-name (or (:name file) (:file/name file) ""))]
    (cond-> {:file/name name}
      (contains? file :type) (assoc :file/type (:type file))
      (contains? file :file/type) (assoc :file/type (:file/type file))
      (contains? file :size) (assoc :file/size (:size file))
      (contains? file :file/size) (assoc :file/size (:file/size file))
      (contains? file :last-modified) (assoc :file/last-modified (:last-modified file))
      (contains? file :file/last-modified) (assoc :file/last-modified (:file/last-modified file)))))

(defn- selected-files
  [event]
  (->> (:files event)
       (keep (fn [file]
               (let [file (selected-file file)]
                 (when (seq (:file/name file))
                   file))))
       vec))

(defn- file-select-event
  [node-id files]
  {:event/type "file"
   :target/id node-id
   :files files})

(defn- checked-event
  [node-id event-type checked]
  {:event/type event-type
   :target/id node-id
   :checked checked})

(defn- toggle-event
  [node-id open?]
  {:event/type "toggle"
   :target/id node-id
   :open open?})

(defn- composition-event
  [node-id event-type state]
  {:event/type event-type
   :target/id node-id
   :value (:text/value state)
   :composition (get-in state [:text/composition :composition/text])})

(defn- scroll-event
  [node-id scroll-left scroll-top delta-x delta-y]
  {:event/type "scroll"
   :target/id node-id
   :scroll/left scroll-left
   :scroll/top scroll-top
   :delta/x delta-x
   :delta/y delta-y})

(defn- pointer-event
  [node-id event-type event]
  (let [pointer-id (or (:pointerId event) (:pointer-id event))
        pointer-type (or (:pointerType event) (:pointer-type event))
        primary? (cond
                   (contains? event :isPrimary) (:isPrimary event)
                   (contains? event :is-primary?) (:is-primary? event)
                   :else nil)]
    (cond-> {:event/type event-type
             :target/id node-id}
      (contains? event :x) (assoc :x (:x event))
      (contains? event :y) (assoc :y (:y event))
      (contains? event :button) (assoc :button (:button event))
      (some? pointer-id) (assoc :pointerId pointer-id)
      (some? pointer-type) (assoc :pointerType pointer-type)
      (some? primary?) (assoc :isPrimary (boolean primary?))
      (contains? event :pressure) (assoc :pressure (:pressure event))
      (contains? event :width) (assoc :width (:width event))
      (contains? event :height) (assoc :height (:height event)))))

(declare listener?)

(defn- key-event
  [node-id event-type event]
  (cond-> {:event/type event-type
           :target/id node-id
           :key (:key event)}
    (contains? event :code) (assoc :code (:code event))
    (contains? event :repeat?) (assoc :repeat (boolean (:repeat? event)))
    (contains? event :shift?) (assoc :shiftKey (boolean (:shift? event)))
    (contains? event :ctrl?) (assoc :ctrlKey (boolean (:ctrl? event)))
    (contains? event :meta?) (assoc :metaKey (boolean (:meta? event)))
    (contains? event :alt?) (assoc :altKey (boolean (:alt? event)))))

(defn- dispatch-key-event
  [document node-id event-name event]
  (if (listener? document node-id event-name)
    (dom/dispatch-event document node-id event-name
                        (key-event node-id event-name event))
    document))

(defn- beforeinput-type
  [event]
  (case (:event/type event)
    :text/input "insertText"
    :composition/end "insertCompositionText"
    :key/down (case (:key event)
                "Backspace" "deleteContentBackward"
                "Delete" "deleteContentForward"
                nil)
    nil))

(defn- dispatch-beforeinput-event
  [document node-id event state input-type]
  (if (listener? document node-id "beforeinput")
    (dom/dispatch-event document node-id "beforeinput"
                        (beforeinput-event node-id event input-type state))
    document))

(defn pointer-id
  [event]
  (or (:pointerId event)
      (:pointer-id event)
      1))

(declare ancestor-form-id)

(defn- node-by-dom-id
  [document dom-id]
  (some (fn [[node-id node]]
          (when (= dom-id (get-in node [:attrs :id]))
            node-id))
        (:nodes document)))

(defn- descendant-node-ids
  ([document node-id]
   (descendant-node-ids document node-id #{}))
  ([document node-id visited]
   (when-not (contains? visited node-id)
     (let [visited (conj visited node-id)]
       (mapcat (fn [child-id]
                 (cons child-id (descendant-node-ids document child-id visited)))
               (get-in document [:nodes node-id :children]))))))

(defn- text-content
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (case (:node/type node)
      :text (:text node)
      :element (str/join "" (map #(text-content document %) (:children node)))
      "")))

(defn- option-node?
  [document node-id]
  (= :option (get-in document [:nodes node-id :tag])))

(defn- option-value
  [document option-id]
  (let [attrs (get-in document [:nodes option-id :attrs])]
    (str (if (contains? attrs :value)
           (:value attrs)
           (text-content document option-id)))))

(defn- select-option-ids
  [document select-id]
  (->> (descendant-node-ids document select-id)
       (filter #(option-node? document %))
       vec))

(defn- option-disabled?
  [document option-id]
  (or (truthy-attr? (get-in document [:nodes option-id :attrs :disabled]))
      (loop [parent-id (parent-node-id document option-id)]
        (when parent-id
          (let [parent (get-in document [:nodes parent-id])]
            (if (= :optgroup (:tag parent))
              (truthy-attr? (get-in parent [:attrs :disabled]))
              (recur (parent-node-id document parent-id))))))))

(defn- selected-option-id
  [document select-id]
  (let [options (select-option-ids document select-id)
        selected-options (filter #(truthy-attr? (get-in document [:nodes % :attrs :selected]))
                                 options)]
    (or (first (remove #(option-disabled? document %) selected-options))
        (when (empty? selected-options)
          (first (remove #(option-disabled? document %) options))))))

(defn- selected-option-ids
  [document select-id]
  (let [options (select-option-ids document select-id)
        selected-options (filter #(truthy-attr? (get-in document [:nodes % :attrs :selected]))
                                 options)
        multiple? (truthy-attr? (get-in document [:nodes select-id :attrs :multiple]))]
    (if (and (empty? selected-options) (not multiple?))
      (when-let [option-id (first (remove #(option-disabled? document %) options))]
        [option-id])
      (vec (remove #(option-disabled? document %) selected-options)))))

(defn- select-value
  [document select-id]
  (when-let [option-id (selected-option-id document select-id)]
    (option-value document option-id)))

(defn- select-values
  [document select-id]
  (mapv #(option-value document %) (selected-option-ids document select-id)))

(defn- set-select-option
  [document select-id option-id]
  (let [options (select-option-ids document select-id)
        value (when option-id (option-value document option-id))]
    (cond-> (reduce #(dom/set-attribute %1 %2 :selected (= %2 option-id))
                    document
                    options)
      (some? value) (dom/set-attribute select-id :value value))))

(defn- set-select-options
  [document select-id option-ids]
  (let [options (select-option-ids document select-id)
        option-id-set (set option-ids)
        first-value (some #(when (and (contains? option-id-set %)
                                      (not (option-disabled? document %)))
                             (option-value document %))
                          options)]
    (cond-> (reduce #(dom/set-attribute %1 %2 :selected (contains? option-id-set %2))
                    document
                    options)
      (some? first-value) (dom/set-attribute select-id :value first-value)
      (nil? first-value) (dom/set-attribute select-id :value ""))))

(defn- select-event
  [select-id event-type value event]
  (cond-> {:event/type event-type
           :target/id select-id
           :value value}
    (contains? event :x) (assoc :x (:x event))
    (contains? event :y) (assoc :y (:y event))))

(defn- dispatch-select-change
  [document select-id value event]
  (-> document
      (dom/dispatch-event select-id "input" (select-event select-id "input" value event))
      (dom/dispatch-event select-id "change" (select-event select-id "change" value event))))

(defn- successful-control-entries
  [document submitter-id event node-id]
  (let [node (get-in document [:nodes node-id])
        attrs (:attrs node)
        type (input-type node)
        name (:name attrs)
        checked? (truthy-attr? (:checked attrs))
        submitter? (= submitter-id node-id)]
    (when (and (seq (str name))
               (not (disabled-control? document node-id)))
      (cond
        (= :textarea (:tag node))
        [{:name (str name)
          :value (str (get attrs :value ""))
          :node/id node-id}]

        (and (= :input (:tag node))
             (contains? value-input-types type))
        [{:name (str name)
          :value (str (get attrs :value ""))
          :node/id node-id}]

        (and (= :input (:tag node))
             (contains? #{"hidden"} type))
        [{:name (str name)
          :value (str (get attrs :value ""))
          :node/id node-id}]

        (and (= :input (:tag node))
             (= "file" type)
             (seq (:files attrs)))
        (mapv (fn [file]
                {:name (str name)
                 :value (str (:file/name file))
                 :node/id node-id
                 :file/name (:file/name file)
                 :file/type (:file/type file)
                 :file/size (:file/size file)
                 :file/last-modified (:file/last-modified file)})
              (:files attrs))

        (and (= :input (:tag node))
             (contains? #{"checkbox" "radio"} type)
             checked?)
        [{:name (str name)
          :value (str (get attrs :value "on"))
          :node/id node-id}]

        (= :select (:tag node))
        (let [values (select-values document node-id)]
          (when (seq values)
            (mapv (fn [value]
                    {:name (str name)
                     :value (str value)
                     :node/id node-id})
                  values)))

        (and submitter?
             (= :input (:tag node))
             (= "image" type))
        [{:name (str name ".x")
          :value (str (or (:x event) 0))
          :node/id node-id}
         {:name (str name ".y")
          :value (str (or (:y event) 0))
          :node/id node-id}]

        (and submitter?
             (submit-control? document node-id))
        [{:name (str name)
          :value (str (get attrs :value ""))
          :node/id node-id}]

        :else nil))))

(defn- form-associated-node-ids
  [document form-id]
  (let [descendants (vec (descendant-node-ids document form-id))
        descendant-set (set descendants)
        form-dom-id (get-in document [:nodes form-id :attrs :id])
        owned-descendants (filter (fn [node-id]
                                    (let [form-attr (get-in document [:nodes node-id :attrs :form])]
                                      (or (nil? form-attr)
                                          (= form-dom-id form-attr))))
                                  descendants)
        associated (when (seq (str form-dom-id))
                     (keep (fn [[node-id node]]
                             (when (and (not= node-id form-id)
                                        (not (contains? descendant-set node-id))
                                        (= form-dom-id (get-in node [:attrs :form])))
                               node-id))
                           (:nodes document)))]
    (vec (distinct (concat owned-descendants associated)))))

(defn- form-data
  [document form-id submitter-id event]
  (->> (form-associated-node-ids document form-id)
       (mapcat #(or (successful-control-entries document submitter-id event %) []))
       vec))

(defn- radio-required-satisfied?
  [document node-id]
  (some #(truthy-attr? (get-in document [:nodes % :attrs :checked]))
        (radio-group-node-ids document node-id)))

(defn- text-value-for-validation
  [document node-id]
  (let [node (get-in document [:nodes node-id])
        attrs (:attrs node)
        type (input-type node)]
    (cond
      (= :textarea (:tag node))
      (str (get attrs :value ""))

      (and (= :input (:tag node))
           (contains? value-input-types type))
      (str (get attrs :value ""))

      :else nil)))

(def ^:private email-format-pattern
  "The real WHATWG HTML5 email-format regex (verbatim -- the same one
   real browsers use for `type=\"email\"` `typeMismatch` checking), not a
   hand-simplified approximation. Deliberately still an honest scope-cut
   in one specific way: the `multiple` attribute (a comma-separated list
   of addresses, each individually checked) is NOT supported -- a single
   address only, matching this file's own established 'single X only'
   posture elsewhere (e.g. `text-shadow`'s single-shadow scope-cut in the
   sibling kotoba-lang/cssom repo)."
  #"[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*")

(def ^:private url-format-pattern
  "A deliberately simplified `type=\"url\"` format check -- real HTML5
   requires successfully parsing via the full WHATWG URL parser (which
   accepts almost anything with a scheme); this engine has no such parser,
   so this instead requires the single most common practical shape, an
   absolute URL with a real scheme (`scheme://...`) -- an honest,
   documented scope-cut consistent with this file's other 'reasonable
   baseline, not full spec coverage' checks."
  #"[a-zA-Z][a-zA-Z0-9+.-]*://\S+")

(defn- type-mismatch?
  "Real HTML5 `typeMismatch`: a non-blank value on `type=\"email\"`/
   `\"url\"` that doesn't match that type's own format -- previously read
   NOWHERE at all, an honest, documented scope-cut called out right next
   to `pattern`'s own (now-fixed) gap in this repo's JS-facing
   `__kotobaValidityState`. Mirrors `pattern-invalid?`'s own shape --
   deliberately NOT enforced for a blank value (that's `required`'s
   concern, not `typeMismatch`'s)."
  [type value]
  (and (not (str/blank? value))
       (case type
         "email" (not (re-matches email-format-pattern value))
         "url" (not (re-matches url-format-pattern value))
         false)))

(def ^:private step-mismatch-tolerance
  "Mirrors kotoba-lang/cssom's own identically-scoped `step-mismatch-
   tolerance` -- a small, fixed epsilon rather than real HTML5's own
   precise-decimal step-validity algorithm, since `parse-number` already
   only accepts plain decimal strings (no scientific notation, an
   existing honest scope-cut), an honest, documented simplification."
  1e-9)

(defn- step-invalid?
  "Real HTML5 step-mismatch: `type=\"number\"`/`\"range\"` with a non-blank,
   numerically-parseable value that isn't `step-base + n*step` for some
   integer `n` -- previously read NOWHERE at all (an honest, documented
   scope-cut in this repo's own JS-facing `__kotobaValidityState`:
   `stepMismatch: false` hardcoded), mirrors kotoba-lang/cssom's own
   identically-scoped `step-invalid?` exactly (fixed together for the
   same reason `range-invalid?`/`pattern-invalid?`/`type-mismatch?`
   above were). Real HTML5's own default `step` is `1` (NOT 'no
   constraint' -- `<input type=\"number\" value=\"3.5\">` with no `step`
   attribute at all is real HTML5 INVALID), and a real, legal
   `step=\"any\"` disables the check entirely. A `step` present but not
   itself a valid positive number falls back to that same default of
   `1` -- deliberately DIFFERENT from `min`/`max`'s own degrade-don't-
   guess convention, since real HTML5 step genuinely has a defined
   default to fall back to."
  [type value attrs]
  (and (contains? #{"number" "range"} type)
       (not (str/blank? value))
       (when-let [n (parse-number value)]
         (let [raw-step (:step attrs)]
           (when-not (and raw-step (= "any" (str/lower-case (str raw-step))))
             (let [parsed-step (parse-number raw-step)
                   step (if (and parsed-step (pos? parsed-step)) parsed-step 1.0)
                   base (or (parse-number (:min attrs)) 0.0)
                   steps (/ (- n base) step)
                   frac (mod steps 1)]
               (and (> frac step-mismatch-tolerance)
                    (< frac (- 1 step-mismatch-tolerance)))))))))

(defn- compile-pattern
  "Compiles a real HTML5 `pattern` attribute value into a `re-pattern`, or
   `nil` if it isn't a legal regex -- a malformed `pattern` is simply NOT
   enforced (matching this file's own existing degrade-don't-guess
   treatment of a non-numeric `minlength`/`maxlength`/`min`/`max` above),
   not a crash. `re-matches` (used by `validation-reason` below) already
   requires matching the ENTIRE string, the same implicit `^(?:...)$`
   anchoring real HTML5 applies to `pattern` itself -- no extra
   concatenation needed."
  [pattern]
  #?(:clj (try (re-pattern pattern) (catch Exception _ nil))
     :cljs (try (re-pattern pattern) (catch :default _ nil))))

(defn- validation-reason
  "`:range-underflow`/`:range-overflow` (real HTML5 `ValidityState`
   property names) for `type=\"number\"`/`\"range\"`: a non-blank,
   numerically-parseable value outside its own `min`/`max` attributes
   (either bound optional; a non-numeric `min`/`max` is simply not
   enforced, matching this fn's existing degrade-don't-guess treatment of
   a non-numeric `minlength`/`maxlength`). Before this, a real, common
   pattern like `<input type=\"number\" min=\"1\" max=\"10\" value=\"15\">`
   was silently treated as valid at real form-submission time -- this
   engine would let the form submit when a real browser blocks it and
   dispatches a real `invalid` event instead. Fixed together with the
   identical gap in kotoba-lang/cssom's own `constraint-invalid?` (that
   repo's `:invalid`/`:valid` CSS pseudo-class matching had the exact
   same missing check).

   `:pattern-mismatch` for a real, non-blank `pattern` attribute (valid on
   exactly the same `text-input-types` set real HTML5 restricts it to --
   `text`/`search`/`url`/`tel`/`email`/`password`, NOT `<textarea>` even
   though `input-type` also resolves an untyped `<textarea>` to the same
   `\"\"` this set contains, hence the explicit `(= :input (:tag node))`
   guard below) whose value doesn't fully match it -- previously read
   NOWHERE at all (a hardcoded `patternMismatch: false` scope-cut existed
   in this repo's own JS-facing `__kotobaValidityState` and in
   kotoba-lang/cssom's `constraint-invalid?`), confirmed via direct REPL
   reproduction that a real `<input pattern=\"[0-9]+\" value=\"abc\">`
   was silently treated as valid at real form-submission time.

   `:type-mismatch` for a non-blank `type=\"email\"`/`\"url\"` value not
   matching that type's own format (see `type-mismatch?`) -- checked
   BEFORE `:pattern-mismatch` since a real, wrong-FORMAT value is a more
   fundamental problem than merely not matching an author's own custom
   `pattern` (also legal on `email`/`url`), matching real ValidityState's
   own IDL property ordering.

   `:step-mismatch` (see `step-invalid?`) is checked LAST, after
   `:range-underflow`/`:range-overflow`, matching real ValidityState's
   own IDL property order (`stepMismatch` is declared after
   `rangeOverflow` in the real interface)."
  [document node-id]
  (let [node (get-in document [:nodes node-id])
        attrs (:attrs node)
        type (input-type node)
        text-value (text-value-for-validation document node-id)
        min-length (parse-int (:minlength attrs) nil)
        max-length (parse-int (:maxlength attrs) nil)
        range-value (when (and (= :input (:tag node))
                               (contains? #{"number" "range"} type)
                               (some? text-value)
                               (not (str/blank? text-value)))
                     (parse-number text-value))
        range-min (when range-value (parse-number (:min attrs)))
        range-max (when range-value (parse-number (:max attrs)))
        pattern-regex (when (and (= :input (:tag node))
                                 (contains? text-input-types type)
                                 (some? text-value)
                                 (not (str/blank? text-value))
                                 (some? (:pattern attrs)))
                        (compile-pattern (:pattern attrs)))]
    (when-not (or (disabled-control? document node-id)
                  (readonly-control? document node-id)
                  (validation-barred-control? node))
      (cond
        (and (truthy-attr? (:required attrs))
             (cond
               (some? text-value) (str/blank? text-value)
               (and (= :input (:tag node)) (= "checkbox" type)) (not (truthy-attr? (:checked attrs)))
               (and (= :input (:tag node)) (= "radio" type)) (not (radio-required-satisfied? document node-id))
               (= :select (:tag node)) (every? #(str/blank? (str %))
                                                (select-values document node-id))
               :else false))
        :value-missing

        (and (some? text-value)
             min-length
             (not (str/blank? text-value))
             (< (count text-value) min-length))
        :too-short

        (and (some? text-value)
             max-length
             (> (count text-value) max-length))
        :too-long

        (and (= :input (:tag node))
             (some? text-value)
             (type-mismatch? type text-value))
        :type-mismatch

        (and pattern-regex (not (re-matches pattern-regex text-value)))
        :pattern-mismatch

        (and range-min (< range-value range-min))
        :range-underflow

        (and range-max (> range-value range-max))
        :range-overflow

        (and (= :input (:tag node))
             (some? text-value)
             (step-invalid? type text-value attrs))
        :step-mismatch

        :else nil))))

(defn- invalid-controls
  [document form-id]
  (->> (form-associated-node-ids document form-id)
       (keep (fn [node-id]
               (when-let [reason (validation-reason document node-id)]
                 {:node/id node-id
                  :reason reason})))
       (distinct)
       vec))

(defn- invalid-event
  [node-id form-id submitter-id reason event]
  (cond-> {:event/type "invalid"
           :target/id node-id
           :form/id form-id
           :submitter/id submitter-id
           :reason reason}
    (contains? event :key) (assoc :key (:key event))
    (contains? event :x) (assoc :x (:x event))
    (contains? event :y) (assoc :y (:y event))))

(defn- dispatch-invalid-events
  [document form-id submitter-id invalid-controls event]
  (reduce (fn [document invalid-control]
            (let [node-id (:node/id invalid-control)
                  reason (:reason invalid-control)]
              (-> document
                  (dom/set-attribute node-id :invalid true)
                  (dom/set-attribute node-id :validation-reason (name reason))
                  (dom/dispatch-event node-id "invalid"
                                      (invalid-event node-id form-id submitter-id reason event)))))
          document
          invalid-controls))

(defn- clear-validation-state
  [document form-id]
  (reduce (fn [document node-id]
            (-> document
                (dom/set-attribute node-id :invalid false)
                (dom/set-attribute node-id :validation-reason "")))
          document
          (form-associated-node-ids document form-id)))

(defn- submit-event
  [form-id submitter-id form-data event]
  (cond-> {:event/type "submit"
           :target/id form-id
           :submitter/id submitter-id
           :form/data form-data}
    (contains? event :key) (assoc :key (:key event))
    (contains? event :x) (assoc :x (:x event))
    (contains? event :y) (assoc :y (:y event))))

(defn- reset-event
  [form-id resetter-id event]
  (cond-> {:event/type "reset"
           :target/id form-id
           :resetter/id resetter-id}
    (contains? event :key) (assoc :key (:key event))
    (contains? event :x) (assoc :x (:x event))
    (contains? event :y) (assoc :y (:y event))))

(defn- listener?
  [document node-id event-name]
  (contains? (get-in document [:listeners node-id] {}) (keyword event-name)))

(defn- dispatch-pointer-event
  [document node-id event-name event]
  (if (listener? document node-id event-name)
    (dom/dispatch-event document node-id event-name
                        (pointer-event node-id event-name event))
    document))

(defn- reduce-pointer-move-event
  [document node-id event]
  (let [previous-hover (:hover document)
        changed? (not= previous-hover node-id)
        before-count (count (:ops document))
        document (cond-> document
                   (and changed? previous-hover)
                   (dispatch-pointer-event previous-hover "pointerout" event)

                   (and changed? previous-hover)
                   (dispatch-pointer-event previous-hover "mouseout" event)

                   (and changed? previous-hover)
                   (dispatch-pointer-event previous-hover "pointerleave" event)

                   (and changed? previous-hover)
                   (dispatch-pointer-event previous-hover "mouseleave" event)

                   changed?
                   (assoc :hover node-id)

                   changed?
                   (dispatch-pointer-event node-id "pointerover" event)

                   changed?
                   (dispatch-pointer-event node-id "mouseover" event)

                   changed?
                   (dispatch-pointer-event node-id "pointerenter" event)

                   changed?
                   (dispatch-pointer-event node-id "mouseenter" event))
        document (dispatch-pointer-event document node-id "pointermove" event)
        document (dispatch-pointer-event document node-id "mousemove" event)
        dispatched? (< before-count (count (:ops document)))]
    {:document document
     :node/id node-id
     :previous/node-id previous-hover
     :event event
     :handled? (boolean (or changed? dispatched?))
     :hovered? changed?
     :commit? (boolean (or changed? dispatched?))}))

(defn- reduce-pointer-button-event
  [document node-id event event-name]
  (let [before-count (count (:ops document))
        pid (pointer-id event)
        down? (= "pointerdown" event-name)
        release? (contains? #{"pointerup" "pointercancel"} event-name)
        capture-held? (= node-id (get-in document [:pointer/capture pid]))
        document (cond-> document
                   down? (assoc-in [:pointer/capture pid] node-id))
        document (dispatch-pointer-event document node-id event-name event)
        document (cond-> document
                   down? (dispatch-pointer-event node-id "gotpointercapture" event)
                   (and release? capture-held?) (dispatch-pointer-event node-id "lostpointercapture" event)
                   (and release? capture-held?) (update :pointer/capture dissoc pid))
        dispatched? (< before-count (count (:ops document)))
        capture-changed? (or down? (and release? capture-held?))]
    {:document document
     :node/id node-id
     :event event
     :pointer/id pid
     :handled? (boolean (or dispatched? down? release?))
     :commit? (boolean (or dispatched? down? release? capture-changed?))}))

(defn- reduce-key-listener-event
  [document node-id event event-name]
  (let [before-count (count (:ops document))
        document (dispatch-key-event document node-id event-name event)
        dispatched? (< before-count (count (:ops document)))]
    {:document document
     :node/id node-id
     :event event
     :handled? dispatched?
     :commit? dispatched?}))

(defn- text-change-control?
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (or (= :textarea (:tag node))
        (and (= :input (:tag node))
             (contains? text-input-types (input-type node))))))

(defn- dispatch-text-change-if-dirty
  [document node-id]
  (let [node (get-in document [:nodes node-id])
        dirty? (truthy-attr? (get-in node [:attrs :dirty-value]))]
    (if (and dirty? (text-change-control? document node-id))
      (let [value (str (get-in node [:attrs :value] ""))
            document (dom/set-attribute document node-id :dirty-value false)]
        (if (listener? document node-id "change")
          (dom/dispatch-event document node-id "change" (text-change-event node-id value))
          document))
      document)))

(defn- blur-focused
  [document next-focus]
  (let [previous (:focus document)]
    (if (and previous (not= previous next-focus))
      (-> document
          (dispatch-text-change-if-dirty previous)
          (dispatch-pointer-event previous "blur" {})
          (assoc :focus nil))
      document)))

(defn- focus-editable
  [document node-id]
  (if (= (:focus document) node-id)
    document
    (-> document
        (blur-focused node-id)
        (assoc :focus node-id)
        (dispatch-pointer-event node-id "focus" {}))))

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

(defn- ancestor-form-id
  [document node-id]
  (or (when-let [form-dom-id (get-in document [:nodes node-id :attrs :form])]
        (let [form-id (node-by-dom-id document form-dom-id)]
          (when (= :form (get-in document [:nodes form-id :tag]))
            form-id)))
      (let [parents (parent-index document)]
        (loop [id (get parents node-id)
               seen #{}]
          (when (and id (not (contains? seen id)))
            (let [node (get-in document [:nodes id])]
              (if (= :form (:tag node))
                id
                (recur (get parents id) (conj seen id)))))))))

(defn- ancestor-select-id
  [document node-id]
  (let [parents (parent-index document)]
    (loop [id (get parents node-id)
           seen #{}]
      (when (and id (not (contains? seen id)))
        (let [node (get-in document [:nodes id])]
          (if (= :select (:tag node))
            id
            (recur (get parents id) (conj seen id))))))))

(defn- dispatch-submit-event
  [document form-id submitter-id form-data event]
  (if form-id
    (dom/dispatch-event document form-id "submit"
                        (submit-event form-id submitter-id form-data event))
    document))

(defn- reset-control-state
  [document node-id]
  (let [node (get-in document [:nodes node-id])
        attrs (:attrs node)
        type (input-type node)
        default-value (str (get attrs :default-value ""))
        default-checked? (truthy-attr? (:default-checked attrs))]
    (cond
      (= :textarea (:tag node))
      (let [caret (count default-value)]
        (-> document
            (dom/set-attribute node-id :value default-value)
            (dom/set-attribute node-id :selection-start caret)
            (dom/set-attribute node-id :selection-end caret)
            (dom/set-attribute node-id :composition "")
            (dom/set-attribute node-id :composing false)
            (clear-node-validation-state node-id)))

      (and (= :input (:tag node))
           (contains? text-input-types type))
      (let [caret (count default-value)]
        (-> document
            (dom/set-attribute node-id :value default-value)
            (dom/set-attribute node-id :selection-start caret)
            (dom/set-attribute node-id :selection-end caret)
            (dom/set-attribute node-id :composition "")
            (dom/set-attribute node-id :composing false)
            (clear-node-validation-state node-id)))

      (and (= :input (:tag node))
           (contains? value-input-types type))
      (-> document
          (dom/set-attribute node-id :value default-value)
          (clear-node-validation-state node-id))

      (and (= :input (:tag node))
           (= "hidden" type))
      (-> document
          (dom/set-attribute node-id :value default-value)
          (clear-node-validation-state node-id))

      (and (= :input (:tag node))
           (= "file" type))
      (-> document
          (dom/set-attribute node-id :value "")
          (dom/set-attribute node-id :files [])
          (clear-node-validation-state node-id))

      (and (= :input (:tag node))
           (contains? #{"checkbox" "radio"} type))
      (-> document
          (dom/set-attribute node-id :checked default-checked?)
          (clear-node-validation-state node-id))

      (= :select (:tag node))
      (let [options (select-option-ids document node-id)
            multiple? (truthy-attr? (get-in node [:attrs :multiple]))
            default-options (filter #(truthy-attr? (get-in document [:nodes % :attrs :default-selected]))
                                    options)
            option-ids (if multiple?
                         (vec default-options)
                         [(or (first default-options)
                              (first options))])]
        (-> document
            (set-select-options node-id (remove nil? option-ids))
            (clear-node-validation-state node-id)))

      :else document)))

(defn- dispatch-reset-event
  [document form-id resetter-id event]
  (if form-id
    (dom/dispatch-event document form-id "reset"
                        (reset-event form-id resetter-id event))
    document))

(defn- apply-reset-default-action
  [document resetter-id event]
  (when-let [form-id (ancestor-form-id document resetter-id)]
    (let [document (reduce reset-control-state
                           document
                           (form-associated-node-ids document form-id))
          document (dispatch-reset-event document form-id resetter-id event)]
      {:document document
       :form/id form-id
       :reset? true})))

(defn- apply-submit-default-action
  [document submitter-id event]
  (when-let [form-id (ancestor-form-id document submitter-id)]
    (let [form-node (get-in document [:nodes form-id])
          submitter-node (get-in document [:nodes submitter-id])
          skip-validation? (or (truthy-attr? (get-in form-node [:attrs :novalidate]))
                               (truthy-attr? (get-in submitter-node [:attrs :formnovalidate])))
          invalid-controls (when-not skip-validation?
                             (invalid-controls document form-id))
          invalid-ids (mapv :node/id invalid-controls)]
      (if (seq invalid-controls)
        {:document (dispatch-invalid-events document form-id submitter-id invalid-controls event)
         :form/id form-id
         :invalid/control-ids invalid-ids
         :invalid? true}
        (let [form-data (form-data document form-id submitter-id event)]
          {:document (-> document
                         (clear-validation-state form-id)
                         (dispatch-submit-event form-id submitter-id form-data event))
           :form/id form-id
           :form/data form-data
           :submitted? true})))))

(defn- option-id-for-change
  [document select-id event]
  (or (:option/id event)
      (when (and (:value event) select-id)
        (first (filter #(= (str (:value event)) (option-value document %))
                       (select-option-ids document select-id))))))

(defn- reduce-select-change
  [document select-id option-id event]
  (let [disabled? (or (disabled-control? document select-id)
                      (option-disabled? document option-id))]
    (if (or disabled? (nil? option-id))
      {:document document
       :node/id (or option-id select-id)
       :event event
       :handled? false}
      (let [value (option-value document option-id)
            document (-> document
                         (focus-editable select-id)
                         (set-select-option select-id option-id)
                         (clear-node-validation-state select-id)
                         (dispatch-select-change select-id value event))]
        {:document document
         :node/id select-id
         :option/id option-id
         :event event
         :value value
         :selected? true
         :handled? true}))))

(defn- first-descendant-control-id
  ([document node-id]
   (first-descendant-control-id document node-id #{}))
  ([document node-id visited]
   (when-not (contains? visited node-id)
     (let [visited (conj visited node-id)
           children (get-in document [:nodes node-id :children])]
       (some (fn [child-id]
               (or (when (focusable-control? document child-id)
                     child-id)
                   (first-descendant-control-id document child-id visited)))
             children)))))

(defn- label-control-id
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (when (= :label (:tag node))
      (or (when-let [for-id (get-in node [:attrs :for])]
            (let [control-id (node-by-dom-id document for-id)]
              (when (focusable-control? document control-id)
                control-id)))
          (first-descendant-control-id document node-id)))))

(defn- closest-link-id
  [document node-id]
  (loop [id node-id]
    (when id
      (let [node (get-in document [:nodes id])
            href (get-in node [:attrs :href])]
        (if (and (= :a (:tag node))
                 (some? href)
                 (not (str/blank? (str href))))
          id
          (recur (parent-node-id document id)))))))

(defn- reduce-click-event
  [document node-id event]
  (if-let [control-id (let [control-id (label-control-id document node-id)]
                        (when (and control-id
                                   (not= control-id node-id))
                          control-id))]
    (let [label-click-listener? (listener? document node-id "click")
          document (cond-> document
                     label-click-listener? (dispatch-pointer-event node-id "click" event))
          result (reduce-click-event document control-id event)]
      (assoc result
             :document (:document result)
             :event event
             :label/id node-id
             :handled? (boolean (or label-click-listener?
                                     (:handled? result)))))
    (if (option-node? document node-id)
      (if-let [select-id (ancestor-select-id document node-id)]
        (reduce-select-change document select-id node-id event)
        {:document document
         :node/id node-id
         :event event
         :handled? false})
      (let [node (get-in document [:nodes node-id])
            hidden-input? (hidden-input-control? node)
            file-input? (file-input-control? node)
            disabled? (disabled-control? document node-id)
            link-id (when-not disabled?
                      (closest-link-id document node-id))]
        (if (or hidden-input? file-input?)
          {:document document
           :node/id node-id
           :event event
           :handled? false}
          (if link-id
            (let [click-listener? (listener? document node-id "click")
                  previous-focus (:focus document)
                  link-attrs (get-in document [:nodes link-id :attrs])
                  download? (contains? link-attrs :download)
                  download-name (when download?
                                  (let [value (str (:download link-attrs))]
                                    (when-not (str/blank? value) value)))
                  document (cond-> (blur-focused document nil)
                             click-listener? (dispatch-pointer-event node-id "click" event))
                  focus-changed? (and previous-focus (not= previous-focus (:focus document)))]
              {:document document
               :node/id node-id
               :link/id link-id
               :event event
               :handled? true
               :commit? (boolean (or click-listener? focus-changed?))
               :navigation/href (get-in document [:nodes link-id :attrs :href])
               :navigation/target (str (or (get-in document [:nodes link-id :attrs :target])
                                           "_self"))
               :navigation/rel (get-in link-attrs [:rel])
               :navigation/referrer-policy (get-in link-attrs [:referrerpolicy])
               :navigation/download? download?
               :download/filename download-name})
          (let [click-listener? (and (not disabled?)
                                     (listener? document node-id "click"))
                focusable? (focusable-control? document node-id)
                checkbox? (and (not disabled?)
                               (checkbox-control? document node-id))
                radio? (and (not disabled?)
                            (radio-control? document node-id))
                checked? (when checkbox?
                           (not (truthy-attr? (get-in document [:nodes node-id :attrs :checked]))))
                radio-changed? (and radio?
                                    (not (truthy-attr? (get-in document [:nodes node-id :attrs :checked]))))
                details-id (and (not disabled?) (details-parent-id document node-id))
                details-open? (when details-id
                                (not (truthy-attr? (get-in document [:nodes details-id :attrs :open]))))
                previous-focus (:focus document)
                document (cond-> (if focusable?
                                   (focus-editable document node-id)
                                   (blur-focused document nil))
                           checkbox? (dom/set-attribute node-id :checked checked?)
                           checkbox? (clear-node-validation-state node-id)
                           checkbox? (dom/dispatch-event node-id "input" (checked-event node-id "input" checked?))
                           checkbox? (dom/dispatch-event node-id "change" (checked-event node-id "change" checked?))
                           radio-changed? (as-> d
                                            (reduce #(dom/set-attribute %1 %2 :checked (= %2 node-id))
                                                    d
                                                    (radio-group-node-ids d node-id)))
                           radio-changed? (clear-node-validation-state node-id)
                           radio-changed? (dom/dispatch-event node-id "input" (checked-event node-id "input" true))
                           radio-changed? (dom/dispatch-event node-id "change" (checked-event node-id "change" true))
                           details-id (dom/set-attribute details-id :open details-open?)
                           details-id (dom/dispatch-event details-id "toggle" (toggle-event details-id details-open?))
                           click-listener? (dispatch-pointer-event node-id "click" event))
                submit-result (when (and (not disabled?)
                                         (submit-control? document node-id))
                                (apply-submit-default-action document node-id event))
                reset-result (when (and (not disabled?)
                                        (reset-control? document node-id))
                               (apply-reset-default-action (or (:document submit-result) document)
                                                           node-id
                                                           event))
                document (or (:document reset-result)
                             (:document submit-result)
                             document)]
            (cond-> {:document document
                     :node/id node-id
                     :event event
                     :handled? (boolean (or click-listener?
                                            focusable?
                                            checkbox?
                                            radio?
                                            details-id
                                            (:submitted? submit-result)
                                            (:invalid? submit-result)
                                            (:reset? reset-result)
                                            (and previous-focus (not= previous-focus (:focus document)))))
                     :focused? focusable?
                     :checked? (cond
                                 checkbox? checked?
                                 radio? true
                                 :else nil)}
              (:submitted? submit-result) (assoc :form/id (:form/id submit-result)
                                                 :form/data (:form/data submit-result)
                                                 :submitted? true)
              (:invalid? submit-result) (assoc :form/id (:form/id submit-result)
                                               :invalid/control-ids (:invalid/control-ids submit-result)
                                               :invalid? true)
              (:reset? reset-result) (assoc :form/id (:form/id reset-result)
                                            :reset? true)))))))))

(defn- text-input-submit-event?
  [document node-id event]
  (let [node (get-in document [:nodes node-id])]
    (and (= :key/down (:event/type event))
         (= "Enter" (:key event))
         (= :input (:tag node))
         (editable-node? document node-id)
         (ancestor-form-id document node-id)
         (not (composing? document node-id)))))

(defn- reduce-scroll-event
  [document node-id event]
  (let [node (get-in document [:nodes node-id])
        delta-x (parse-int (:delta-x event) 0)
        delta-y (parse-int (:delta-y event) 0)
        scroll-left (max 0 (+ (parse-int (or (attr node :scroll-left)
                                             (style node :scroll-left))
                                         0)
                              delta-x))
        scroll-top (max 0 (+ (parse-int (or (attr node :scroll-top)
                                            (style node :scroll-top))
                                        0)
                             delta-y))
        document (-> document
                     (dom/set-attribute node-id :scroll-left scroll-left)
                     (dom/set-attribute node-id :scroll-top scroll-top)
                     (dom/dispatch-event node-id "scroll"
                                         (scroll-event node-id scroll-left scroll-top
                                                       delta-x delta-y)))]
    {:document document
     :node/id node-id
     :event event
     :scroll/left scroll-left
     :scroll/top scroll-top
     :handled? true}))

(defn- reduce-file-select
  [document node-id event]
  (let [node (get-in document [:nodes node-id])
        files (selected-files event)]
    (if (and (file-input-control? node)
             (not (disabled-control? document node-id)))
      (let [value (str/join ", " (map :file/name files))
            document (-> document
                         (dom/set-attribute node-id :value value)
                         (dom/set-attribute node-id :files files)
                         (clear-node-validation-state node-id)
                         (dom/dispatch-event node-id "input" (file-select-event node-id files))
                         (dom/dispatch-event node-id "change" (file-select-event node-id files)))]
        {:document document
         :node/id node-id
         :event event
         :files files
         :handled? true})
      {:document document
       :node/id node-id
       :event event
       :handled? false})))

(defn reduce-event
  [document event]
  (let [event (input/normalize-event event)
        node-id (target-node-id document event)
        keydown? (and (= :key/down (:event/type event)) node-id)
        before-key-count (count (:ops document))
        document (if keydown?
                   (dispatch-key-event document node-id "keydown" event)
                   document)
        key-dispatched? (< before-key-count (count (:ops document)))]
    (cond
      (and (= :file/select (:event/type event))
           node-id)
      (reduce-file-select document node-id event)

      (and (= :pointer/move (:event/type event))
           node-id)
      (reduce-pointer-move-event document node-id event)

      (and (= :pointer/down (:event/type event))
           node-id)
      (reduce-pointer-button-event document node-id event "pointerdown")

      (and (= :pointer/up (:event/type event))
           node-id)
      (reduce-pointer-button-event document node-id event "pointerup")

      (and (= :pointer/cancel (:event/type event))
           node-id)
      (reduce-pointer-button-event document node-id event "pointercancel")

      (and (= :pointer/wheel (:event/type event))
           node-id
           (scrollable-node? document node-id))
      (reduce-scroll-event document node-id event)

      (and (= :pointer/click (:event/type event))
           node-id)
      (reduce-click-event document node-id event)

      (and (= :select/change (:event/type event))
           node-id
           (select-control? document node-id))
      (reduce-select-change document
                            node-id
                            (option-id-for-change document node-id event)
                            event)

      (and (= :key/up (:event/type event))
           node-id)
      (reduce-key-listener-event document node-id event "keyup")

      (and (activation-key? event)
           node-id
           (activatable-control? document node-id))
      (reduce-click-event document node-id (assoc event :event/type :pointer/click))

      (and (link-activation-key? event)
           node-id
           (closest-link-id document node-id))
      (reduce-click-event document node-id (assoc event :event/type :pointer/click))

      (and node-id
           (text-input-submit-event? document node-id event))
      (let [submit-result (apply-submit-default-action document node-id event)]
        (cond-> {:document (:document submit-result)
                 :node/id node-id
                 :event event
                 :form/id (:form/id submit-result)
                 :handled? true}
          (:submitted? submit-result) (assoc :form/data (:form/data submit-result)
                                             :submitted? true)
          (:invalid? submit-result) (assoc :invalid/control-ids (:invalid/control-ids submit-result)
                                           :invalid? true)))

      (not (and node-id (editable-node? document node-id)))
      {:document document
       :node/id node-id
       :event event
       :handled? key-dispatched?
       :commit? key-dispatched?}

      (and (readonly-control? document node-id)
           (readonly-mutation-event? event))
      {:document document
       :node/id node-id
       :event event
       :handled? key-dispatched?
       :commit? key-dispatched?
       :readonly? true}

      :else
      (let [node (get-in document [:nodes node-id])
            state (state-from-node node)
            beforeinput-type (beforeinput-type event)
            document (if beforeinput-type
                       (dispatch-beforeinput-event document node-id event state beforeinput-type)
                       document)
            next-state (case (:event/type event)
                         :text/input
                         (text-edit/insert-text state (:text event))

                         :key/down
                         (case (:key event)
                           "Backspace" (text-edit/delete-backward state)
                           "Delete" (text-edit/delete-forward state)
                           "ArrowLeft" (text-edit/move-caret state -1 {:extend? (:shift? event)})
                           "ArrowRight" (text-edit/move-caret state 1 {:extend? (:shift? event)})
                           "Home" (text-edit/move-to state 0 {:extend? (:shift? event)})
                           "End" (text-edit/move-to state (count (:text/value state "")) {:extend? (:shift? event)})
                           "a" (if (or (:meta? event) (:ctrl? event))
                                 (text-edit/select-all state)
                                 state)
                           "A" (if (or (:meta? event) (:ctrl? event))
                                 (text-edit/select-all state)
                                 state)
                           state)

                         :text/caret
                         (text-edit/collapse state (:caret event))

                         :text/selection
                         (text-edit/select state (:start event) (:end event))

                         :composition/start
                         (text-edit/composition-start state)

                         :composition/update
                         (text-edit/composition-update state (:text event))

                         :composition/end
                         (text-edit/composition-end state (:text event))

                         state)
            value-changed? (not= (:text/value state) (:text/value next-state))
            document (cond-> (apply-state document node-id next-state)
                       value-changed? (dom/set-attribute node-id :dirty-value true))
            document (if (contains? #{:text/input :composition/end} (:event/type event))
                       (clear-node-validation-state document node-id)
                       document)
            document (case (:event/type event)
                       :text/input (dom/dispatch-event document node-id "input" (input-event node-id next-state))
                       :composition/start (dom/dispatch-event document node-id "compositionstart"
                                                              (composition-event node-id "compositionstart" next-state))
                       :composition/update (dom/dispatch-event document node-id "compositionupdate"
                                                               (composition-event node-id "compositionupdate" next-state))
                       :composition/end (-> document
                                            (dom/dispatch-event node-id "compositionend"
                                                                (composition-event node-id "compositionend" next-state))
                                            (dom/dispatch-event node-id "input"
                                                                (input-event node-id next-state)))
                       document)]
        {:document document
         :node/id node-id
         :event event
         :state next-state
         :handled? true}))))
