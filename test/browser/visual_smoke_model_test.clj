(ns browser.visual-smoke-model-test
  (:require [browser.session :as session]
            [browser.visual-smoke-model :as model]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.wasm.host :as host]))

(deftest smoke-model-is-cljc-and-committable
  (let [h (host/recording-host)
        s (session/new-session {:host h
                                :viewport model/viewport
                                :surface (model/surface-model)})
        s (session/apply-surface-action! s (model/launch-action))
        s (session/load-html! s model/page)
        text (-> s :browser.session/page :browser/document kotoba.wasm.dom/text-content)
        recorded (host/recorded h)]
    (is (= [760 460] model/viewport))
    (is (str/includes? text "Browser document"))
    (is (str/includes? text "kotoba:dom committed"))
    (is (= 2 (:present-count recorded)))
    (is (= [:surface/commit :page/commit]
           (mapv :event (:browser.session/history s))))))
