(ns browser.text-edit
  "Small text editing model for browser/OS surface text input.")

(defn empty-state
  []
  {:text/value ""
   :text/caret 0
   :text/selection [0 0]
   :text/composition nil})

(defn- clamp
  [n lo hi]
  (max lo (min hi (or n lo))))

(defn normalize-selection
  [state]
  (let [value (:text/value state "")
        n (count value)
        [a b] (or (:text/selection state) [(:text/caret state) (:text/caret state)])
        a (clamp a 0 n)
        b (clamp b 0 n)]
    (assoc state
           :text/caret (clamp (:text/caret state) 0 n)
           :text/selection [(min a b) (max a b)])))

(defn collapse
  [state caret]
  (let [caret (clamp caret 0 (count (:text/value state "")))]
    (assoc state :text/caret caret :text/selection [caret caret])))

(defn select
  [state start end]
  (-> state
      (assoc :text/selection [start end]
             :text/caret end)
      (normalize-selection)))

(defn insert-text
  [state text]
  (let [state (normalize-selection state)
        value (:text/value state "")
        [start end] (:text/selection state)
        text (str text)
        value (str (subs value 0 start) text (subs value end))
        caret (+ start (count text))]
    (assoc state
           :text/value value
           :text/caret caret
           :text/selection [caret caret]
           :text/composition nil)))

(defn delete-backward
  [state]
  (let [state (normalize-selection state)
        value (:text/value state "")
        [start end] (:text/selection state)]
    (cond
      (< start end)
      (insert-text state "")

      (pos? start)
      (let [caret (dec start)]
        (assoc state
               :text/value (str (subs value 0 caret) (subs value start))
               :text/caret caret
               :text/selection [caret caret]))

      :else state)))

(defn delete-forward
  [state]
  (let [state (normalize-selection state)
        value (:text/value state "")
        [start end] (:text/selection state)]
    (cond
      (< start end)
      (insert-text state "")

      (< end (count value))
      (assoc state
             :text/value (str (subs value 0 start) (subs value (inc end)))
             :text/caret start
             :text/selection [start start])

      :else state)))

(defn- selection-anchor
  [state]
  (let [state (normalize-selection state)
        caret (:text/caret state)
        [start end] (:text/selection state)]
    (if (<= caret start) end start)))

(defn move-caret
  ([state delta]
   (move-caret state delta {}))
  ([state delta {:keys [extend?]}]
   (let [state (normalize-selection state)
         value (:text/value state "")
         [start end] (:text/selection state)
         caret (:text/caret state)
         next-caret (clamp (if (and (not extend?) (< start end))
                             (if (neg? delta) start end)
                             (+ caret delta))
                           0
                           (count value))]
     (if extend?
       (select state (selection-anchor state) next-caret)
       (collapse state next-caret)))))

(defn move-to
  ([state caret]
   (move-to state caret {}))
  ([state caret {:keys [extend?]}]
   (let [state (normalize-selection state)
         caret (clamp caret 0 (count (:text/value state "")))]
     (if extend?
       (select state (selection-anchor state) caret)
       (collapse state caret)))))

(defn select-all
  [state]
  (select state 0 (count (:text/value state ""))))

(defn composition-start
  [state]
  (assoc state :text/composition {:composition/text ""}))

(defn composition-update
  [state text]
  (assoc state :text/composition {:composition/text (str text)}))

(defn composition-end
  [state text]
  (insert-text (assoc state :text/composition nil) text))
