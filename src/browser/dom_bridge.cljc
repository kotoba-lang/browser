(ns browser.dom-bridge
  "DOM query/mutation bridge used by compat runtimes."
  (:require [cssom.core :as css]
            [htmldom.core :as html]
            [kotoba.wasm.dom :as dom]))

(defn element-nodes
  [document]
  (letfn [(walk [node-id]
            (let [node (get-in document [:nodes node-id])]
              (when node
                (cons node
                      (mapcat walk (:children node))))))]
    (->> (walk (:root document))
         (filter #(= :element (:node/type %)))
         vec)))

(defn query-selector-all
  [document selector]
  (let [selectors (->> (css/split-selector-list selector)
                       (map css/parse-selector)
                       vec)]
    (->> (element-nodes document)
         (filter #(some (fn [selector]
                          (css/matches? document % selector))
                        selectors))
         (mapv :node/id))))

(defn query-selector
  [document selector]
  (first (query-selector-all document selector)))

(defn get-element-by-id
  [document id]
  (query-selector document (str "#" id)))

(defn title-node-id
  [document]
  (query-selector document "title"))

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

(defn- node-text
  [document node-id]
  (letfn [(walk [id]
            (let [node (get-in document [:nodes id])]
              (case (:node/type node)
                :text (:text node)
                :element (apply str (map walk (:children node)))
                "")))]
    (walk node-id)))

(defn document-title
  [document]
  (if-let [title-id (title-node-id document)]
    (node-text document title-id)
    ""))

(defn document-base-href
  [document]
  (when-let [base-id (query-selector document "base")]
    (get-in document [:nodes base-id :attrs :href])))

(defn- ensure-head-node
  [document]
  (if-let [head-id (query-selector document "head")]
    [head-id document]
    (let [[head-id document] (dom/create-element document :head)]
      [head-id (dom/append-child document (:root document) head-id)])))

(defn set-document-title
  [document title]
  (let [title (str title)
        [title-id document] (if-let [title-id (title-node-id document)]
                              [title-id document]
                              (let [[head-id document] (ensure-head-node document)
                                    [title-id document] (dom/create-element document :title)]
                                [title-id (dom/append-child document head-id title-id)]))
        document (dom/remove-children document title-id)
        [text-id document] (dom/create-text-node document title)]
    (-> document
        (dom/append-child title-id text-id)
        (assoc :title title))))

(defn node-snapshot
  ([document node-id]
   (node-snapshot document (parent-index document) node-id))
  ([document parents node-id]
   (when-let [node (get-in document [:nodes node-id])]
     (cond-> (select-keys node [:node/id :node/type :tag :attrs :children :text])
       (contains? parents node-id) (assoc :parent/id (get parents node-id))
       (= :element (:node/type node)) (assoc :text-content (node-text document node-id))))))

(defn document-snapshot
  [document]
  (let [parents (parent-index document)]
    {:root (:root document)
     :focus (:focus document)
     :url (or (:url document) "about:blank")
     :base-uri (or (:base-uri document) (:url document) "about:blank")
     :ready-state (or (:ready-state document) "complete")
     :title (document-title document)
     :nodes (into {}
                  (map (fn [[node-id _]]
                         [node-id (node-snapshot document parents node-id)]))
                  (:nodes document))}))

(defn add-event-listener
  [document node-id event-type handler-id]
  {:document (dom/add-event-listener document node-id event-type handler-id)
   :node/id node-id
   :handler/id handler-id})

(defn remove-event-listener
  [document node-id event-type handler-id]
  {:document (-> document
                 (update-in [:listeners node-id] dissoc (keyword event-type))
                 (update :ops conj [:dom/remove-event-listener node-id (keyword event-type) handler-id]))
   :node/id node-id
   :handler/id handler-id})

(defn dispatch-event
  [document node-id event]
  (let [event-type (or (:event/type event) (:type event))
        before-count (count (:ops document))
        document (dom/dispatch-event document node-id event-type event)]
    {:document document
     :node/id node-id
     :event/dispatched? (< before-count (count (:ops document)))}))

(defn- style-attr?
  [attr]
  (= :style (keyword attr)))

(defn- clear-style-attrs
  [document node-id]
  (update-in document [:nodes node-id :attrs]
             (fn [attrs]
               (into {}
                     (remove (fn [[k _]]
                               (or (= :style k)
                                   (= :style-inline k)
                                   (= "style" (namespace k)))))
                     attrs))))

(defn- set-style-attribute
  [document node-id value]
  (let [style (css/parse-declarations value)]
    (-> document
        (clear-style-attrs node-id)
        (dom/set-attribute node-id :style value)
        (dom/set-attribute node-id :style-inline style)
        (dom/set-style node-id style))))

(defn- remove-attribute
  [document node-id attr]
  (if (style-attr? attr)
    (clear-style-attrs document node-id)
    (update-in document [:nodes node-id :attrs] dissoc (keyword attr))))

