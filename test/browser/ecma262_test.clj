(ns browser.ecma262-test
  "The host half of the Kotoba engine's DOM bridge, against a real document.

  What is checked here is the HOST: build a snapshot a real engine can read,
  read a log a real engine wrote, and apply it to a real htmldom document.
  Whether the engine emits the right log is checked elsewhere, against
  quickjs-ng (`test/runtime-differential.cljs`) -- the two halves are measured
  separately so a bug in one cannot hide in the other."
  (:require [browser.compat.ecma262 :as ecma262]
            [browser.dom-bridge :as dom-bridge]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [htmldom.core :as html]))

(def ^:private page
  (str "<html><body>"
       "<div id=\"ws-proof\">pending</div>"
       "<div id=\"out\">before</div>"
       "<button id=\"btn\">go</button>"
       "<p>no id at all</p>"
       "</body></html>"))

(defn- doc [] (html/parse-into-document page))

(defn- text-of [document id]
  (:text-content (dom-bridge/node-snapshot document (dom-bridge/get-element-by-id document id))))

(deftest snapshot-names-every-element-that-has-an-id
  (let [region (ecma262/snapshot (doc))]
    (is (str/includes? region "ws-proof=s7:pending"))
    (is (str/includes? region "out=s6:before"))
    (is (str/includes? region "btn=s2:go"))
    ;; An element without an id is not addressable from a page script, so it
    ;; has no place in a snapshot keyed by id.
    (is (not (str/includes? region "no id at all")))))

(deftest parse-effects-reads-a-write
  (is (= [{:effect/op :set-text-content :element/id "ws-proof" :effect/value "done"}]
         (ecma262/parse-effects "11:textContent8:ws-proof4:done"))))

(deftest parse-effects-keeps-the-order-of-two-writes
  (is (= ["1" "2"]
         (mapv :effect/value
               (ecma262/parse-effects "11:textContent3:out1:111:textContent8:ws-proof1:2")))))

(deftest parse-effects-reads-a-registration
  (is (= [{:effect/op :add-event-listener :element/id "btn"
           :event/type "click" :handler/n 0}]
         (ecma262/parse-effects "16:addEventListener3:btn10:5:click1:0"))))

(deftest parse-effects-reads-an-empty-log-as-no-effects
  (is (= [] (ecma262/parse-effects ""))))

;; A truncated log must not read as a shorter one. Silently returning what
;; parsed would make "the script asked for two things and we lost one"
;; indistinguishable from "the script asked for one thing".
(deftest parse-effects-refuses-a-truncated-log
  (is (thrown? clojure.lang.ExceptionInfo
               (ecma262/parse-effects "11:textContent8:ws-proof9:short")))
  (is (thrown? clojure.lang.ExceptionInfo
               (ecma262/parse-effects "11:textContent"))))

(deftest apply-effects-sets-real-text-content
  (let [{:keys [document unknown]}
        (ecma262/apply-effects (doc) (ecma262/parse-effects "11:textContent8:ws-proof4:done"))]
    (is (= "done" (text-of document "ws-proof")))
    (is (= [] unknown))
    ;; The other elements are untouched.
    (is (= "before" (text-of document "out")))))

(deftest apply-effects-replaces-rather-than-appends
  (let [{:keys [document]}
        (ecma262/apply-effects (doc) (ecma262/parse-effects "11:textContent3:out1:X"))]
    (is (= "X" (text-of document "out")))))

(deftest apply-effects-replays-two-writes-in-order
  (let [{:keys [document]}
        (ecma262/apply-effects
         (doc) (ecma262/parse-effects "11:textContent3:out1:111:textContent3:out1:2"))]
    (is (= "2" (text-of document "out")))))

(deftest apply-effects-collects-listeners-instead-of-applying-them
  (let [{:keys [document listeners unknown]}
        (ecma262/apply-effects (doc) (ecma262/parse-effects "16:addEventListener3:btn10:5:click1:0"))]
    (is (= 1 (count listeners)))
    (is (= "click" (:event/type (first listeners))))
    (is (= 0 (:handler/n (first listeners))))
    (is (some? (:node/id (first listeners))))
    (is (= [] unknown))
    ;; Registering changes nothing in the document.
    (is (= "go" (text-of document "btn")))))

;; An effect naming an element that is not there is REPORTED, not dropped.
(deftest apply-effects-reports-an-unknown-element
  (let [{:keys [unknown]}
        (ecma262/apply-effects (doc) (ecma262/parse-effects "11:textContent7:missing1:x"))]
    (is (= 1 (count unknown)))
    (is (= :no-such-element (:reason (first unknown))))))

(deftest apply-effects-reports-an-op-this-host-does-not-implement
  (let [{:keys [unknown]}
        (ecma262/apply-effects (doc) (ecma262/parse-effects "9:innerHTML3:out4:<b>x"))]
    (is (= 1 (count unknown)))
    (is (= :innerHTML (:effect/op (first unknown))))))

;; The round trip: what the host hands the engine, and what it gets back,
;; describe the same element.
(deftest a-write-lands-on-the-element-the-snapshot-named
  (let [d (doc)
        region (ecma262/snapshot d)
        {:keys [document]} (ecma262/apply-effects
                            d (ecma262/parse-effects "11:textContent3:out5:after"))]
    (is (str/includes? region "out=s6:before"))
    (is (= "after" (text-of document "out")))
    (is (str/includes? (ecma262/snapshot document) "out=s5:after"))))
