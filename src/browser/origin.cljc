(ns browser.origin
  "Small origin model for kotoba browser policy and storage.

   This is not a WHATWG URL parser. It is a deterministic subset for
   scheme://authority/path style URLs plus kotoba internal URLs."
  (:require [clojure.string :as str]))

(defn parse-url
  [url]
  (let [s (str url)
        [_ scheme rest] (re-matches #"^([A-Za-z][A-Za-z0-9+.-]*):(.*)$" s)]
    (if scheme
      (let [scheme (str/lower-case scheme)]
        (if (str/starts-with? rest "//")
          (let [without-slashes (subs rest 2)
                slash (.indexOf without-slashes "/")
                authority (if (neg? slash) without-slashes (subs without-slashes 0 slash))
                path (if (neg? slash) "/" (subs without-slashes slash))]
            {:url s
             :scheme scheme
             :authority (str/lower-case authority)
             :path path
             :origin (str scheme "://" (str/lower-case authority))})
          {:url s
           :scheme scheme
           :path rest
           :origin (str scheme ":")}))
      {:url s
       :scheme nil
       :path s
       :origin "opaque:"})))

(defn origin
  [url]
  (:origin (parse-url url)))

(defn same-origin?
  [a b]
  (= (origin a) (origin b)))

(defn internal?
  [url]
  (= "kotoba" (:scheme (parse-url url))))
