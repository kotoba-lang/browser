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

(deftest parse-url-strips-userinfo-from-authority
  ;; RFC 6454: an origin is scheme://host[:port] -- userinfo ("user@" or
  ;; "user:pass@") is never part of it. A URL shaped like
  ;; "https://victim.com@attacker.com/" is a real, network-reachable shape
  ;; (a malicious or compromised server can return it verbatim in a
  ;; redirect Location header) -- without stripping userinfo, this engine's
  ;; origin model would compute the origin as
  ;; "https://victim.com@attacker.com" instead of the real
  ;; "https://attacker.com", silently breaking any cookie/permission-store
  ;; lookup keyed by host for such a URL.
  (is (= "https://attacker.com"
         (origin/origin "https://victim.com@attacker.com/steal")))
  (is (= "attacker.com"
         (:authority (origin/parse-url "https://victim.com@attacker.com/steal"))))
  (is (origin/same-origin? "https://attacker.com/page"
                           "https://victim.com@attacker.com/steal"))
  (is (not (origin/same-origin? "https://victim.com/page"
                                "https://victim.com@attacker.com/steal")))
  ;; userinfo may itself contain "@" (e.g. a percent-decoded value) -- the
  ;; LAST "@" before the path always separates userinfo from host, per
  ;; WHATWG URL's authority-parsing state.
  (is (= "https://host.example"
         (origin/origin "https://user@sub@host.example/path"))))
