(ns browser.page-script
  "Script discovery for kotoba browser pages."
  (:require [browser.origin :as origin]
            [clojure.string :as str]))

(def script-source-storage-key :quickjs.script/source)

(defn- node
  [document id]
  (get-in document [:nodes id]))

(defn- text-content
  [document id]
  (let [n (node document id)]
    (case (:node/type n)
      :text (:text n)
      :element (str/join "" (map #(text-content document %) (:children n)))
      "")))

(defn script-nodes
  [document]
  (->> (:nodes document)
       vals
       (filter #(and (= :element (:node/type %))
                     (= :script (:tag %))))
       (sort-by :node/id)
       vec))

(defn script-type
  [script-node]
  (let [type (some-> script-node :attrs :type str str/lower-case)]
    (if (= "module" type) :module :classic)))

(defn executable-script
  [document page-url script-node]
  (let [attrs (:attrs script-node)
        src (:src attrs)
        type (script-type script-node)
        source (when-not src
                 (str/trim (text-content document (:node/id script-node))))]
    (when (or src (seq source))
      {:script/type type
       :script/source source
       :script/src src
       :script/url (or src page-url)
       :script/node-id (:node/id script-node)
       :script/attrs attrs})))

(defn executable-scripts
  [page]
  (let [document (:browser/document page)]
    (vec
     (keep #(executable-script document (:browser/url page) %)
           (script-nodes document)))))

(defn absolute-url?
  [url]
  (some? (:scheme (origin/parse-url url))))

(defn directory-url
  [url]
  (let [{:keys [scheme authority path]} (origin/parse-url url)]
    (cond
      authority
      (let [idx (.lastIndexOf ^String path "/")
            dir (if (neg? idx) "/" (subs path 0 (inc idx)))]
        (str scheme "://" authority dir))

      scheme
      (let [idx (.lastIndexOf ^String path "/")
            dir (if (neg? idx) path (subs path 0 (inc idx)))]
        (str scheme ":" dir))

      :else "")))

(defn- strip-fragment
  [url]
  (let [url (str url)
        idx (.indexOf url "#")]
    (if (neg? idx) url (subs url 0 idx))))

(defn- strip-query-and-fragment
  [url]
  (let [url (strip-fragment url)
        idx (.indexOf ^String url "?")]
    (if (neg? idx) url (subs url 0 idx))))

(defn- query-fragment-suffix
  [url]
  (let [url (str url)
        query-idx (.indexOf ^String url "?")
        fragment-idx (.indexOf ^String url "#")
        idx (cond
              (and (not (neg? query-idx)) (not (neg? fragment-idx)))
              (min query-idx fragment-idx)

              (not (neg? query-idx)) query-idx
              (not (neg? fragment-idx)) fragment-idx
              :else -1)]
    (if (neg? idx) "" (subs url idx))))

(defn- normalize-path
  [path]
  (let [absolute? (str/starts-with? (str path) "/")
        trailing-slash? (and (> (count (str path)) 1)
                             (str/ends-with? (str path) "/"))
        segments (str/split (str path) #"/")
        normalized (reduce (fn [result segment]
                             (case segment
                               "" result
                               "." result
                               ".." (if (seq result) (pop result) result)
                               (conj result segment)))
                           []
                           segments)
        path (str/join "/" normalized)
        path (cond-> path
               (and trailing-slash? (seq path)) (str "/"))]
    (cond
      (and absolute? (seq path)) (str "/" path)
      absolute? "/"
      :else path)))

(defn- normalize-dot-segments
  [url]
  (let [{:keys [scheme authority path]} (origin/parse-url url)
        suffix (query-fragment-suffix url)
        path (strip-query-and-fragment path)]
    (cond
      authority
      (str scheme "://" authority (normalize-path path) suffix)

      scheme
      (str scheme ":" (normalize-path path) suffix)

      :else
      url)))

(defn- has-dot-segments?
  [url]
  (boolean (re-find #"(^|/)\.{1,2}(/|$|\?|#)" (str url))))

(defn resolve-src
  [page-url src]
  (let [src (str src)
        ;; RFC 3986 4.2: a relative-path reference whose first segment
        ;; contains a colon (e.g. "video:120/clip.js") is indistinguishable
        ;; from an absolute URI with scheme "video" -- callers must prefix
        ;; it with "./" to force relative-path interpretation. `dot-relative?`
        ;; remembers that the caller did so, so the colon in what's left
        ;; after stripping "./" is never mistaken for a scheme below (an
        ;; earlier version stripped the "./" and then still ran
        ;; `absolute-url?` on the remainder, silently defeating the very
        ;; disambiguation "./" exists to provide).
        dot-relative? (str/starts-with? src "./")
        src (if dot-relative? (subs src 2) src)]
    (cond
      (and (not dot-relative?) (absolute-url? src))
      (if (has-dot-segments? src)
        (normalize-dot-segments src)
        src)
      (str/starts-with? src "#")
      (str (strip-fragment page-url) src)
      (str/starts-with? src "?")
      (str (strip-query-and-fragment page-url) src)
      (str/starts-with? src "//")
      (let [{:keys [scheme]} (origin/parse-url page-url)]
        (normalize-dot-segments (str scheme ":" src)))
      (str/starts-with? src "/")
      (let [{:keys [scheme authority]} (origin/parse-url page-url)]
        (if authority
          (normalize-dot-segments (str scheme "://" authority src))
          (normalize-dot-segments (str scheme ":" src))))
      :else
      (normalize-dot-segments (str (directory-url page-url) src)))))
