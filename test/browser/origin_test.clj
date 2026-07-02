(ns browser.origin-test
  (:require [browser.origin :as origin]
            [clojure.test :refer [deftest is]]))

(deftest origin-normalizes-scheme-and-authority
  (is (= "https://example.com"
         (origin/origin "HTTPS://Example.Com/docs?q=1")))
  (is (= "kotoba://shell"
         (origin/origin "kotoba://shell/apps")))
  (is (= "mailto:"
         (origin/origin "mailto:jun@example.com")))
  (is (= "opaque:"
         (origin/origin "/relative/path"))))

(deftest same-origin-compares-normalized-origin
  (is (origin/same-origin? "https://Example.com/a" "https://example.com/b"))
  (is (not (origin/same-origin? "https://example.com/a" "http://example.com/a")))
  (is (origin/internal? "kotoba://shell")))