(defn- create-fragment
  [document]
  (let [id (:next-id document)]
    [id (-> document
            (update :next-id inc)
            (assoc-in [:nodes id] {:node/id id
                                   :node/type :document-fragment
                                   :children []})
            (update :ops conj [:dom/create-fragment id]))]))

(defn- fragment-node?
  [document node-id]
  (= :document-fragment (get-in document [:nodes node-id :node/type])))

(defn- append-child-or-fragment
  [document parent-id child-id]
  (if (fragment-node? document child-id)
    (let [children (vec (get-in document [:nodes child-id :children] []))]
      {:document (assoc-in (reduce #(dom/append-child %1 parent-id %2)
                                   document
                                   children)
                           [:nodes child-id :children]
                           [])
       :node/id child-id})
    {:document (dom/append-child document parent-id child-id)
     :node/id child-id}))

(defn- insert-child-or-fragment-before
  [document parent-id child-id before-id]
  (if (fragment-node? document child-id)
    (let [children (vec (get-in document [:nodes child-id :children] []))]
      {:document (assoc-in (reduce #(dom/insert-before %1 parent-id %2 before-id)
                                   document
                                   children)
                           [:nodes child-id :children]
                           [])
       :node/id child-id})
    {:document (dom/insert-before document parent-id child-id before-id)
     :node/id child-id}))

(defn- clone-node*
  [document source-id deep?]
  (let [source (get-in document [:nodes source-id])]
    (case (:node/type source)
      :text
      (dom/create-text-node document (:text source))

      :element
      (let [[id document] (dom/create-element document (:tag source))
            document (reduce-kv #(dom/set-attribute %1 id %2 %3)
                                document
                                (or (:attrs source) {}))]
        [id (if deep?
              (reduce (fn [document child-id]
                        (let [[cloned-child-id document] (clone-node* document child-id true)]
                          (dom/append-child document id cloned-child-id)))
                      document
                      (:children source))
              document)])

      :document-fragment
      (let [[id document] (create-fragment document)]
        [id (if deep?
              (reduce (fn [document child-id]
                        (let [[cloned-child-id document] (clone-node* document child-id true)]
                          (dom/append-child document id cloned-child-id)))
                      document
                      (:children source))
              document)])

      [nil document])))

(defn- import-node*
  [document source-document source-id]
  (let [source (get-in source-document [:nodes source-id])]
    (case (:node/type source)
      :text
      (dom/create-text-node document (:text source))

      :element
      (let [[id document] (dom/create-element document (:tag source))
            document (reduce-kv #(dom/set-attribute %1 id %2 %3)
                                document
                                (or (:attrs source) {}))]
        [id (reduce (fn [document child-id]
                      (let [[imported-child-id document] (import-node* document source-document child-id)]
                        (dom/append-child document id imported-child-id)))
                    document
                    (:children source))])

      [nil document])))

(defn- set-inner-html
  [document node-id html-text]
  (let [fragment-document (html/parse-into-document html-text)
        fragment-root (:root fragment-document)
        children (vec (get-in fragment-document [:nodes fragment-root :children] []))
        document (dom/remove-children document node-id)]
    {:document (reduce (fn [document child-id]
                         (let [[imported-child-id document] (import-node* document fragment-document child-id)]
                           (dom/append-child document node-id imported-child-id)))
                       document
                       children)
     :node/id node-id}))

(defn- set-outer-html
  [document node-id html-text]
  (if-let [parent-id (get (parent-index document) node-id)]
    (let [fragment-document (html/parse-into-document html-text)
          fragment-root (:root fragment-document)
          children (vec (get-in fragment-document [:nodes fragment-root :children] []))
          document (reduce (fn [document child-id]
                             (let [[imported-child-id document] (import-node* document fragment-document child-id)]
                               (dom/insert-before document parent-id imported-child-id node-id)))
                           document
                           children)]
      {:document (dom/remove-child document parent-id node-id)
       :node/id node-id})
    {:document document
     :node/id node-id}))

(defn- split-text-node
  [document node-id offset]
  (let [text (str (get-in document [:nodes node-id :text] ""))
        offset (max 0 (min (count text) (long (or offset 0))))
        before (subs text 0 offset)
        after (subs text offset)
        parents (parent-index document)
        parent-id (get parents node-id)
        parent-children (get-in document [:nodes parent-id :children] [])
        idx (when parent-id (.indexOf (vec parent-children) node-id))
        next-id (when (and idx (not (neg? idx)))
                  (nth parent-children (inc idx) nil))
        document (-> document
                     (assoc-in [:nodes node-id :text] before)
                     (update :ops conj [:dom/set-text node-id before]))
        [new-id document] (dom/create-text-node document after)
        document (if parent-id
                   (dom/insert-before document parent-id new-id next-id)
                   document)]
    {:document document :node/id new-id}))

