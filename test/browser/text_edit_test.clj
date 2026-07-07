(ns browser.text-edit-test
  (:require [browser.text-edit :as text-edit]
            [clojure.test :refer [deftest is]]))

(deftest caret-selection-and-composition-edit-text
  (let [state (-> (text-edit/empty-state)
                  (text-edit/insert-text "koba")
                  (text-edit/collapse 2)
                  (text-edit/insert-text "to")
                  (text-edit/select 0 2)
                  (text-edit/insert-text "KO")
                  (text-edit/composition-start)
                  (text-edit/composition-update "ba")
                  (text-edit/composition-end "BA"))]
    (is (= "KOBAtoba" (:text/value state)))
    (is (= 4 (:text/caret state)))
    (is (nil? (:text/composition state)))))

(deftest forward-delete-home-end-shift-and-select-all-edit-text
  (let [state (-> (text-edit/empty-state)
                  (text-edit/insert-text "abcdef")
                  (text-edit/move-to 3)
                  (text-edit/delete-forward)
                  (text-edit/move-to 0)
                  (text-edit/move-caret 2 {:extend? true})
                  (text-edit/insert-text "XY")
                  (text-edit/move-to (count "XYdef"))
                  (text-edit/move-caret -2 {:extend? true})
                  (text-edit/delete-backward))]
    (is (= "XYc" (:text/value state)))
    (is (= 3 (:text/caret state)))
    (is (= [3 3] (:text/selection state))))
  (let [state (-> (text-edit/empty-state)
                  (text-edit/insert-text "kotoba")
                  (text-edit/select-all)
                  (text-edit/insert-text "aiueos"))]
    (is (= "aiueos" (:text/value state)))
    (is (= [6 6] (:text/selection state)))))

;; A real emoji like 😀 is TWO UTF-16 code units (a surrogate pair), not
;; one -- before this fix, every caret-moving/deleting fn below indexed by
;; raw code unit with zero surrogate-pair awareness, so landing/deleting
;; mid-pair produced a value containing a lone, invalid surrogate (an
;; unpaired 0xD800-0xDBFF or 0xDC00-0xDFFF code unit -- not a real
;; character at all).

(def ^:private emoji "😀")

(deftest backspace-after-an-emoji-deletes-the-whole-surrogate-pair
  (let [state (-> (text-edit/empty-state)
                  (text-edit/insert-text (str "a" emoji "b"))
                  (text-edit/move-to 3)
                  (text-edit/delete-backward))]
    (is (= "ab" (:text/value state))
        "the whole 2-code-unit emoji is removed, not just its low surrogate")
    (is (= 1 (:text/caret state)))))

(deftest delete-forward-before-an-emoji-deletes-the-whole-surrogate-pair
  (let [state (-> (text-edit/empty-state)
                  (text-edit/insert-text (str "a" emoji "b"))
                  (text-edit/move-to 1)
                  (text-edit/delete-forward))]
    (is (= "ab" (:text/value state))
        "the whole 2-code-unit emoji is removed, not just its high surrogate")
    (is (= 1 (:text/caret state)))))

(deftest arrow-right-steps-over-a-whole-surrogate-pair-not-into-its-middle
  (let [state (-> (text-edit/empty-state)
                  (text-edit/insert-text (str "a" emoji "b"))
                  (text-edit/move-to 1)
                  (text-edit/move-caret 1))]
    (is (= 3 (:text/caret state))
        "lands right after the whole pair (position 2 would split it)")))

(deftest arrow-left-steps-over-a-whole-surrogate-pair-not-into-its-middle
  (let [state (-> (text-edit/empty-state)
                  (text-edit/insert-text (str "a" emoji "b"))
                  (text-edit/move-to 3)
                  (text-edit/move-caret -1))]
    (is (= 1 (:text/caret state))
        "lands right before the whole pair (position 2 would split it)")))

(deftest shift-arrow-extending-a-selection-also-skips-over-a-surrogate-pair-boundary
  (let [state (-> (text-edit/empty-state)
                  (text-edit/insert-text (str "a" emoji "b"))
                  (text-edit/move-to 1)
                  (text-edit/move-caret 1 {:extend? true}))]
    (is (= [1 3] (:text/selection state)))
    (is (= 3 (:text/caret state)))))

(deftest surrogate-pair-fix-does-not-affect-plain-ascii-editing
  (let [state (-> (text-edit/empty-state) (text-edit/insert-text "hello"))]
    (is (= "hell" (:text/value (text-edit/delete-backward state))))
    (is (= "ello" (:text/value (text-edit/delete-forward (text-edit/move-to state 0)))))
    (is (= 4 (:text/caret (text-edit/move-caret state -1))))))
