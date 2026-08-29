(ns browser.compat.ecma262
  "The host side of the Kotoba ECMA-262 engine's DOM bridge.

  That engine (`kotoba-lang/org-ecma-international-262`) is pure: it has no
  capabilities and cannot touch a document. It reads from a SNAPSHOT the host
  injects and returns an EFFECT LOG the host replays. This namespace is the
  host half of that exchange -- build the snapshot, read the log, apply it.

      document ──snapshot──> engine ──effect log──> apply-effects ──> document'

  Nothing here evaluates JavaScript. That is the point: the authority to
  change the document never leaves this side of the line, which is why
  `browser.runtime/ecma262` declares `:imports #{}` while `quickjs` needs
  twelve.

  ## The wire format

  Both directions use the length-prefixed entry format the engine uses for its
  own environment, so neither side needs escaping:

      snapshot   <id>=s<len>:<text>          repeated, newest first
      effect     <len>:<op><len>:<id><len>:<value>   repeated, in order

  A registration nests one more level, because it carries two values:

      16:addEventListener3:btn10:5:click1:0
      └ op ────────────┘└ id ┘└ 5:click1:0 ┘
                               └ type ┘└ n ┘"
  (:require [browser.dom-bridge :as dom-bridge]
            [clojure.string :as str]))

(defn snapshot
  "Element id -> textContent, in the format the engine reads.

  Only elements WITH an id are included: the engine addresses nodes by id and
  has no other handle, so an element without one is not reachable from a page
  script anyway."
  [document]
  (reduce (fn [region node]
            ;; `:attrs` is keyed by keyword -- `dom-bridge/get-element-by-id`
              ;; reads `[:attrs :id]`, and the two must agree or the snapshot
              ;; names elements the host cannot find again.
              (if-let [id (get-in node [:attrs :id])]
              (let [text (or (:text-content (dom-bridge/node-snapshot document (:node/id node))) "")]
                (str id "=s" (count text) ":" text region))
              region))
          ""
          (dom-bridge/element-nodes document)))

(defn- read-field
  "One `<len>:<text>` field. Returns [text next-index], or nil if `s` does not
  hold a well-formed field at `i` -- a truncated log must not be read as an
  empty one."
  [s i]
  (let [colon (str/index-of s ":" i)]
    (when colon
      (when-let [n (parse-long (subs s i colon))]
        (let [start (inc colon)
              end (+ start n)]
          (when (<= end (count s))
            [(subs s start end) end]))))))

(defn parse-effects
  "The engine's log -> a vector of effects, in the order the script asked.

  Throws on a malformed log rather than returning what it managed to read: a
  half-read log and an empty one must not look the same, or a script whose
  effects were truncated would replay as a script that asked for nothing."
  [log]
  (loop [i 0 out []]
    (if (>= i (count log))
      out
      (let [[op i1] (or (read-field log i)
                        (throw (ex-info "malformed effect log: bad op field"
                                        {:log log :at i})))
            [id i2] (or (read-field log i1)
                        (throw (ex-info "malformed effect log: bad id field"
                                        {:log log :at i1 :op op})))
            [value i3] (or (read-field log i2)
                           (throw (ex-info "malformed effect log: bad value field"
                                           {:log log :at i2 :op op :id id})))]
        (recur i3
               (conj out
                     (if (= op "addEventListener")
                       (let [[type j] (or (read-field value 0)
                                          (throw (ex-info "malformed registration: bad type"
                                                          {:value value})))
                             [n _] (or (read-field value j)
                                       (throw (ex-info "malformed registration: bad handler number"
                                                       {:value value})))]
                         {:effect/op :add-event-listener
                          :element/id id
                          :event/type type
                          :handler/n (parse-long n)})
                       {:effect/op (case op
                                     "textContent" :set-text-content
                                     (keyword op))
                        :element/id id
                        :effect/value value})))))))

(defn- set-text-content
  "What a real `textContent` setter does: drop the children, then add one text
  node. `:set-text` alone would write an element's own `:text`, which
  `node-text` does not read for an element."
  [document node-id text]
  (let [document (:document (dom-bridge/apply-mutation
                             document {:dom/op :remove-children :node/id node-id}))
        {:keys [document node/id]} (dom-bridge/apply-mutation
                                    document {:dom/op :create-text :text text})]
    (:document (dom-bridge/apply-mutation
                document {:dom/op :append-child :parent/id node-id :child/id id}))))

(defn apply-effects
  "Replay the log against a real document.

  Returns {:document d :listeners [...] :unknown [...]}. An effect this host
  does not implement is REPORTED, never dropped: a log the host silently
  ignored is indistinguishable from a script that did nothing, which is the
  failure this codebase keeps finding in its own gates."
  [document effects]
  (reduce
   (fn [acc {:keys [effect/op element/id effect/value] :as fx}]
     (if-let [node-id (dom-bridge/get-element-by-id (:document acc) id)]
       (case op
         :set-text-content
         (update acc :document set-text-content node-id value)

         :add-event-listener
         (update acc :listeners conj (assoc fx :node/id node-id))

         (update acc :unknown conj fx))
       (update acc :unknown conj (assoc fx :reason :no-such-element))))
   {:document document :listeners [] :unknown []}
   effects))