(defn- normalize-node
  [document node-id]
  (let [children (vec (get-in document [:nodes node-id :children] []))
        document (reduce (fn [document child-id]
                           (if (= :element (get-in document [:nodes child-id :node/type]))
                             (normalize-node document child-id)
                             document))
                         document
                         children)
        children (vec (get-in document [:nodes node-id :children] []))]
    (loop [document document
           result []
           children children
           active-text nil]
      (if-let [child-id (first children)]
        (let [node (get-in document [:nodes child-id])]
          (if (= :text (:node/type node))
            (let [text (str (:text node ""))]
              (cond
                (empty? text)
                (recur (dom/remove-child document node-id child-id)
                       result
                       (rest children)
                       active-text)

                active-text
                (let [merged (str (get-in document [:nodes active-text :text] "") text)]
                  (recur (-> document
                             (assoc-in [:nodes active-text :text] merged)
                             (update :ops conj [:dom/set-text active-text merged])
                             (dom/remove-child node-id child-id))
                         result
                         (rest children)
                         active-text))

                :else
                (recur document (conj result child-id) (rest children) child-id)))
            (recur document (conj result child-id) (rest children) nil)))
        document))))

(defn apply-mutation
  [document {:keys [dom/op tag attrs attr value text title html offset deep?] :as mutation}]
  (case op
    :create-element
    (let [[id document] (dom/create-element document tag)
          document (reduce-kv #(dom/set-attribute %1 id %2 %3)
                              document
                              (or attrs {}))]
      {:document document :node/id id})

    :create-text
    (let [[id document] (dom/create-text-node document text)]
      {:document document :node/id id})

    :set-text
    (let [node-id (:node/id mutation)]
      {:document (-> document
                     (assoc-in [:nodes node-id :text] (str text))
                     (update :ops conj [:dom/set-text node-id (str text)]))
       :node/id node-id})

    :set-title
    {:document (set-document-title document title)
     :title (str title)}

    :split-text
    (split-text-node document (:node/id mutation) offset)

    :create-fragment
    (let [[id document] (create-fragment document)]
      {:document document :node/id id})

    :clone-node
    (let [[id document] (clone-node* document (:source/id mutation) (boolean deep?))]
      {:document document :node/id id})

    :set-inner-html
    (set-inner-html document (:node/id mutation) html)

    :set-outer-html
    (set-outer-html document (:node/id mutation) html)

    :normalize
    (let [node-id (:node/id mutation)]
      {:document (normalize-node document node-id)
       :node/id node-id})

    :set-attribute
    (let [node-id (:node/id mutation)]
      {:document (if (style-attr? attr)
                   (set-style-attribute document node-id value)
                   (dom/set-attribute document node-id attr value))
       :node/id node-id})

    :remove-attribute
    (let [node-id (:node/id mutation)]
      {:document (remove-attribute document node-id attr)
       :node/id node-id})

    :append-child
    (let [parent-id (:parent/id mutation)
          child-id (:child/id mutation)]
      (append-child-or-fragment document parent-id child-id))

    :remove-child
    (let [parent-id (:parent/id mutation)
          child-id (:child/id mutation)]
      {:document (dom/remove-child document parent-id child-id)
       :node/id child-id})

    :insert-before
    (let [parent-id (:parent/id mutation)
          child-id (:child/id mutation)
          before-id (:before/id mutation)]
      (insert-child-or-fragment-before document parent-id child-id before-id))

    :remove-children
    (let [node-id (:node/id mutation)]
      {:document (dom/remove-children document node-id)
       :node/id node-id})

    :focus-node
    (let [node-id (:node/id mutation)]
      {:document (-> document
                     (assoc :focus node-id)
                     (update :ops conj [:dom/focus node-id]))
       :node/id node-id})

    :blur-node
    (let [node-id (:node/id mutation)]
      {:document (cond-> (update document :ops conj [:dom/blur node-id])
                   (= node-id (:focus document)) (assoc :focus nil))
       :node/id node-id})

    {:document document
     :error :dom/unknown-mutation
     :mutation mutation}))

(defn handle-request
  [document request]
  (case (:capability request)
    :dom/query
    (case (:dom/query request)
      :query-selector {:document document
                       :result (query-selector document (:selector request))}
      :query-selector-all {:document document
                           :result (query-selector-all document (:selector request))}
      :get-element-by-id {:document document
                          :result (get-element-by-id document (:id request))}
      :node {:document document
             :result (node-snapshot document (:node/id request))}
      :document {:document document
                 :result (document-snapshot document)}
      {:document document :error :dom/unknown-query})

    :dom/mutate
    (apply-mutation document request)

    :event/listen
    (add-event-listener document
                        (:node/id request)
                        (:event/type request)
                        (:handler/id request))

    :event/remove
    (remove-event-listener document
                           (:node/id request)
                           (:event/type request)
                           (:handler/id request))

    :event/dispatch
    (dispatch-event document
                    (:node/id request)
                    (:event request))

    {:document document :error :dom/unsupported-capability}))
