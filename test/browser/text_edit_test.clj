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
