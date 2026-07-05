(ns browser.session-test
  (:require [browser.account :as account]
            [browser.audit :as audit]
            [browser.compat.quickjs-runner :as quickjs-runner]
            [browser.core :as browser]
            [browser.dom-bridge :as bridge]
            [browser.net :as net]
            [browser.persistence-provider :as persistence-provider]
            [browser.profile :as profile]
            [browser.session :as session]
            [browser.storage :as storage]
            [browser.surface :as surface]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [kotoba.wasm.dom :as dom]
            [kotoba.wasm.host :as host]))

(deftest load-html-commits-page-ops-to-host
  (let [h (host/recording-host)
        s (session/new-session {:host h})
        s (session/load-html! s {:url "kotoba://hello"
                                 :html "<main><p>Hello</p></main>"})
        recorded (host/recorded h)]
    (is (= "Hello" (-> s :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= 1 (:present-count recorded)))
    (is (seq (:ops recorded)))
    (is (= 1 (get-in s [:browser.session/last-batch :abi/version])))
    (is (= (count (:ops recorded))
           (count (get-in s [:browser.session/last-batch :ops]))))
    (is (= :page/commit (-> s :browser.session/history last :event)))))

(deftest navigate-commits-only-successful-pages
  (let [h (host/recording-host)
        calls (atom [])
        s (session/new-session
           {:host h
            :fetch-fn (fn [req]
                        (swap! calls conj req)
                        (if (= "kotoba://ok" (:url req))
                          {:status 200 :body "<main>OK</main>"}
                          {:status 500 :body "<main>NO</main>"}))})
        ok (session/navigate! s "kotoba://ok")
        after-ok (host/recorded h)
        failed (session/navigate! ok "kotoba://fail")
        after-fail (host/recorded h)]
    (is (= [{:url "kotoba://ok" :method :get}
            {:url "kotoba://fail" :method :get}]
           @calls))
    (is (= "OK" (-> ok :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= 1 (:present-count after-ok)))
    (is (= 1 (:present-count after-fail)) "failed navigation does not commit stale/error HTML")
    (is (= :navigation/http-error (:browser.session/error failed)))
    (is (= :navigation/error (-> failed :browser.session/history last :event)))))

(deftest same-document-fragment-navigation-updates-history-without-fetch-or-render
  (let [h (host/recording-host)
        calls (atom [])
        s (session/new-session
           {:host h
            :fetch-fn (fn [req]
                        (swap! calls conj req)
                        {:status 200 :body "<main><h1 id=\"top\">Top</h1><section id=\"details\">Details</section></main>"})})
        page (session/navigate! s "kotoba://doc")
        generation (:browser.session/page-generation page)
        fragment (session/navigate! page "kotoba://doc#details")
        recorded (host/recorded h)
        back (session/back! fragment)]
    (is (= [{:url "kotoba://doc" :method :get}]
           @calls))
    (is (= 1 (:present-count recorded))
        "same-document fragment navigation does not re-render or refetch the document")
    (is (= generation (:browser.session/page-generation fragment)))
    (is (= "kotoba://doc#details"
           (get-in fragment [:browser.session/page :browser/url])))
    (is (= ["kotoba://doc" "kotoba://doc#details"]
           (mapv :url (get-in fragment [:browser.session/navigation :entries]))))
    (is (= [:page/commit :navigation/fragment]
           (mapv :event (:browser.session/history fragment))))
    (is (= "TopDetails"
           (-> fragment :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= "kotoba://doc"
           (get-in back [:browser.session/page :browser/url])))))

(deftest surface-actions-commit-os-ui-to-host
  (let [h (host/recording-host)
        os (-> (surface/empty-surface {:title "Kotoba OS"})
               (surface/register-app {:app/id "term"
                                      :app/title "Terminal"
                                      :app/document [:main [:p "$ kotoba"]]}))
        s (session/new-session {:host h :surface os})
        s (session/apply-surface-action! s {:action :app/launch :app-id "term"})
        recorded (host/recorded h)]
    (is (= 1 (:present-count recorded)))
    (is (= "$ kotoba" (-> s :browser.session/surface :surface/windows first :window/document second second)))
    (is (= :surface/commit (-> s :browser.session/history last :event)))))

(deftest input-events-reduce-and-commit-os-ui-to-host
  (let [h (host/recording-host)
        os (-> (surface/empty-surface {:title "Kotoba OS"})
               (surface/open-window {:app-id "editor" :title "Editor" :rect [20 20 200 120]}))
        s (session/new-session {:host h :surface os})
        s (session/apply-input-event! s {:capability "keyboard/type" :text "kotoba"})
        recorded (host/recorded h)]
    (is (= 1 (:present-count recorded)))
    (is (= "kotoba" (-> s :browser.session/surface :surface/windows first :window/text-buffer)))
    (is (= :input/reduce (-> s :browser.session/history last :event)))))

(deftest os-surface-state-restores-and-resumes-through-provider
  (let [h (host/recording-host)
        provider (persistence-provider/memory-provider)
        os (-> (surface/empty-surface {:title "Kotoba OS"})
               (surface/open-window {:app-id "editor"
                                     :title "Editor"
                                     :rect [20 20 200 120]}))
        saved (-> (session/new-session {:host h
                                        :surface os
                                        :persistence-provider provider})
                  (session/apply-input-event! {:capability "keyboard/type"
                                               :text "persisted"})
                  (session/apply-surface-action! {:action :window/move
                                                  :window-id "w1"
                                                  :position [44 55]}))
        restored (session/new-session {:host h
                                       :persistence-provider provider})
        resumed (session/resume-current-surface! restored)
        window (-> resumed :browser.session/surface :surface/windows first)
        recorded (host/recorded h)]
    (is (= "persisted" (:window/text-buffer window)))
    (is (= [44 55 200 120] (:window/rect window)))
    (is (= "w1" (get-in resumed [:browser.session/surface :surface/focus])))
    (is (= "persisted"
           (-> restored :browser.session/surface :surface/windows first :window/text-buffer)))
    (is (= :surface/resume (-> resumed :browser.session/history last :event)))
    (is (= 3 (:present-count recorded)))
    (is (seq (get-in resumed [:browser.session/last-batch :ops])))
    (is (= (:browser.session/surface saved)
           (:browser.session/surface restored)))))

(deftest document-input-events-update-page-document-and-commit
  (let [h (host/recording-host)
        s (-> (session/new-session {:host h})
              (session/load-html! {:url "kotoba://form"
                                   :html "<main><input id=\"field\" value=\"hi\"></main>"})
              (session/apply-document-input-event! {:event/type :text/input
                                                    :node/selector "#field"
                                                    :text "!"}))
        field (bridge/query-selector (get-in s [:browser.session/page :browser/document]) "#field")
        recorded (host/recorded h)]
    (is (= "hi!" (get-in s [:browser.session/page :browser/document :nodes field :attrs :value])))
    (is (= 2 (:present-count recorded)))
    (is (= :document/input (-> s :browser.session/history last :event)))
    (is (= true (-> s :browser.session/history last :handled?)))))

(deftest document-text-change-dispatches-on-blur-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://change"
                                        :html "<main><input id=\"field\" value=\"old\"><button id=\"next\">Next</button></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        field (bridge/query-selector document "#field")
        next (bridge/query-selector document "#next")
        loaded (update-in loaded [:browser.session/page :browser/document]
                          #(-> %
                               (dom/add-event-listener field "change" 101)
                               (dom/add-event-listener field "blur" 102)
                               (dom/add-event-listener next "focus" 103)
                               (assoc :ops [])))
        focused (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                             :node/selector "#field"})
        typed (session/apply-document-input-event! focused {:event/type :text/input
                                                            :node/selector "#field"
                                                            :text "!"})
        blurred (session/apply-document-input-event! typed {:event/type :pointer/click
                                                            :node/selector "#next"})
        document (get-in blurred [:browser.session/page :browser/document])
        recorded (host/recorded h)]
    (is (= "old!" (get-in document [:nodes field :attrs :value])))
    (is (= false (get-in document [:nodes field :attrs :dirty-value])))
    (is (= next (:focus document)))
    (is (= 4 (:present-count recorded)))
    (is (= [[:dom/dispatch-event
             101
             {:event/type "change"
              :target/id field
              :value "old!"}]
            [:dom/dispatch-event
             102
             {:event/type "blur"
              :target/id field}]
            [:dom/dispatch-event
             103
             {:event/type "focus"
              :target/id next}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in blurred [:browser.session/document-input-result :document :ops]))))))

(deftest document-wheel-events-update-scroll-container-and-commit
  (let [h (host/recording-host)
        s (-> (session/new-session {:host h})
              (session/load-html! {:url "kotoba://scroll"
                                   :html "<main><section id=\"pane\" overflow=\"auto\" scroll-top=\"8\" scroll-left=\"2\"><p>Scrolled</p></section></main>"})
              (session/apply-document-input-event! {:event/type :pointer/wheel
                                                    :node/selector "#pane"
                                                    :delta-x 3
                                                    :delta-y 12}))
        document (get-in s [:browser.session/page :browser/document])
        pane (bridge/query-selector document "#pane")
        attrs (get-in document [:nodes pane :attrs])
        node-op (some #(when (and (= :node (:draw/op %))
                                  (= pane (:id %)))
                         %)
                      (get-in s [:browser.session/page :browser/draw-ops]))
        recorded (host/recorded h)]
    (is (= 5 (:scroll-left attrs)))
    (is (= 20 (:scroll-top attrs)))
    (is (= 5 (:scroll-left node-op)))
    (is (= 20 (:scroll-top node-op)))
    (is (= 2 (:present-count recorded)))
    (is (= :document/input (-> s :browser.session/history last :event)))
    (is (= true (-> s :browser.session/history last :handled?)))))

(deftest document-wheel-events-resolve-scroll-container-from-page-coordinates
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://scroll"
                                        :html "<main><section id=\"outer\" overflow=\"auto\" style=\"height: 80px; width: 220px\"><p>outer</p><section id=\"inner\" overflow=\"auto\" scroll-top=\"4\" style=\"height: 30px; width: 160px\"><p>inner</p></section></section></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        outer (bridge/query-selector document "#outer")
        inner (bridge/query-selector document "#inner")
        inner-op (some #(when (and (= :node (:draw/op %))
                                   (= inner (:id %)))
                          %)
                       (:browser/draw-ops page))
        s (session/apply-document-input-event! loaded {:event/type :pointer/wheel
                                                       :x (+ (:x inner-op) 2)
                                                       :y (+ (:y inner-op) 2)
                                                       :delta-y 9})
        document (get-in s [:browser.session/page :browser/document])]
    (is (= 13 (get-in document [:nodes inner :attrs :scroll-top])))
    (is (nil? (get-in document [:nodes outer :attrs :scroll-top])))
    (is (= inner (-> s :browser.session/history last :node/id)))
    (is (= true (-> s :browser.session/history last :handled?)))))

(deftest document-pointer-move-resolves-hover-target-from-page-coordinates
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://hover"
                                        :html "<main><button id=\"run\">Run</button></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        button (bridge/query-selector document "#run")
        document (-> document
                     (dom/add-event-listener button "pointerover" 50)
                     (dom/add-event-listener button "mouseover" 51)
                     (dom/add-event-listener button "pointerenter" 54)
                     (dom/add-event-listener button "mouseenter" 55)
                     (dom/add-event-listener button "pointermove" 53)
                     (dom/add-event-listener button "mousemove" 52))
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        button-op (some #(when (and (= :node (:draw/op %))
                                    (= button (:id %)))
                           %)
                        (:browser/draw-ops page))
        x (+ (:x button-op) 2)
        y (+ (:y button-op) 2)
        moved (session/apply-document-input-event! loaded {:event/type :pointer/move
                                                          :x x
                                                          :y y})
        document (get-in moved [:browser.session/page :browser/document])
        input-result (:browser.session/document-input-result moved)]
    (is (= button (:hover document)))
    (is (= button (-> moved :browser.session/history last :node/id)))
    (is (= true (-> moved :browser.session/history last :handled?)))
    (is (= [[:dom/dispatch-event
             50
             {:event/type "pointerover" :target/id button :x x :y y}]
            [:dom/dispatch-event
             51
             {:event/type "mouseover" :target/id button :x x :y y}]
            [:dom/dispatch-event
             54
             {:event/type "pointerenter" :target/id button :x x :y y}]
            [:dom/dispatch-event
             55
             {:event/type "mouseenter" :target/id button :x x :y y}]
            [:dom/dispatch-event
             53
             {:event/type "pointermove" :target/id button :x x :y y}]
            [:dom/dispatch-event
             52
             {:event/type "mousemove" :target/id button :x x :y y}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in input-result [:document :ops]))))))

(deftest document-pointer-down-and-up-resolve-target-from-page-coordinates
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://pointer"
                                        :html "<main><button id=\"run\">Run</button></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        button (bridge/query-selector document "#run")
        document (-> document
                     (dom/add-event-listener button "pointerdown" 61)
                     (dom/add-event-listener button "pointerup" 62)
                     (dom/add-event-listener button "pointercancel" 63))
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        button-op (some #(when (and (= :node (:draw/op %))
                                    (= button (:id %)))
                           %)
                        (:browser/draw-ops page))
        x (+ (:x button-op) 2)
        y (+ (:y button-op) 2)
        down (session/apply-document-input-event! loaded {:event/type :pointer/down
                                                         :button 0
                                                         :x x
                                                         :y y
                                                         :pointerId 7
                                                         :pointerType "touch"
                                                         :isPrimary true})
        up (session/apply-document-input-event! down {:event/type :pointer/up
                                                      :button 0
                                                      :x x
                                                      :y y
                                                      :pointerId 7
                                                      :pointerType "touch"
                                                      :isPrimary true})
        canceled (session/apply-document-input-event! up {:event/type :pointer/cancel
                                                         :x x
                                                         :y y
                                                         :pointerId 7
                                                         :pointerType "touch"
                                                         :isPrimary true})
        input-result (:browser.session/document-input-result canceled)]
    (is (= button (-> down :browser.session/history last :node/id)))
    (is (= true (-> down :browser.session/history last :handled?)))
    (is (= button (-> up :browser.session/history last :node/id)))
    (is (= true (-> up :browser.session/history last :handled?)))
    (is (= button (-> canceled :browser.session/history last :node/id)))
    (is (= true (-> canceled :browser.session/history last :handled?)))
    (is (= [[:dom/dispatch-event
             63
             {:event/type "pointercancel"
              :target/id button
              :x x
              :y y
              :pointerId 7
              :pointerType "touch"
              :isPrimary true}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in input-result [:document :ops]))))))

(deftest document-pointer-capture-routes-move-until-release
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://capture"
                                        :html "<main><button id=\"first\">First</button><button id=\"second\">Second</button></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        first-id (bridge/query-selector document "#first")
        second-id (bridge/query-selector document "#second")
        document (-> document
                     (dom/add-event-listener first-id "pointerdown" 71)
                     (dom/add-event-listener first-id "gotpointercapture" 72)
                     (dom/add-event-listener first-id "pointermove" 73)
                     (dom/add-event-listener first-id "pointerup" 74)
                     (dom/add-event-listener first-id "lostpointercapture" 75)
                     (dom/add-event-listener second-id "pointermove" 76))
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        node-op (fn [id]
                  (some #(when (and (= :node (:draw/op %))
                                    (= id (:id %)))
                           %)
                        (:browser/draw-ops page)))
        first-op (node-op first-id)
        second-op (node-op second-id)
        first-x (+ (:x first-op) 2)
        first-y (+ (:y first-op) 2)
        second-x (+ (:x second-op) 2)
        second-y (+ (:y second-op) 2)
        down (session/apply-document-input-event! loaded {:event/type :pointer/down
                                                         :x first-x
                                                         :y first-y
                                                         :pointerId 9
                                                         :pointerType "mouse"})
        moved (session/apply-document-input-event! down {:event/type :pointer/move
                                                        :x second-x
                                                        :y second-y
                                                        :pointerId 9
                                                        :pointerType "mouse"})
        up (session/apply-document-input-event! moved {:event/type :pointer/up
                                                       :x second-x
                                                       :y second-y
                                                       :pointerId 9
                                                       :pointerType "mouse"})
        move-result (:browser.session/document-input-result moved)
        up-result (:browser.session/document-input-result up)]
    (is (= first-id (get-in down [:browser.session/page :browser/document :pointer/capture 9])))
    (is (= first-id (-> moved :browser.session/history last :node/id)))
    (is (nil? (get-in up [:browser.session/page :browser/document :pointer/capture 9])))
    (is (= [[:dom/dispatch-event
             73
             {:event/type "pointermove"
              :target/id first-id
              :x second-x
              :y second-y
              :pointerId 9
              :pointerType "mouse"}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in move-result [:document :ops]))))
    (is (= [[:dom/dispatch-event
             74
             {:event/type "pointerup"
              :target/id first-id
              :x second-x
              :y second-y
              :pointerId 9
              :pointerType "mouse"}]
            [:dom/dispatch-event
             75
             {:event/type "lostpointercapture"
              :target/id first-id
              :x second-x
              :y second-y
              :pointerId 9
              :pointerType "mouse"}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in up-result [:document :ops]))))))

(deftest document-click-events-resolve-target-from-page-coordinates
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://click"
                                        :html "<main><button id=\"run\">Run</button><input id=\"field\" value=\"ab\"><input id=\"upload\" type=\"file\" value=\"/secret/path.txt\"></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        button (bridge/query-selector document "#run")
        field (bridge/query-selector document "#field")
        upload (bridge/query-selector document "#upload")
        document (-> document
                     (dom/add-event-listener button "click" 41)
                     (dom/add-event-listener field "focus" 42))
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        button-op (some #(when (and (= :node (:draw/op %))
                                    (= button (:id %)))
                           %)
                        (:browser/draw-ops page))
        field-op (some #(when (and (= :node (:draw/op %))
                                   (= field (:id %)))
                          %)
                       (:browser/draw-ops page))
        upload-op (some #(when (and (= :node (:draw/op %))
                                    (= upload (:id %)))
                           %)
                        (:browser/draw-ops page))
        clicked (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x (+ (:x button-op) 2)
                                                            :y (+ (:y button-op) 2)})
        focused (session/apply-document-input-event! clicked {:event/type :pointer/click
                                                             :x (+ (:x field-op) 2)
                                                             :y (+ (:y field-op) 2)})
        typed (session/apply-document-input-event! focused {:event/type :text/input
                                                           :text "Z"})
        file-clicked (session/apply-document-input-event! typed {:event/type :pointer/click
                                                                 :x (+ (:x upload-op) 2)
                                                                 :y (+ (:y upload-op) 2)})
        recorded (host/recorded h)]
    (is (= button (-> clicked :browser.session/history last :node/id)))
    (is (= [:dom/dispatch-event
            41
            {:event/type "click"
             :target/id button
             :x (+ (:x button-op) 2)
             :y (+ (:y button-op) 2)}]
           (last (get-in clicked [:browser.session/document-input-result :document :ops]))))
    (is (= field (-> focused :browser.session/history last :node/id)))
    (is (= field (get-in focused [:browser.session/page :browser/document :focus])))
    (is (= "abZ" (get-in typed [:browser.session/page :browser/document :nodes field :attrs :value])))
    (is (= field (-> typed :browser.session/history last :node/id)))
    (is (= upload (-> file-clicked :browser.session/history last :node/id)))
    (is (= false (-> file-clicked :browser.session/history last :handled?)))
    (is (= field (get-in file-clicked [:browser.session/page :browser/document :focus])))
    (is (= "/secret/path.txt" (get-in file-clicked [:browser.session/page :browser/document :nodes upload :attrs :value])))
    (is (= 5 (:present-count recorded)))))

(deftest document-click-hit-test-prefers-higher-z-index-absolute-node
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://position"
                                        :html "<main><section id=\"stack\"><button id=\"low\">Low</button><button id=\"high\">High</button></section></main>"
                                        :css "#stack { position: relative; width: 120px; height: 80px; padding: 0 } button { position: absolute; width: 60px; height: 40px } #low { left: 10px; top: 10px; z-index: 1 } #high { left: 20px; top: 20px; z-index: 5 }"}))
        document (get-in loaded [:browser.session/page :browser/document])
        low (bridge/query-selector document "#low")
        high (bridge/query-selector document "#high")
        document (-> document
                     (dom/add-event-listener low "click" 71)
                     (dom/add-event-listener high "click" 72))
        loaded (session/commit-document! loaded document)
        high-op (some #(when (and (= :node (:draw/op %))
                                  (= high (:id %)))
                         %)
                      (get-in loaded [:browser.session/page :browser/draw-ops]))
        x (+ (:x high-op) 5)
        y (+ (:y high-op) 5)
        clicked (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x x
                                                            :y y})
        input-result (:browser.session/document-input-result clicked)]
    (is (= high (-> clicked :browser.session/history last :node/id)))
    (is (= [[:dom/dispatch-event
             72
             {:event/type "click"
              :target/id high
              :x x
              :y y}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in input-result [:document :ops]))))))

(deftest document-click-hit-test-skips-pointer-events-none-overlay
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://pointer-events"
                                        :html "<main><section id=\"stack\" style=\"position: relative; width: 120px; height: 80px; padding: 0\"><button id=\"under\" style=\"position: absolute; left: 10px; top: 10px; width: 80px; height: 40px; z-index: 1\">Under</button><section id=\"overlay\" style=\"position: absolute; left: 0; top: 0; width: 120px; height: 80px; z-index: 5; pointer-events: none; background: #eeeeee\">Overlay</section></section></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        under (bridge/query-selector document "#under")
        overlay (bridge/query-selector document "#overlay")
        document (-> document
                     (dom/add-event-listener under "click" 81)
                     (dom/add-event-listener overlay "click" 82))
        loaded (session/commit-document! loaded document)
        overlay-op (some #(when (and (= :node (:draw/op %))
                                     (= overlay (:id %)))
                            %)
                         (get-in loaded [:browser.session/page :browser/draw-ops]))
        under-op (some #(when (and (= :node (:draw/op %))
                                   (= under (:id %)))
                          %)
                       (get-in loaded [:browser.session/page :browser/draw-ops]))
        x (+ (:x under-op) 5)
        y (+ (:y under-op) 5)
        clicked (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x x
                                                            :y y})
        input-result (:browser.session/document-input-result clicked)]
    (is (= "none" (:pointer-events overlay-op)))
    (is (= under (-> clicked :browser.session/history last :node/id)))
    (is (= [[:dom/dispatch-event
             81
             {:event/type "click"
              :target/id under
              :x x
              :y y}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in input-result [:document :ops]))))))

(deftest document-click-hit-test-keeps-transparent-overlay-targetable
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://opacity-hit"
                                        :html "<main><section id=\"stack\" style=\"position: relative; width: 120px; height: 80px; padding: 0\"><button id=\"under\" style=\"position: absolute; left: 10px; top: 10px; width: 80px; height: 40px; z-index: 1\">Under</button><section id=\"overlay\" style=\"position: absolute; left: 0; top: 0; width: 120px; height: 80px; z-index: 5; opacity: 0\">Overlay</section></section></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        under (bridge/query-selector document "#under")
        overlay (bridge/query-selector document "#overlay")
        document (-> document
                     (dom/add-event-listener under "click" 91)
                     (dom/add-event-listener overlay "click" 92))
        loaded (session/commit-document! loaded document)
        overlay-op (some #(when (and (= :node (:draw/op %))
                                     (= overlay (:id %)))
                            %)
                         (get-in loaded [:browser.session/page :browser/draw-ops]))
        x (+ (:x overlay-op) 15)
        y (+ (:y overlay-op) 15)
        clicked (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x x
                                                            :y y})
        input-result (:browser.session/document-input-result clicked)]
    (is (= 0.0 (:opacity overlay-op)))
    (is (= overlay (-> clicked :browser.session/history last :node/id)))
    (is (= [[:dom/dispatch-event
             92
             {:event/type "click"
              :target/id overlay
              :x x
              :y y}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in input-result [:document :ops]))))))

(deftest document-link-click-navigates-through-session
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 (case url
                                   "https://app.example/next"
                                   {:status 200 :body "<main>Next page</main>"}
                                   {:status 404 :body "<main>Missing</main>"}))})
                   (session/load-html! {:url "https://app.example/docs/"
                                        :html "<main><a id=\"next\" href=\"/next\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        recorded (host/recorded h)
        link-event (first (filter #(= :link/navigation (:event %))
                                  (:browser.session/history result)))]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/docs/"}
             :url "https://app.example/next" :method :get}]
           @calls))
    (is (= "Next page"
           (-> result :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= 2 (:present-count recorded))
        "link navigation should not commit the old document before the destination page")
    (is (= {:event :link/navigation
            :url "https://app.example/next"
            :href "/next"
            :target "_self"
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))
            :referrer "https://app.example/docs/"}
           link-event))
    (is (= [:page/commit :document/input :link/navigation :page/commit]
           (mapv :event (:browser.session/history result))))))

(deftest document-link-referrerpolicy-origin-sends-origin-referer
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 (case url
                                   "https://app.example/next"
                                   {:status 200 :body "<main>Next page</main>"}
                                   {:status 404 :body "<main>Missing</main>"}))})
                   (session/load-html! {:url "https://app.example/docs/index.html"
                                        :html "<main><a id=\"next\" href=\"/next\" referrerpolicy=\"origin\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        link-event (first (filter #(= :link/navigation (:event %))
                                  (:browser.session/history result)))]
    (is (= [{:headers {"referer" "https://app.example"}
             :url "https://app.example/next"
             :method :get}]
           @calls))
    (is (= {:event :link/navigation
            :url "https://app.example/next"
            :href "/next"
            :target "_self"
            :referrer-policy "origin"
            :referrer "https://app.example"
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))}
           link-event))))

(deftest document-link-noreferrer-suppresses-current-navigation-referer
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 (case url
                                   "https://app.example/next"
                                   {:status 200 :body "<main>Next page</main>"}
                                   {:status 404 :body "<main>Missing</main>"}))})
                   (session/load-html! {:url "https://app.example/docs/index.html"
                                        :html "<main><a id=\"next\" href=\"/next\" rel=\"noreferrer\" referrerpolicy=\"origin\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        link-event (first (filter #(= :link/navigation (:event %))
                                  (:browser.session/history result)))]
    (is (= [{:url "https://app.example/next" :method :get}]
           @calls))
    (is (= {:event :link/navigation
            :url "https://app.example/next"
            :href "/next"
            :target "_self"
            :rel "noreferrer"
            :referrer-policy "origin"
            :referrer nil
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))}
           link-event))))

(deftest document-link-cross-origin-no-policy-sends-origin-only-referer
  ;; Proves the request-referrer / link-request-referrer fix: with no explicit
  ;; referrerpolicy, a cross-origin destination gets the real
  ;; strict-origin-when-cross-origin default (source origin only), not the
  ;; full page-url the pre-fix code leaked to any destination.
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 (case url
                                   "https://other.example/next"
                                   {:status 200 :body "<main>Next page</main>"}
                                   {:status 404 :body "<main>Missing</main>"}))})
                   (session/load-html! {:url "https://app.example/docs/index.html"
                                        :html "<main><a id=\"next\" href=\"https://other.example/next\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        link-event (first (filter #(= :link/navigation (:event %))
                                  (:browser.session/history result)))]
    (is (= [{:headers {"referer" "https://app.example"}
             :url "https://other.example/next"
             :method :get}]
           @calls))
    (is (= {:event :link/navigation
            :url "https://other.example/next"
            :href "https://other.example/next"
            :target "_self"
            :referrer "https://app.example"
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))}
           link-event))))

(deftest document-link-same-origin-no-policy-sends-full-referer
  ;; Confirms the fix did not break the existing, correct same-origin
  ;; default: a same-origin destination still gets the full page-url.
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 (case url
                                   "https://app.example/next"
                                   {:status 200 :body "<main>Next page</main>"}
                                   {:status 404 :body "<main>Missing</main>"}))})
                   (session/load-html! {:url "https://app.example/docs/index.html"
                                        :html "<main><a id=\"next\" href=\"/next\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})]
    (is (= [{:headers {"referer" "https://app.example/docs/index.html"}
             :url "https://app.example/next"
             :method :get}]
           @calls))))

(deftest document-link-https-to-http-downgrade-suppresses-referer
  ;; Proves downgrade suppression: even though the destination is the same
  ;; host, an HTTPS page linking to a plain-HTTP destination must send no
  ;; referrer at all -- checked before, and regardless of, the
  ;; same-origin/cross-origin split.
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 (case url
                                   "http://app.example/other"
                                   {:status 200 :body "<main>Other page</main>"}
                                   {:status 404 :body "<main>Missing</main>"}))})
                   (session/load-html! {:url "https://app.example/"
                                        :html "<main><a id=\"next\" href=\"http://app.example/other\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        link-event (first (filter #(= :link/navigation (:event %))
                                  (:browser.session/history result)))]
    (is (= [{:url "http://app.example/other" :method :get}]
           @calls))
    (is (= {:event :link/navigation
            :url "http://app.example/other"
            :href "http://app.example/other"
            :target "_self"
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))}
           link-event)
        "no :referrer key at all -- referrer computed to nil and rel/referrer-policy both absent")))

(deftest document-blank-target-link-records-context-request-without-current-navigation
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>New context?</main>"})})
                   (session/load-html! {:url "https://app.example/docs/"
                                        :html "<main><a id=\"next\" href=\"/next\" target=\"_blank\">Next</a></main>"}))
        generation (:browser.session/page-generation loaded)
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        recorded (host/recorded h)
        context-event (last (:browser.session/history result))]
    (is (= [] @calls))
    (is (= generation (:browser.session/page-generation result)))
    (is (= "https://app.example/docs/"
           (get-in result [:browser.session/page :browser/url])))
    (is (= 1 (:present-count recorded)))
    (is (= {:event :link/context-request
            :url "https://app.example/next"
            :href "/next"
            :target "_blank"
            :rel nil
            :referrer-policy nil
            :referrer "https://app.example/docs/"
            :opener? false
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))}
           context-event))
    (is (= [:page/commit :document/input :link/context-request]
           (mapv :event (:browser.session/history result))))))

(deftest document-noreferrer-target-link-suppresses-context-referrer
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>New context?</main>"})})
                   (session/load-html! {:url "https://app.example/docs/index.html"
                                        :html "<main><a id=\"next\" href=\"/next\" target=\"report\" rel=\"noreferrer opener\" referrerpolicy=\"origin\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        context-event (last (:browser.session/history result))]
    (is (= [] @calls))
    (is (= "https://app.example/docs/index.html"
           (get-in result [:browser.session/page :browser/url])))
    (is (= {:event :link/context-request
            :url "https://app.example/next"
            :href "/next"
            :target "report"
            :rel "noreferrer opener"
            :referrer-policy "origin"
            :referrer nil
            :opener? false
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))}
           context-event))))

(deftest document-blank-target-link-cross-origin-no-policy-sends-origin-only-referer
  ;; Proves the link-context-request-event fix: with no explicit
  ;; referrerpolicy, a target="_blank" link to a cross-origin destination
  ;; gets origin-only referer, matching the same strict-origin-when-cross-origin
  ;; default as same-page navigation (link-request-referrer).
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>New context?</main>"})})
                   (session/load-html! {:url "https://app.example/docs/"
                                        :html "<main><a id=\"next\" href=\"https://other.example/next\" target=\"_blank\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        context-event (last (:browser.session/history result))]
    (is (= [] @calls))
    (is (= {:event :link/context-request
            :url "https://other.example/next"
            :href "https://other.example/next"
            :target "_blank"
            :rel nil
            :referrer-policy nil
            :referrer "https://app.example"
            :opener? false
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))}
           context-event))))

(deftest document-blank-target-link-https-to-http-downgrade-suppresses-referer
  ;; Proves downgrade suppression on the context-request path too: same host,
  ;; but HTTPS -> HTTP, so no referrer at all.
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>New context?</main>"})})
                   (session/load-html! {:url "https://app.example/"
                                        :html "<main><a id=\"next\" href=\"http://app.example/other\" target=\"_blank\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        context-event (last (:browser.session/history result))]
    (is (= [] @calls))
    (is (= {:event :link/context-request
            :url "http://app.example/other"
            :href "http://app.example/other"
            :target "_blank"
            :rel nil
            :referrer-policy nil
            :referrer nil
            :opener? false
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))}
           context-event))))

(deftest document-blank-target-link-explicit-no-referrer-policy-without-rel-suppresses-referer
  ;; Proves the core link-context-request-event bug fix: before the fix, this
  ;; function only ever checked rel="noreferrer" and otherwise unconditionally
  ;; sent the full page-url, silently ignoring an explicit
  ;; referrerpolicy="no-referrer" attribute whenever rel="noreferrer" was not
  ;; also present. A target="_blank" link with an explicit no-referrer policy
  ;; (but no rel="noreferrer") must now suppress the referrer.
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>New context?</main>"})})
                   (session/load-html! {:url "https://app.example/docs/"
                                        :html "<main><a id=\"next\" href=\"/next\" target=\"_blank\" referrerpolicy=\"no-referrer\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#next"})
        context-event (last (:browser.session/history result))]
    (is (= [] @calls))
    (is (= {:event :link/context-request
            :url "https://app.example/next"
            :href "/next"
            :target "_blank"
            :rel nil
            :referrer-policy "no-referrer"
            :referrer nil
            :opener? false
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))}
           context-event))))

(deftest document-fragment-link-click-enters-same-document-navigation
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>Unexpected fetch</main>"})})
                   (session/load-html! {:url "https://app.example/docs/index.html#top"
                                        :html "<main><a id=\"jump\" href=\"#details\">Jump</a><section id=\"details\">Details</section></main>"}))
        generation (:browser.session/page-generation loaded)
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#jump"})
        recorded (host/recorded h)]
    (is (= [] @calls))
    (is (= generation (:browser.session/page-generation result)))
    (is (= 1 (:present-count recorded))
        "fragment links do not refetch or re-render the same document")
    (is (= "https://app.example/docs/index.html#details"
           (get-in result [:browser.session/page :browser/url])))
    (is (= [:page/commit :document/input :link/navigation :navigation/fragment]
           (mapv :event (:browser.session/history result))))))

(deftest document-download-link-click-records-boundary-without-navigation
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>Downloaded?</main>"})})
                   (session/load-html! {:url "https://app.example/docs/index.html"
                                        :html "<main><a id=\"report\" href=\"/files/report.csv\" download=\"report.csv\">Report</a></main>"}))
        generation (:browser.session/page-generation loaded)
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :node/selector "#report"})
        recorded (host/recorded h)
        download-event (last (:browser.session/history result))]
    (is (= [] @calls))
    (is (= generation (:browser.session/page-generation result)))
    (is (= "https://app.example/docs/index.html"
           (get-in result [:browser.session/page :browser/url])))
    (is (= 1 (:present-count recorded)))
    (is (= {:event :link/download-request
            :url "https://app.example/files/report.csv"
            :href "/files/report.csv"
            :target "_self"
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))
            :download/filename "report.csv"}
           download-event))
    (is (= {:capability :download/request
            :url "https://app.example/files/report.csv"
            :href "/files/report.csv"
            :target "_self"
            :link/id (:node/id (get-in result [:browser.session/document-input-result]))
            :node/id (:node/id (get-in result [:browser.session/document-input-result]))
            :download/filename "report.csv"}
           (:browser.session/download-request result)))
    (is (= [:page/commit :document/input :link/download-request]
           (mapv :event (:browser.session/history result))))))

(deftest document-link-enter-key-navigates-through-session
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 (case url
                                   "https://app.example/next"
                                   {:status 200 :body "<main>Next page</main>"}
                                   {:status 404 :body "<main>Missing</main>"}))})
                   (session/load-html! {:url "https://app.example/docs/index.html"
                                        :html "<main><a id=\"next\" href=\"/next\">Next</a></main>"}))
        result (session/apply-document-input-event! loaded {:event/type :key/down
                                                           :node/selector "#next"
                                                           :key "Enter"})
        recorded (host/recorded h)]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/docs/index.html"}
             :url "https://app.example/next" :method :get}]
           @calls))
    (is (= "Next page"
           (-> result :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= 2 (:present-count recorded)))
    (is (= [:page/commit :document/input :link/navigation :page/commit]
           (mapv :event (:browser.session/history result))))))

(deftest document-checkbox-click-toggles-and-commits-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://checkbox"
                                        :html "<main><input id=\"flag\" type=\"checkbox\"></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        flag (bridge/query-selector document "#flag")
        flag-op (some #(when (and (= :node (:draw/op %))
                                  (= flag (:id %)))
                         %)
                      (:browser/draw-ops page))
        checked (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x (+ (:x flag-op) 2)
                                                            :y (+ (:y flag-op) 2)})
        unchecked (session/apply-document-input-event! checked {:event/type :pointer/click
                                                               :x (+ (:x flag-op) 2)
                                                               :y (+ (:y flag-op) 2)})
        recorded (host/recorded h)]
    (is (= true (get-in checked [:browser.session/page :browser/document :nodes flag :attrs :checked])))
    (is (= false (get-in unchecked [:browser.session/page :browser/document :nodes flag :attrs :checked])))
    (is (= flag (-> checked :browser.session/history last :node/id)))
    (is (= true (-> checked :browser.session/history last :handled?)))
    (is (= 3 (:present-count recorded)))))

(deftest document-label-click-activates-control-and-commits-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://label"
                                        :html "<main><label id=\"label\" for=\"flag\">Flag</label><input id=\"flag\" type=\"checkbox\"></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        label (bridge/query-selector document "#label")
        flag (bridge/query-selector document "#flag")
        label-op (some #(when (and (= :node (:draw/op %))
                                   (= label (:id %)))
                          %)
                       (:browser/draw-ops page))
        checked (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x (+ (:x label-op) 2)
                                                            :y (+ (:y label-op) 2)})
        recorded (host/recorded h)]
    (is (= true (get-in checked [:browser.session/page :browser/document :nodes flag :attrs :checked])))
    (is (= flag (get-in checked [:browser.session/page :browser/document :focus])))
    (is (= flag (-> checked :browser.session/history last :node/id)))
    (is (= label (get-in checked [:browser.session/document-input-result :label/id])))
    (is (= true (-> checked :browser.session/history last :handled?)))
    (is (= 2 (:present-count recorded)))))

(deftest disabled-fieldset-labels-only-activate-first-legend-controls-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://label"
                                        :html "<main><fieldset disabled><legend><label id=\"legend-label\" for=\"legend-flag\">Legend</label><input id=\"legend-flag\" type=\"checkbox\"></legend><label id=\"blocked-label\" for=\"blocked-flag\">Blocked</label><input id=\"blocked-flag\" type=\"checkbox\"></fieldset></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        legend-label (bridge/query-selector document "#legend-label")
        blocked-label (bridge/query-selector document "#blocked-label")
        legend-flag (bridge/query-selector document "#legend-flag")
        blocked-flag (bridge/query-selector document "#blocked-flag")
        legend-op (some #(when (and (= :node (:draw/op %))
                                    (= legend-label (:id %)))
                           %)
                        (:browser/draw-ops page))
        blocked-op (some #(when (and (= :node (:draw/op %))
                                     (= blocked-label (:id %)))
                            %)
                         (:browser/draw-ops page))
        legend-result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                                   :x (+ (:x legend-op) 2)
                                                                   :y (+ (:y legend-op) 2)})
        blocked-result (session/apply-document-input-event! legend-result {:event/type :pointer/click
                                                                           :x (+ (:x blocked-op) 2)
                                                                           :y (+ (:y blocked-op) 2)})
        recorded (host/recorded h)]
    (is (= true (get-in legend-result [:browser.session/page :browser/document :nodes legend-flag :attrs :checked])))
    (is (= legend-flag (get-in legend-result [:browser.session/page :browser/document :focus])))
    (is (= legend-flag (-> legend-result :browser.session/history last :node/id)))
    (is (= legend-label (get-in legend-result [:browser.session/document-input-result :label/id])))
    (is (= true (-> legend-result :browser.session/history last :handled?)))
    (is (= true (-> blocked-result :browser.session/history last :handled?)))
    (is (= blocked-label (-> blocked-result :browser.session/history last :node/id)))
    (is (nil? (get-in blocked-result [:browser.session/document-input-result :label/id])))
    (is (not (true? (get-in blocked-result [:browser.session/page :browser/document :nodes blocked-flag :attrs :checked]))))
    (is (nil? (get-in blocked-result [:browser.session/page :browser/document :focus])))
    (is (= 3 (:present-count recorded)))))

(deftest document-file-label-click-does-not-commit-without-file-picker-capability
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://label"
                                        :html "<main><label id=\"label\" for=\"upload\">Upload</label><input id=\"upload\" type=\"file\" value=\"/secret/path.txt\"></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        label (bridge/query-selector document "#label")
        upload (bridge/query-selector document "#upload")
        label-op (some #(when (and (= :node (:draw/op %))
                                   (= label (:id %)))
                          %)
                       (:browser/draw-ops page))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x (+ (:x label-op) 2)
                                                            :y (+ (:y label-op) 2)})
        recorded (host/recorded h)]
    (is (= label (-> result :browser.session/history last :node/id)))
    (is (= false (-> result :browser.session/history last :handled?)))
    (is (nil? (get-in result [:browser.session/page :browser/document :focus])))
    (is (= "/secret/path.txt" (get-in result [:browser.session/page :browser/document :nodes upload :attrs :value])))
    (is (= 1 (:present-count recorded)))))

(deftest document-submit-button-dispatches-submit-and-commits-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://submit"
                                        :html "<main><form id=\"form\"><input id=\"field\" name=\"q\" value=\"Kotoba\"><button id=\"go\" name=\"action\" value=\"go\">Go</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        form (bridge/query-selector document "#form")
        field (bridge/query-selector document "#field")
        go (bridge/query-selector document "#go")
        document (dom/add-event-listener document form "submit" 43)
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        recorded (host/recorded h)]
    (is (= go (-> submitted :browser.session/history last :node/id)))
    (is (= form (get-in submitted [:browser.session/document-input-result :form/id])))
    (is (= true (get-in submitted [:browser.session/document-input-result :submitted?])))
    (is (= [{:name "q" :value "Kotoba" :node/id field}
            {:name "action" :value "go" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (= [:dom/dispatch-event
            43
            {:event/type "submit" :target/id form :submitter/id go
             :form/data [{:name "q" :value "Kotoba" :node/id field}
                         {:name "action" :value "go" :node/id go}]
             :x (+ (:x go-op) 2)
             :y (+ (:y go-op) 2)}]
           (last (get-in submitted [:browser.session/document-input-result :document :ops]))))
    (is (= true (-> submitted :browser.session/history last :handled?)))
    (is (= 3 (:present-count recorded)))))

(deftest disabled-submit-button-does-not-submit-or-fetch-through-session
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>Submitted</main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/submit\"><input id=\"field\" name=\"q\" value=\"Kotoba\"><button id=\"go\" disabled>Go</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x (+ (:x go-op) 2)
                                                            :y (+ (:y go-op) 2)})
        recorded (host/recorded h)]
    (is (empty? @calls))
    (is (= go (-> result :browser.session/history last :node/id)))
    (is (= false (-> result :browser.session/history last :handled?)))
    (is (nil? (get-in result [:browser.session/document-input-result :submitted?])))
    (is (= "https://app.example/form" (get-in result [:browser.session/page :browser/url])))
    (is (= 1 (:present-count recorded)))))

(deftest disabled-focused-submit-key-does-not-submit-or-fetch-through-session
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>Submitted</main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/submit\"><input id=\"field\" name=\"q\" value=\"Kotoba\"><button id=\"go\" disabled>Go</button></form></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        go (bridge/query-selector document "#go")
        loaded (session/commit-document! loaded (assoc document :focus go))
        result (session/apply-document-input-event! loaded {:event/type :key/down
                                                            :key "Enter"})
        recorded (host/recorded h)]
    (is (empty? @calls))
    (is (= go (-> result :browser.session/history last :node/id)))
    (is (= false (-> result :browser.session/history last :handled?)))
    (is (nil? (get-in result [:browser.session/document-input-result :submitted?])))
    (is (= go (get-in result [:browser.session/page :browser/document :focus])))
    (is (= "https://app.example/form" (get-in result [:browser.session/page :browser/url])))
    (is (= 2 (:present-count recorded)))))

(deftest type-button-controls-do-not-submit-reset-or-fetch-through-session
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>Submitted</main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/submit\"><input id=\"field\" name=\"q\" value=\"Kotoba\"><button id=\"button-noop\" type=\"button\" name=\"action\" value=\"button\">Button</button><input id=\"input-noop\" type=\"button\" name=\"action\" value=\"input\"><button id=\"go\" name=\"action\" value=\"go\">Go</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        button-noop (bridge/query-selector document "#button-noop")
        input-noop (bridge/query-selector document "#input-noop")
        button-noop-op (some #(when (and (= :node (:draw/op %))
                                         (= button-noop (:id %)))
                                %)
                             (:browser/draw-ops page))
        input-noop-op (some #(when (and (= :node (:draw/op %))
                                        (= input-noop (:id %)))
                               %)
                            (:browser/draw-ops page))
        button-clicked (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                                    :x (+ (:x button-noop-op) 2)
                                                                    :y (+ (:y button-noop-op) 2)})
        input-clicked (session/apply-document-input-event! button-clicked {:event/type :pointer/click
                                                                           :x (+ (:x input-noop-op) 2)
                                                                           :y (+ (:y input-noop-op) 2)})
        recorded (host/recorded h)]
    (is (empty? @calls))
    (is (= "https://app.example/form" (get-in button-clicked [:browser.session/page :browser/url])))
    (is (= "https://app.example/form" (get-in input-clicked [:browser.session/page :browser/url])))
    (is (= button-noop (-> button-clicked :browser.session/history last :node/id)))
    (is (= input-noop (-> input-clicked :browser.session/history last :node/id)))
    (is (= true (-> button-clicked :browser.session/history last :handled?)))
    (is (= true (-> input-clicked :browser.session/history last :handled?)))
    (is (nil? (get-in button-clicked [:browser.session/document-input-result :submitted?])))
    (is (nil? (get-in button-clicked [:browser.session/document-input-result :reset?])))
    (is (nil? (get-in input-clicked [:browser.session/document-input-result :submitted?])))
    (is (nil? (get-in input-clicked [:browser.session/document-input-result :reset?])))
    (is (= input-noop (get-in input-clicked [:browser.session/page :browser/document :focus])))
    (is (= 3 (:present-count recorded)))))

(deftest get-form-submit-navigates-with-form-data-query
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search/index.html"
                                        :html "<main><form id=\"form\" action=\"/results\" method=\"get\"><input id=\"q\" name=\"q\" value=\"hello world\"><input id=\"amount\" type=\"number\" name=\"amount\" value=\"7\"><input id=\"volume\" type=\"range\" name=\"volume\" value=\"4\"><input id=\"flag\" type=\"checkbox\" name=\"ok\" checked><button id=\"go\" name=\"action\" value=\"find\">Find</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        amount (bridge/query-selector document "#amount")
        volume (bridge/query-selector document "#volume")
        flag (bridge/query-selector document "#flag")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://app.example/results?q=hello+world&amount=7&volume=4&ok=on&action=find"
        recorded (host/recorded h)]
    ;; No explicit referrerpolicy + same-origin destination -> the real
    ;; strict-origin-when-cross-origin default sends the full page-url as
    ;; the referer (see request-referrer in session.cljc).
    (is (= [{:headers {"referer" "https://app.example/search/index.html"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (= expected-url
           (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= [{:name "q" :value "hello world" :node/id q}
            {:name "amount" :value "7" :node/id amount}
            {:name "volume" :value "4" :node/id volume}
            {:name "ok" :value "on" :node/id flag}
            {:name "action" :value "find" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (= :form/submit-navigation
           (:event (first (filter #(= :form/submit-navigation (:event %))
                                  (:browser.session/history submitted))))))
    (is (= expected-url
           (:url (first (filter #(= :form/submit-navigation (:event %))
                                (:browser.session/history submitted))))))
    (is (= 3 (:present-count recorded)))))

(deftest get-form-referrerpolicy-origin-sends-origin-referer
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search/index.html"
                                        :html "<main><form id=\"form\" action=\"/results\" method=\"get\" referrerpolicy=\"origin\"><input id=\"q\" name=\"q\" value=\"hello\"><button id=\"go\" name=\"action\" value=\"find\">Find</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://app.example/results?q=hello&action=find"
        nav-event (first (filter #(= :form/submit-navigation (:event %))
                                 (:browser.session/history submitted)))]
    (is (= [{:headers {"referer" "https://app.example"}
             :url expected-url
             :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (= {:event :form/submit-navigation
            :url expected-url
            :target "_self"
            :referrer-policy "origin"
            :referrer "https://app.example"
            :enctype "application/x-www-form-urlencoded"
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "hello" :node/id q}
                        {:name "action" :value "find" :node/id go}]}
           nav-event))))

(deftest get-form-cross-origin-no-policy-sends-origin-only-referer
  ;; Proves the form-submit-request / request-referrer fix on the GET
  ;; (navigate!) path: with no explicit referrerpolicy, a cross-origin form
  ;; action gets origin-only referer, not the full page-url.
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search/index.html"
                                        :html "<main><form id=\"form\" action=\"https://other.example/results\" method=\"get\"><input id=\"q\" name=\"q\" value=\"hello\"><button id=\"go\" name=\"action\" value=\"find\">Find</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://other.example/results?q=hello&action=find"]
    (is (= [{:headers {"referer" "https://app.example"}
             :url expected-url
             :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))))

(deftest get-form-https-to-http-downgrade-suppresses-referer
  ;; Proves downgrade suppression on the form-submission path: same host,
  ;; but HTTPS -> HTTP, so no referrer at all even though there is no
  ;; explicit referrerpolicy and the destination is same-origin-ish (same
  ;; host, different scheme).
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search/index.html"
                                        :html "<main><form id=\"form\" action=\"http://app.example/results\" method=\"get\"><input id=\"q\" name=\"q\" value=\"hello\"><button id=\"go\" name=\"action\" value=\"find\">Find</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "http://app.example/results?q=hello&action=find"]
    (is (= [{:url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))))

(deftest nameless-submitters-navigate-without-query-entry
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search/index.html"
                                        :html "<main><form id=\"form\" action=\"/results\" method=\"get\"><input id=\"q\" name=\"q\" value=\"hello\"><button id=\"button-go\" value=\"button\">Button</button><input id=\"empty-submit\" type=\"submit\" name=\"\" value=\"empty\"></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        button-go (bridge/query-selector document "#button-go")
        empty-submit (bridge/query-selector document "#empty-submit")
        button-go-op (some #(when (and (= :node (:draw/op %))
                                       (= button-go (:id %)))
                              %)
                           (:browser/draw-ops page))
        empty-submit-op (some #(when (and (= :node (:draw/op %))
                                          (= empty-submit (:id %)))
                                 %)
                              (:browser/draw-ops page))
        button-submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                                      :x (+ (:x button-go-op) 2)
                                                                      :y (+ (:y button-go-op) 2)})
        empty-submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                                     :x (+ (:x empty-submit-op) 2)
                                                                     :y (+ (:y empty-submit-op) 2)})
        expected-url "https://app.example/results?q=hello"]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/search/index.html"}
             :url expected-url :method :get}
            {:headers {"referer" "https://app.example/search/index.html"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in button-submitted [:browser.session/page :browser/url])))
    (is (= expected-url (get-in empty-submitted [:browser.session/page :browser/url])))
    (is (= [{:name "q" :value "hello" :node/id q}]
           (get-in button-submitted [:browser.session/document-input-result :form/data])))
    (is (= [{:name "q" :value "hello" :node/id q}]
           (get-in empty-submitted [:browser.session/document-input-result :form/data])))))

(deftest image-form-submit-navigates-with-coordinate-query
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search/index.html"
                                        :html "<main><form id=\"form\" action=\"/pick\" method=\"get\"><input id=\"q\" name=\"q\" value=\"map\"><input id=\"image\" type=\"image\" name=\"spot\" value=\"ignored\"><input id=\"nameless\" type=\"image\" value=\"ignored\"></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        image (bridge/query-selector document "#image")
        nameless (bridge/query-selector document "#nameless")
        image-op (some #(when (and (= :node (:draw/op %))
                                   (= image (:id %)))
                          %)
                       (:browser/draw-ops page))
        x (+ (:x image-op) 3)
        y (+ (:y image-op) 4)
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x x
                                                              :y y})
        nameless-submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                                       :node/id nameless
                                                                       :x 2
                                                                       :y 3})
        expected-url (str "https://app.example/pick?q=map&spot.x=" x "&spot.y=" y)]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/search/index.html"}
             :url expected-url :method :get}]
           (take 1 @calls)))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (= [{:name "q" :value "map" :node/id q}
            {:name "spot.x" :value (str x) :node/id image}
            {:name "spot.y" :value (str y) :node/id image}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (= "https://app.example/pick?q=map"
           (get-in nameless-submitted [:browser.session/page :browser/url])))
    (is (= [{:name "q" :value "map" :node/id q}]
           (get-in nameless-submitted [:browser.session/document-input-result :form/data])))))

(deftest invalid-required-form-submit-does-not-navigate
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search"
                                        :html "<main><form id=\"form\" action=\"/results\" method=\"get\"><input id=\"q\" name=\"q\" required><button id=\"go\">Find</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        document (dom/add-event-listener document q "invalid" 77)
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        recorded (host/recorded h)]
    (is (= [] @calls))
    (is (= "https://app.example/search" (get-in submitted [:browser.session/page :browser/url])))
    (is (= true (get-in submitted [:browser.session/document-input-result :invalid?])))
    (is (= [q] (get-in submitted [:browser.session/document-input-result :invalid/control-ids])))
    (is (= true (get-in submitted [:browser.session/page :browser/document :nodes q :attrs :invalid])))
    (is (= "value-missing" (get-in submitted [:browser.session/page :browser/document :nodes q :attrs :validation-reason])))
    (is (not-any? #(= :form/submit-navigation (:event %))
                  (:browser.session/history submitted)))
    (is (= true (-> submitted :browser.session/history last :handled?)))
    (is (= [:dom/dispatch-event
            77
            {:event/type "invalid" :target/id q
             :form/id (bridge/query-selector document "#form")
             :submitter/id go
             :reason :value-missing
             :x (+ (:x go-op) 2)
             :y (+ (:y go-op) 2)}]
           (last (get-in submitted [:browser.session/document-input-result :document :ops]))))
    (is (= 3 (:present-count recorded)))))

(deftest external-formnovalidate-submitter-skips-required-validation-and-navigates
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search"
                                        :html "<main><form id=\"form\" action=\"/results\" method=\"get\"><input id=\"q\" name=\"q\" required></form><button id=\"skip\" form=\"form\" formnovalidate name=\"action\" value=\"skip\">Skip</button></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        skip (bridge/query-selector document "#skip")
        document (dom/add-event-listener document q "invalid" 78)
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        skip-op (some #(when (and (= :node (:draw/op %))
                                  (= skip (:id %)))
                         %)
                      (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x skip-op) 2)
                                                              :y (+ (:y skip-op) 2)})
        expected-url "https://app.example/results?q=&action=skip"
        recorded (host/recorded h)]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/search"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (nil? (get-in submitted [:browser.session/document-input-result :invalid?])))
    (is (= false (get-in submitted [:browser.session/document-input-result :document :nodes q :attrs :invalid])))
    (is (= [{:name "q" :value "" :node/id q}
            {:name "action" :value "skip" :node/id skip}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (not-any? #(= "invalid" (second %))
                  (get-in submitted [:browser.session/document-input-result :document :ops])))
    (is (= 4 (:present-count recorded)))))

(deftest invalid-text-length-submit-does-not-navigate
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search"
                                        :html "<main><form id=\"form\" action=\"/results\" method=\"get\"><input id=\"q\" name=\"q\" value=\"abcdef\" maxlength=\"4\"><button id=\"go\">Find</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})]
    (is (= [] @calls))
    (is (= "https://app.example/search" (get-in submitted [:browser.session/page :browser/url])))
    (is (= true (get-in submitted [:browser.session/document-input-result :invalid?])))
    (is (= [q] (get-in submitted [:browser.session/document-input-result :invalid/control-ids])))
    (is (= "too-long" (get-in submitted [:browser.session/page :browser/document :nodes q :attrs :validation-reason])))))

(deftest external-formnovalidate-submitter-skips-text-length-validation-and-navigates
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search"
                                        :html "<main><form id=\"form\" action=\"/results\" method=\"get\"><input id=\"short\" name=\"short\" value=\"ab\" minlength=\"3\"><textarea id=\"long\" name=\"long\" maxlength=\"4\">abcde</textarea></form><button id=\"skip\" form=\"form\" formnovalidate name=\"action\" value=\"skip\">Skip</button></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        short (bridge/query-selector document "#short")
        long (bridge/query-selector document "#long")
        skip (bridge/query-selector document "#skip")
        document (-> document
                     (dom/add-event-listener short "invalid" 79)
                     (dom/add-event-listener long "invalid" 80))
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        skip-op (some #(when (and (= :node (:draw/op %))
                                  (= skip (:id %)))
                         %)
                      (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x skip-op) 2)
                                                              :y (+ (:y skip-op) 2)})
        expected-url "https://app.example/results?short=ab&long=abcde&action=skip"
        recorded (host/recorded h)]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/search"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (nil? (get-in submitted [:browser.session/document-input-result :invalid?])))
    (is (= false (get-in submitted [:browser.session/document-input-result :document :nodes short :attrs :invalid])))
    (is (= "" (get-in submitted [:browser.session/document-input-result :document :nodes short :attrs :validation-reason])))
    (is (= false (get-in submitted [:browser.session/document-input-result :document :nodes long :attrs :invalid])))
    (is (= "" (get-in submitted [:browser.session/document-input-result :document :nodes long :attrs :validation-reason])))
    (is (= [{:name "short" :value "ab" :node/id short}
            {:name "long" :value "abcde" :node/id long}
            {:name "action" :value "skip" :node/id skip}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (not-any? #(or (= 79 (second %))
                       (= 80 (second %)))
                  (get-in submitted [:browser.session/document-input-result :document :ops])))
    (is (= 4 (:present-count recorded)))))

(deftest readonly-invalid-looking-controls-submit-and-navigate
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/search"
                                        :html "<main><form id=\"form\" action=\"/results\" method=\"get\"><input id=\"q\" name=\"q\" required readonly><textarea id=\"long\" name=\"long\" maxlength=\"4\" readonly>abcde</textarea><button id=\"go\" name=\"action\" value=\"go\">Find</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        long (bridge/query-selector document "#long")
        go (bridge/query-selector document "#go")
        document (-> document
                     (dom/add-event-listener q "invalid" 81)
                     (dom/add-event-listener long "invalid" 82))
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://app.example/results?q=&long=abcde&action=go"
        recorded (host/recorded h)]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/search"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (nil? (get-in submitted [:browser.session/document-input-result :invalid?])))
    (is (= [{:name "q" :value "" :node/id q}
            {:name "long" :value "abcde" :node/id long}
            {:name "action" :value "go" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (not-any? #(or (= 81 (second %))
                       (= 82 (second %)))
                  (get-in submitted [:browser.session/document-input-result :document :ops])))
    (is (= 4 (:present-count recorded)))))

(deftest validation-state-recomputes-css-after-submit-and-text-input
  (let [h (host/recording-host)
        page (browser/load-html {:url "https://app.example/search"
                                 :html "<main><form id=\"form\" action=\"/results\" method=\"get\"><input id=\"q\" name=\"q\" required><button id=\"go\">Find</button><button id=\"reset\" type=\"reset\">Reset</button></form></main>"
                                 :css "input:invalid { border-color: red; margin: 6px } input:valid { border-color: green; padding: 4px }"})
        loaded (session/new-session {:host h :page page})
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        reset (bridge/query-selector document "#reset")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        submitted-attrs (get-in submitted [:browser.session/page :browser/document :nodes q :attrs])
        focused (session/apply-document-input-event! submitted {:event/type :pointer/click
                                                               :node/id q})
        typed (session/apply-document-input-event! focused {:event/type :text/input
                                                           :node/id q
                                                           :text "kotoba"})
        typed-attrs (get-in typed [:browser.session/page :browser/document :nodes q :attrs])
        reset-op (some #(when (and (= :node (:draw/op %))
                                   (= reset (:id %)))
                          %)
                       (get-in typed [:browser.session/page :browser/draw-ops]))
        reset-result (session/apply-document-input-event! typed {:event/type :pointer/click
                                                                :x (+ (:x reset-op) 2)
                                                                :y (+ (:y reset-op) 2)})
        reset-attrs (get-in reset-result [:browser.session/page :browser/document :nodes q :attrs])]
    (is (= true (:invalid submitted-attrs)))
    (is (= "value-missing" (:validation-reason submitted-attrs)))
    (is (= "red" (:style/border-color submitted-attrs)))
    (is (= 6 (:style/margin submitted-attrs)))
    (is (nil? (:style/padding submitted-attrs)))
    (is (= false (:invalid typed-attrs)))
    (is (= "" (:validation-reason typed-attrs)))
    (is (= "green" (:style/border-color typed-attrs)))
    (is (= 4 (:style/padding typed-attrs)))
    (is (nil? (:style/margin typed-attrs)))
    (is (= true (get-in reset-result [:browser.session/document-input-result :reset?])))
    (is (= "" (:value reset-attrs)))
    (is (= false (:invalid reset-attrs)))
    (is (= "" (:validation-reason reset-attrs)))
    (is (= "red" (:style/border-color reset-attrs)))
    (is (= 6 (:style/margin reset-attrs)))
    (is (nil? (:style/padding reset-attrs)))))

(deftest submitter-formaction-overrides-form-action
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200 :body (str "<main>" url "</main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/default\" method=\"get\"><input id=\"q\" name=\"q\" value=\"override\"><button id=\"go\" formaction=\"/alternate\" name=\"action\" value=\"alt\">Alt</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://app.example/alternate?q=override&action=alt"]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/form"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))))

(deftest submitter-formmethod-overrides-form-method
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :headers {} :body "<main>Posted override</main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/default\" method=\"get\"><input id=\"q\" name=\"q\" value=\"override\"><button id=\"go\" formmethod=\"post\" formaction=\"/submit\" name=\"action\" value=\"post\">Post</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:url "https://app.example/submit"
             :method :post
             :headers {"content-type" "application/x-www-form-urlencoded"
                       "referer" "https://app.example/form"}
             :body "q=override&action=post"}]
           @calls))
    (is (= "Posted override" (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= "https://app.example/submit" (get-in submitted [:browser.session/page :browser/url])))))

(deftest submitter-formtarget-records-context-request-without-current-navigation
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200 :body (str "<main>" url "</main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/default\" method=\"get\" target=\"_self\" referrerpolicy=\"no-referrer\"><input id=\"q\" name=\"q\" value=\"target\"><button id=\"go\" formtarget=\"_blank\" name=\"action\" value=\"open\">Open</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        recorded (host/recorded h)
        expected-url "https://app.example/default?q=target&action=open"]
    (is (= [] @calls))
    (is (= "https://app.example/form"
           (get-in submitted [:browser.session/page :browser/url])))
    (is (= 2 (:present-count recorded)))
    (is (= {:event :form/context-request
            :url expected-url
            :method :get
            :target "_blank"
            :referrer-policy "no-referrer"
            :referrer nil
            :enctype "application/x-www-form-urlencoded"
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "target" :node/id q}
                        {:name "action" :value "open" :node/id go}]}
           (first (filter #(= :form/context-request (:event %))
                          (:browser.session/history submitted)))))))

(deftest submitter-formtarget-referrerpolicy-origin-sends-origin-only-referer
  ;; Proves the core form-context-request-event bug fix: before the fix, any
  ;; referrer-policy string other than the literal "no-referrer" fell through
  ;; to sending the full page-url unconditionally, so referrerpolicy="origin"
  ;; was silently ignored on the context-request path. It must now trim to
  ;; the origin.
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200 :body (str "<main>" url "</main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/default\" method=\"get\" target=\"_self\" referrerpolicy=\"origin\"><input id=\"q\" name=\"q\" value=\"target\"><button id=\"go\" formtarget=\"_blank\" name=\"action\" value=\"open\">Open</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://app.example/default?q=target&action=open"]
    (is (= [] @calls))
    (is (= {:event :form/context-request
            :url expected-url
            :method :get
            :target "_blank"
            :referrer-policy "origin"
            :referrer "https://app.example"
            :enctype "application/x-www-form-urlencoded"
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "target" :node/id q}
                        {:name "action" :value "open" :node/id go}]}
           (first (filter #(= :form/context-request (:event %))
                          (:browser.session/history submitted)))))))

(deftest submitter-formtarget-cross-origin-no-policy-sends-origin-only-referer
  ;; No explicit referrerpolicy + cross-origin destination on the
  ;; context-request path -> origin-only referer, same default as the other
  ;; two referrer code paths.
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200 :body (str "<main>" url "</main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"https://other.example/default\" method=\"get\" target=\"_self\"><input id=\"q\" name=\"q\" value=\"target\"><button id=\"go\" formtarget=\"_blank\" name=\"action\" value=\"open\">Open</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://other.example/default?q=target&action=open"]
    (is (= [] @calls))
    (is (= {:event :form/context-request
            :url expected-url
            :method :get
            :target "_blank"
            :referrer-policy nil
            :referrer "https://app.example"
            :enctype "application/x-www-form-urlencoded"
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "target" :node/id q}
                        {:name "action" :value "open" :node/id go}]}
           (first (filter #(= :form/context-request (:event %))
                          (:browser.session/history submitted)))))))

(deftest submitter-formtarget-https-to-http-downgrade-suppresses-referer
  ;; Downgrade suppression on the context-request path: same host, HTTPS ->
  ;; HTTP, so no referrer at all.
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200 :body (str "<main>" url "</main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"http://app.example/default\" method=\"get\" target=\"_self\"><input id=\"q\" name=\"q\" value=\"target\"><button id=\"go\" formtarget=\"_blank\" name=\"action\" value=\"open\">Open</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "http://app.example/default?q=target&action=open"]
    (is (= [] @calls))
    (is (= {:event :form/context-request
            :url expected-url
            :method :get
            :target "_blank"
            :referrer-policy nil
            :referrer nil
            :enctype "application/x-www-form-urlencoded"
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "target" :node/id q}
                        {:name "action" :value "open" :node/id go}]}
           (first (filter #(= :form/context-request (:event %))
                          (:browser.session/history submitted)))))))

(deftest unknown-formmethod-falls-back-to-get-navigation
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200 :body (str "<main>" url "</main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/default\" method=\"bogus\"><input id=\"q\" name=\"q\" value=\"fallback\"><button id=\"go\" formmethod=\"also-bogus\" name=\"action\" value=\"go\">Go</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://app.example/default?q=fallback&action=go"]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/form"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (= :form/submit-navigation
           (:event (first (filter #(= :form/submit-navigation (:event %))
                                  (:browser.session/history submitted))))))))

(deftest dialog-formmethod-records-submit-without-fetch-or-navigation
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :body "<main>unexpected</main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/submit\" method=\"dialog\" target=\"modal\"><input id=\"q\" name=\"q\" value=\"dialog\"><button id=\"go\" name=\"action\" value=\"close\">Close</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})]
    (is (empty? @calls))
    (is (= "https://app.example/form" (get-in submitted [:browser.session/page :browser/url])))
    (is (= {:event :form/submit-dialog
            :target "modal"
            :enctype "application/x-www-form-urlencoded"
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "dialog" :node/id q}
                        {:name "action" :value "close" :node/id go}]}
           (first (filter #(= :form/submit-dialog (:event %))
                          (:browser.session/history submitted)))))))

(deftest form-attribute-external-submitter-navigates-with-associated-controls
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200 :body (str "<main>" url "</main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/submit\" method=\"get\"><input id=\"inside\" name=\"inside\" value=\"one\"></form><input id=\"outside\" form=\"form\" name=\"outside\" value=\"two\"><button id=\"disabled-go\" form=\"form\" disabled formmethod=\"post\" formaction=\"/disabled\" name=\"action\" value=\"disabled\">Disabled</button><button id=\"go\" form=\"form\" name=\"action\" value=\"external\">Go</button></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        inside (bridge/query-selector document "#inside")
        outside (bridge/query-selector document "#outside")
        disabled-go (bridge/query-selector document "#disabled-go")
        go (bridge/query-selector document "#go")
        disabled-go-op (some #(when (and (= :node (:draw/op %))
                                         (= disabled-go (:id %)))
                                %)
                             (:browser/draw-ops page))
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        disabled-submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                                        :x (+ (:x disabled-go-op) 2)
                                                                        :y (+ (:y disabled-go-op) 2)})
        calls-after-disabled @calls
        submitted (session/apply-document-input-event! disabled-submitted {:event/type :pointer/click
                                                                          :x (+ (:x go-op) 2)
                                                                          :y (+ (:y go-op) 2)})
        expected-url "https://app.example/submit?inside=one&outside=two&action=external"]
    (is (empty? calls-after-disabled))
    (is (= disabled-go (-> disabled-submitted :browser.session/history last :node/id)))
    (is (= false (-> disabled-submitted :browser.session/history last :handled?)))
    (is (nil? (get-in disabled-submitted [:browser.session/document-input-result :submitted?])))
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/form"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (= [{:name "inside" :value "one" :node/id inside}
            {:name "outside" :value "two" :node/id outside}
            {:name "action" :value "external" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))))

(deftest fieldset-disabled-external-associated-control-is-omitted-from-submit
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200 :body (str "<main>" url "</main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"external\" action=\"/submit\" method=\"get\"><button id=\"go\" name=\"action\" value=\"go\">Go</button></form><fieldset disabled><legend><input id=\"legend-external\" form=\"external\" name=\"legend\" value=\"ok\"></legend><input id=\"blocked\" form=\"external\" name=\"blocked\" value=\"blocked\" required></fieldset><input id=\"outside\" form=\"external\" name=\"outside\" value=\"ok\"></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        legend-external (bridge/query-selector document "#legend-external")
        blocked (bridge/query-selector document "#blocked")
        outside (bridge/query-selector document "#outside")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://app.example/submit?action=go&outside=ok&legend=ok"]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/form"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (= [{:name "action" :value "go" :node/id go}
            {:name "outside" :value "ok" :node/id outside}
            {:name "legend" :value "ok" :node/id legend-external}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (nil? (some #(= blocked (:node/id %))
                    (get-in submitted [:browser.session/document-input-result :form/data]))))
    (is (nil? (get-in submitted [:browser.session/document-input-result :invalid?])))))

(deftest document-reset-button-resets-and-commits-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://reset"
                                        :html "<main><form id=\"form\"><input id=\"field\" name=\"q\" value=\"initial\"><input id=\"amount\" type=\"number\" name=\"amount\" value=\"7\"><input id=\"volume\" type=\"range\" name=\"volume\" value=\"4\"><input id=\"flag\" type=\"checkbox\"><select id=\"tags\" name=\"tag\" multiple><option id=\"tag-one\" value=\"one\" selected>One</option><option id=\"tag-two\" value=\"two\" selected>Two</option><optgroup disabled><option id=\"tag-locked\" value=\"locked\" selected>Locked</option></optgroup><option id=\"tag-three\" value=\"three\">Three</option></select><button id=\"reset\" type=\"reset\">Reset</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        form (bridge/query-selector document "#form")
        field (bridge/query-selector document "#field")
        amount (bridge/query-selector document "#amount")
        volume (bridge/query-selector document "#volume")
        flag (bridge/query-selector document "#flag")
        tags (bridge/query-selector document "#tags")
        tag-one (bridge/query-selector document "#tag-one")
        tag-two (bridge/query-selector document "#tag-two")
        tag-locked (bridge/query-selector document "#tag-locked")
        tag-three (bridge/query-selector document "#tag-three")
        reset (bridge/query-selector document "#reset")
        document (-> document
                     (dom/set-attribute field :value "changed")
                     (dom/set-attribute amount :value "99")
                     (dom/set-attribute volume :value "10")
                     (dom/set-attribute flag :checked true)
                     (dom/set-attribute tag-one :selected false)
                     (dom/set-attribute tag-two :selected false)
                     (dom/set-attribute tag-locked :selected false)
                     (dom/set-attribute tag-three :selected true)
                     (dom/set-attribute tags :value "three")
                     (dom/add-event-listener form "reset" 44))
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        reset-op (some #(when (and (= :node (:draw/op %))
                                   (= reset (:id %)))
                          %)
                       (:browser/draw-ops page))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :x (+ (:x reset-op) 2)
                                                           :y (+ (:y reset-op) 2)})
        document (get-in result [:browser.session/page :browser/document])
        recorded (host/recorded h)]
    (is (= true (get-in result [:browser.session/document-input-result :reset?])))
    (is (= form (get-in result [:browser.session/document-input-result :form/id])))
    (is (= "initial" (get-in document [:nodes field :attrs :value])))
    (is (= "7" (get-in document [:nodes amount :attrs :value])))
    (is (= "4" (get-in document [:nodes volume :attrs :value])))
    (is (= false (get-in document [:nodes flag :attrs :checked])))
    (is (= true (get-in document [:nodes tag-one :attrs :selected])))
    (is (= true (get-in document [:nodes tag-two :attrs :selected])))
    (is (= true (get-in document [:nodes tag-locked :attrs :selected])))
    (is (= false (get-in document [:nodes tag-three :attrs :selected])))
    (is (= "one" (get-in document [:nodes tags :attrs :value])))
    (is (= [:dom/dispatch-event
            44
            {:event/type "reset" :target/id form :resetter/id reset
             :x (+ (:x reset-op) 2)
             :y (+ (:y reset-op) 2)}]
           (last (get-in result [:browser.session/document-input-result :document :ops]))))
    (is (= true (-> result :browser.session/history last :handled?)))
    (is (= 3 (:present-count recorded)))))

(deftest external-resetter-resets-associated-form-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://reset"
                                        :html "<main><form id=\"form\"><input id=\"inside\" name=\"inside\" value=\"initial\"><input id=\"foreign-inside\" form=\"other\" name=\"foreign\" value=\"foreign-initial\"></form><form id=\"other\"></form><input id=\"outside\" form=\"form\" name=\"outside\" value=\"external\"><button id=\"reset\" type=\"reset\" form=\"form\">Reset</button><button id=\"other-reset\" type=\"reset\" form=\"other\">Other reset</button><button id=\"disabled-reset\" type=\"reset\" form=\"form\" disabled>Disabled</button></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        form (bridge/query-selector document "#form")
        other (bridge/query-selector document "#other")
        inside (bridge/query-selector document "#inside")
        foreign-inside (bridge/query-selector document "#foreign-inside")
        outside (bridge/query-selector document "#outside")
        reset (bridge/query-selector document "#reset")
        other-reset (bridge/query-selector document "#other-reset")
        disabled-reset (bridge/query-selector document "#disabled-reset")
        document (-> document
                     (dom/set-attribute inside :value "changed-inside")
                     (dom/set-attribute foreign-inside :value "changed-foreign")
                     (dom/set-attribute outside :value "changed-outside")
                     (dom/add-event-listener form "reset" 45)
                     (dom/add-event-listener other "reset" 46))
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        reset-op (some #(when (and (= :node (:draw/op %))
                                   (= reset (:id %)))
                          %)
                       (:browser/draw-ops page))
        disabled-reset-op (some #(when (and (= :node (:draw/op %))
                                            (= disabled-reset (:id %)))
                                   %)
                                (:browser/draw-ops page))
        disabled-result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                                     :x (+ (:x disabled-reset-op) 2)
                                                                     :y (+ (:y disabled-reset-op) 2)})
        result (session/apply-document-input-event! disabled-result {:event/type :pointer/click
                                                                     :x (+ (:x reset-op) 2)
                                                                     :y (+ (:y reset-op) 2)})
        other-result (session/apply-document-input-event! result {:event/type :pointer/click
                                                                  :node/id other-reset})
        document (get-in result [:browser.session/page :browser/document])
        other-document (get-in other-result [:browser.session/page :browser/document])
        recorded (host/recorded h)]
    (is (= disabled-reset (-> disabled-result :browser.session/history last :node/id)))
    (is (= false (-> disabled-result :browser.session/history last :handled?)))
    (is (nil? (get-in disabled-result [:browser.session/document-input-result :reset?])))
    (is (= "changed-foreign" (get-in disabled-result [:browser.session/page :browser/document :nodes foreign-inside :attrs :value])))
    (is (= true (get-in result [:browser.session/document-input-result :reset?])))
    (is (= form (get-in result [:browser.session/document-input-result :form/id])))
    (is (= "initial" (get-in document [:nodes inside :attrs :value])))
    (is (= "changed-foreign" (get-in document [:nodes foreign-inside :attrs :value])))
    (is (= "external" (get-in document [:nodes outside :attrs :value])))
    (is (= [:dom/dispatch-event
            45
            {:event/type "reset" :target/id form :resetter/id reset
             :x (+ (:x reset-op) 2)
             :y (+ (:y reset-op) 2)}]
           (last (get-in result [:browser.session/document-input-result :document :ops]))))
    (is (= true (get-in other-result [:browser.session/document-input-result :reset?])))
    (is (= other (get-in other-result [:browser.session/document-input-result :form/id])))
    (is (= "foreign-initial" (get-in other-document [:nodes foreign-inside :attrs :value])))
    (is (= [:dom/dispatch-event
            46
            {:event/type "reset" :target/id other :resetter/id other-reset}]
           (last (get-in other-result [:browser.session/document-input-result :document :ops]))))
    (is (= 4 (:present-count recorded)))))

(deftest disabled-reset-button-does-not-reset-or-commit-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://reset"
                                        :html "<main><form id=\"form\"><input id=\"field\" name=\"q\" value=\"initial\"><button id=\"reset\" type=\"reset\" disabled>Reset</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        field (bridge/query-selector document "#field")
        reset (bridge/query-selector document "#reset")
        document (dom/set-attribute document field :value "changed")
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        reset-op (some #(when (and (= :node (:draw/op %))
                                   (= reset (:id %)))
                          %)
                       (:browser/draw-ops page))
        result (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                           :x (+ (:x reset-op) 2)
                                                           :y (+ (:y reset-op) 2)})
        document (get-in result [:browser.session/page :browser/document])
        recorded (host/recorded h)]
    (is (= reset (-> result :browser.session/history last :node/id)))
    (is (= false (-> result :browser.session/history last :handled?)))
    (is (nil? (get-in result [:browser.session/document-input-result :reset?])))
    (is (= "changed" (get-in document [:nodes field :attrs :value])))
    (is (= 2 (:present-count recorded)))))

(deftest disabled-focused-reset-key-does-not-reset-or-commit-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://reset"
                                        :html "<main><form id=\"form\"><input id=\"field\" name=\"q\" value=\"initial\"><button id=\"reset\" type=\"reset\" disabled>Reset</button></form></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        field (bridge/query-selector document "#field")
        reset (bridge/query-selector document "#reset")
        document (-> document
                     (dom/set-attribute field :value "changed")
                     (assoc :focus reset))
        loaded (session/commit-document! loaded document)
        result (session/apply-document-input-event! loaded {:event/type :key/down
                                                           :key " "})
        document (get-in result [:browser.session/page :browser/document])
        recorded (host/recorded h)]
    (is (= reset (-> result :browser.session/history last :node/id)))
    (is (= false (-> result :browser.session/history last :handled?)))
    (is (nil? (get-in result [:browser.session/document-input-result :reset?])))
    (is (= reset (get-in document [:focus])))
    (is (= "changed" (get-in document [:nodes field :attrs :value])))
    (is (= 2 (:present-count recorded)))))

(deftest document-select-change-commits-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://select"
                                        :html "<main><form id=\"form\"><select id=\"mode\" name=\"mode\"><option id=\"one\" value=\"one\" selected>One</option><option id=\"two\" value=\"two\">Two</option><option id=\"locked\" value=\"locked\" disabled>Locked</option></select></form></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        select (bridge/query-selector document "#mode")
        one (bridge/query-selector document "#one")
        two (bridge/query-selector document "#two")
        locked (bridge/query-selector document "#locked")
        result (session/apply-document-input-event! loaded {:event/type :select/change
                                                           :node/selector "#mode"
                                                           :value "two"})
        locked-result (session/apply-document-input-event! result {:event/type :select/change
                                                                   :node/selector "#mode"
                                                                   :value "locked"})
        document (get-in result [:browser.session/page :browser/document])
        locked-document (get-in locked-result [:browser.session/page :browser/document])
        recorded (host/recorded h)]
    (is (= true (get-in result [:browser.session/document-input-result :selected?])))
    (is (= select (get-in result [:browser.session/document-input-result :node/id])))
    (is (= two (get-in result [:browser.session/document-input-result :option/id])))
    (is (= false (get-in document [:nodes one :attrs :selected])))
    (is (= true (get-in document [:nodes two :attrs :selected])))
    (is (= "two" (get-in document [:nodes select :attrs :value])))
    (is (= true (-> result :browser.session/history last :handled?)))
    (is (= false (-> locked-result :browser.session/history last :handled?)))
    (is (= locked (-> locked-result :browser.session/history last :node/id)))
    (is (= false (get-in locked-document [:nodes locked :attrs :selected])))
    (is (= "two" (get-in locked-document [:nodes select :attrs :value])))
    (is (= 2 (:present-count recorded)))))

(deftest disabled-selected-option-is-omitted-from-session-submit-query
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/submit\" method=\"get\" novalidate><select id=\"mode\" name=\"mode\" required><option value=\"locked\" selected disabled>Locked</option><option value=\"go\">Go</option></select><select id=\"optional\" name=\"optional\"><option value=\"secret\" selected disabled>Secret</option><option value=\"public\">Public</option></select><button id=\"go\" name=\"action\" value=\"go\">Go</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        mode (bridge/query-selector document "#mode")
        optional (bridge/query-selector document "#optional")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://app.example/submit?action=go"]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/form"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (= [{:name "action" :value "go" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (nil? (some #(= mode (:node/id %))
                    (get-in submitted [:browser.session/document-input-result :form/data]))))
    (is (nil? (some #(= optional (:node/id %))
                    (get-in submitted [:browser.session/document-input-result :form/data]))))))

(deftest optgroup-disabled-option-is-omitted-from-session-submit-query
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/submit\" method=\"get\" novalidate><select id=\"mode\" name=\"mode\" required><optgroup disabled><option id=\"locked\" value=\"locked\" selected>Locked</option></optgroup><option id=\"open\" value=\"open\">Open</option></select><button id=\"go\" name=\"action\" value=\"go\">Go</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        mode (bridge/query-selector document "#mode")
        locked (bridge/query-selector document "#locked")
        go (bridge/query-selector document "#go")
        locked-result (session/apply-document-input-event! loaded {:event/type :select/change
                                                                   :node/selector "#mode"
                                                                   :value "locked"})
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! locked-result {:event/type :pointer/click
                                                                      :x (+ (:x go-op) 2)
                                                                      :y (+ (:y go-op) 2)})
        expected-url "https://app.example/submit?action=go"]
    (is (= false (-> locked-result :browser.session/history last :handled?)))
    (is (= locked (-> locked-result :browser.session/history last :node/id)))
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/form"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (= [{:name "action" :value "go" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (nil? (some #(= mode (:node/id %))
                    (get-in submitted [:browser.session/document-input-result :form/data]))))))

(deftest multiple-select-submits-repeated-query-entries
  (let [h (host/recording-host)
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :fetch-fn (fn [{:keys [url] :as req}]
                                 (swap! calls conj req)
                                 {:status 200
                                  :body (str "<main><p>" url "</p></main>")})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/submit\" method=\"get\"><select id=\"tags\" name=\"tag\" multiple required><option value=\"one\" selected>One</option><option value=\"two\" selected>Two</option><option value=\"locked\" selected disabled>Locked</option><option value=\"three\">Three</option></select><button id=\"go\" name=\"action\" value=\"go\">Go</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        tags (bridge/query-selector document "#tags")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        expected-url "https://app.example/submit?tag=one&tag=two&action=go"]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:headers {"referer" "https://app.example/form"}
             :url expected-url :method :get}]
           @calls))
    (is (= expected-url (get-in submitted [:browser.session/page :browser/url])))
    (is (= [{:name "tag" :value "one" :node/id tags}
            {:name "tag" :value "two" :node/id tags}
            {:name "action" :value "go" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))))

(deftest disabled-select-change-does-not-commit-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://select"
                                        :html "<main><select id=\"mode\" disabled><option id=\"one\" value=\"one\" selected>One</option><option id=\"two\" value=\"two\">Two</option></select></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        select (bridge/query-selector document "#mode")
        one (bridge/query-selector document "#one")
        two (bridge/query-selector document "#two")
        result (session/apply-document-input-event! loaded {:event/type :select/change
                                                           :node/selector "#mode"
                                                           :value "two"})
        document (get-in result [:browser.session/page :browser/document])
        recorded (host/recorded h)]
    (is (= false (-> result :browser.session/history last :handled?)))
    (is (= two (-> result :browser.session/history last :node/id)))
    (is (nil? (get-in document [:focus])))
    (is (= true (get-in document [:nodes one :attrs :selected])))
    (is (= false (get-in document [:nodes two :attrs :selected])))
    (is (= "one" (get-in document [:nodes select :attrs :value])))
    (is (= 1 (:present-count recorded)))))

(deftest fieldset-disabled-select-change-does-not-commit-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://select"
                                        :html "<main><fieldset disabled><legend><select id=\"legend-mode\"><option id=\"legend-one\" value=\"one\" selected>One</option><option id=\"legend-two\" value=\"two\">Two</option></select></legend><select id=\"mode\"><option id=\"one\" value=\"one\" selected>One</option><option id=\"two\" value=\"two\">Two</option></select></fieldset></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        select (bridge/query-selector document "#mode")
        one (bridge/query-selector document "#one")
        two (bridge/query-selector document "#two")
        result (session/apply-document-input-event! loaded {:event/type :select/change
                                                           :node/selector "#mode"
                                                           :value "two"})
        document (get-in result [:browser.session/page :browser/document])
        recorded (host/recorded h)]
    (is (= false (-> result :browser.session/history last :handled?)))
    (is (= two (-> result :browser.session/history last :node/id)))
    (is (nil? (get-in document [:focus])))
    (is (= true (get-in document [:nodes one :attrs :selected])))
    (is (= false (get-in document [:nodes two :attrs :selected])))
    (is (= "one" (get-in document [:nodes select :attrs :value])))
    (is (= 1 (:present-count recorded)))))

(deftest post-form-submit-fetches-through-net-policy-and-commits-response
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        store (-> (storage/empty-store)
                  (storage/put-value p "https://app.example/post" net/cookie-key {"sid" "abc"}))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store store
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {"set-cookie" "submitted=yes; Path=/"}
                                  :body "<main><p>Posted</p></main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/post\" method=\"post\"><input id=\"q\" name=\"q\" value=\"hello world\"><button id=\"go\" name=\"action\" value=\"save\">Save</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        recorded (host/recorded h)]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:url "https://app.example/post"
             :method :post
             :headers {"content-type" "application/x-www-form-urlencoded"
                       "referer" "https://app.example/form"
                       "cookie" "sid=abc"}
             :body "q=hello+world&action=save"}]
           @calls))
    (is (= "Posted" (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= "https://app.example/post" (get-in submitted [:browser.session/page :browser/url])))
    (is (= {"sid" "abc" "submitted" "yes"}
           (storage/get-value (:browser.session/store submitted)
                              p
                              "https://app.example/post"
                              net/cookie-key)))
    (is (= [{:name "q" :value "hello world" :node/id q}
            {:name "action" :value "save" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (= {:event :form/submit-fetch
            :url "https://app.example/post"
            :method :post
            :target "_self"
            :enctype "application/x-www-form-urlencoded"
            :status 200
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "hello world" :node/id q}
                        {:name "action" :value "save" :node/id go}]}
           (first (filter #(= :form/submit-fetch (:event %))
                          (:browser.session/history submitted)))))
    (is (= 3 (:present-count recorded)))))

(deftest post-formenctype-text-plain-overrides-urlencoded-body
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                   {:host h
                    :profile p
                    :store (storage/empty-store)
                    :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {}
                                  :body "<main><p>Posted plain</p></main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/post\" method=\"post\" enctype=\"application/x-www-form-urlencoded\"><input id=\"q\" name=\"q\" value=\"hello world\"><button id=\"go\" name=\"action\" value=\"save\" formenctype=\"text/plain\" formtarget=\"_self\">Save</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= [{:url "https://app.example/post"
             :method :post
             :headers {"content-type" "text/plain"
                       "referer" "https://app.example/form"}
             :body "q=hello world\r\naction=save"}]
           @calls))
    (is (= "Posted plain" (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= {:event :form/submit-fetch
            :url "https://app.example/post"
            :method :post
            :target "_self"
            :enctype "text/plain"
            :status 200
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "hello world" :node/id q}
                        {:name "action" :value "save" :node/id go}]}
           (first (filter #(= :form/submit-fetch (:event %))
                          (:browser.session/history submitted)))))))

(deftest post-form-referrerpolicy-origin-sends-origin-referer
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {}
                                  :body "<main><p>Posted with referrer</p></main>"})})
                   (session/load-html! {:url "https://app.example/form/page.html"
                                        :html "<main><form id=\"form\" action=\"/post\" method=\"post\" referrerpolicy=\"origin\"><input id=\"q\" name=\"q\" value=\"hello\"><button id=\"go\">Save</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})]
    (is (= [{:url "https://app.example/post"
             :method :post
             :headers {"content-type" "application/x-www-form-urlencoded"
                       "referer" "https://app.example"}
             :body "q=hello"}]
           @calls))
    (is (= "Posted with referrer"
           (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))))

(deftest post-form-referrerpolicy-no-referrer-suppresses-referer
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {}
                                  :body "<main><p>Posted without referrer</p></main>"})})
                   (session/load-html! {:url "https://app.example/form/page.html"
                                        :html "<main><form id=\"form\" action=\"/post\" method=\"post\" referrerpolicy=\"no-referrer\"><input id=\"q\" name=\"q\" value=\"hello\"><button id=\"go\">Save</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})]
    (is (= [{:url "https://app.example/post"
             :method :post
             :headers {"content-type" "application/x-www-form-urlencoded"}
             :body "q=hello"}]
           @calls))
    (is (= "Posted without referrer"
           (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))))

(deftest post-form-non-current-target-records-context-request-without-fetch
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {}
                                  :body "<main><p>Posted target</p></main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/post\" method=\"post\" enctype=\"application/x-www-form-urlencoded\" target=\"plain-frame\"><input id=\"q\" name=\"q\" value=\"hello world\"><button id=\"go\" name=\"action\" value=\"save\" formenctype=\"text/plain\">Save</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        recorded (host/recorded h)]
    (is (= [] @calls))
    (is (= "https://app.example/form"
           (get-in submitted [:browser.session/page :browser/url])))
    (is (= 2 (:present-count recorded)))
    (is (= {:event :form/context-request
            :url "https://app.example/post"
            :method :post
            :target "plain-frame"
            :referrer-policy nil
            :referrer "https://app.example/form"
            :enctype "text/plain"
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "hello world" :node/id q}
                        {:name "action" :value "save" :node/id go}]
            :headers {"content-type" "text/plain"}
            :body "q=hello world\r\naction=save"}
           (first (filter #(= :form/context-request (:event %))
                          (:browser.session/history submitted)))))))

(deftest post-multipart-form-data-omits-file-input-without-picker-capability
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {}
                                  :body "<main><p>Uploaded</p></main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\"><input id=\"q\" name=\"q\" value=\"hello world\"><input id=\"upload\" type=\"file\" name=\"upload\" value=\"/secret/path.txt\"><button id=\"go\" name=\"action\" value=\"save\">Save</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        upload (bridge/query-selector document "#upload")
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        req (first @calls)]
    (is (= "https://app.example/upload" (:url req)))
    (is (= :post (:method req)))
    ;; No explicit referrerpolicy + same-origin destination -> full page-url referer.
    (is (= {"content-type" "multipart/form-data; boundary=kotoba-browser-form-boundary"
            "referer" "https://app.example/form"}
           (:headers req)))
    (is (re-find #"Content-Disposition: form-data; name=\"q\"\r\n\r\nhello world" (:body req)))
    (is (re-find #"Content-Disposition: form-data; name=\"action\"\r\n\r\nsave" (:body req)))
    (is (str/ends-with? (:body req) "--kotoba-browser-form-boundary--\r\n"))
    (is (not (str/includes? (:body req) "upload")))
    (is (not (str/includes? (:body req) "/secret/path.txt")))
    (is (= "Uploaded" (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= [{:name "q" :value "hello world" :node/id q}
            {:name "action" :value "save" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))
    (is (nil? (some #(= upload (:node/id %))
                    (get-in submitted [:browser.session/document-input-result :form/data]))))
    (is (= "multipart/form-data"
           (:enctype (first (filter #(= :form/submit-fetch (:event %))
                                    (:browser.session/history submitted))))))))

(deftest post-multipart-form-data-includes-explicit-file-picker-metadata
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {}
                                  :body "<main><p>Uploaded</p></main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\"><input id=\"q\" name=\"q\" value=\"hello world\"><input id=\"upload\" type=\"file\" name=\"upload\" value=\"/secret/path.txt\"><button id=\"go\" name=\"action\" value=\"save\">Save</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        q (bridge/query-selector document "#q")
        upload (bridge/query-selector document "#upload")
        go (bridge/query-selector document "#go")
        selected (session/apply-document-input-event! loaded {:event/type :file/select
                                                             :node/id upload
                                                             :files [{:name "/Users/me/report.csv"
                                                                      :type "text/csv"
                                                                      :size 42}]})
        submitted (session/apply-document-input-event! selected {:event/type :pointer/click
                                                                :node/id go})
        req (first @calls)
        form-data (get-in submitted [:browser.session/document-input-result :form/data])]
    (is (= "report.csv"
           (get-in selected [:browser.session/page :browser/document :nodes upload :attrs :value])))
    (is (= [{:file/name "report.csv"
             :file/type "text/csv"
             :file/size 42}]
           (get-in selected [:browser.session/page :browser/document :nodes upload :attrs :files])))
    (is (re-find #"Content-Disposition: form-data; name=\"upload\"\r\n\r\nreport.csv" (:body req)))
    (is (not (str/includes? (:body req) "/Users/me")))
    (is (not (str/includes? (:body req) "/secret/path.txt")))
    (is (= [{:name "q" :value "hello world" :node/id q}
            {:name "upload"
             :value "report.csv"
             :node/id upload
             :file/name "report.csv"
             :file/type "text/csv"
             :file/size 42
             :file/last-modified nil}
            {:name "action" :value "save" :node/id go}]
           form-data))
    (is (= "Uploaded" (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))))

(deftest post-form-submit-denied-by-permission-does-not-call-host-fetch
  (let [h (host/recording-host)
        p (profile/new-profile {:id "default"})
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200 :headers {} :body "<main>Nope</main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"https://api.example/post\" method=\"post\"><input id=\"q\" name=\"q\" value=\"secret\"><button id=\"go\">Send</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        blocked (first (filter #(= :form/submit-fetch-blocked (:event %))
                               (:browser.session/history submitted)))
        recorded (host/recorded h)]
    (is (empty? @calls))
    (is (= "https://app.example/form" (get-in submitted [:browser.session/page :browser/url])))
    (is (= :permission/not-granted (:error blocked)))
    (is (= false (:allowed? blocked)))
    (is (= 2 (:present-count recorded)))))

(deftest cross-origin-post-form-submit-is-blocked-without-cors
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://api.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {}
                                  :body "<main>Blocked by CORS</main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"https://api.example/post\" method=\"post\"><input id=\"q\" name=\"q\" value=\"secret\"><button id=\"go\">Send</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        blocked (first (filter #(= :form/submit-fetch-blocked (:event %))
                               (:browser.session/history submitted)))
        recorded (host/recorded h)]
    ;; No explicit referrerpolicy + cross-origin destination -> the real
    ;; strict-origin-when-cross-origin default trims the referer to just the
    ;; source origin (not the full page-url).
    (is (= [{:url "https://api.example/post"
             :method :post
             :headers {"content-type" "application/x-www-form-urlencoded"
                       "origin" "https://app.example"
                       "referer" "https://app.example"}
             :body "q=secret"}]
           @calls))
    (is (= "https://app.example/form" (get-in submitted [:browser.session/page :browser/url])))
    (is (= :cors/blocked (:error blocked)))
    (is (= false (:allowed? blocked)))
    (is (= 2 (:present-count recorded)))))

(deftest cross-origin-post-form-submit-commits-when-cors-allows-origin
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://api.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {"access-control-allow-origin" (get-in req [:headers "origin"])}
                                  :body "<main><p>Cross posted</p></main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"https://api.example/post\" method=\"post\"><input id=\"q\" name=\"q\" value=\"secret\"><button id=\"go\">Send</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        recorded (host/recorded h)]
    ;; No explicit referrerpolicy + cross-origin destination -> the real
    ;; strict-origin-when-cross-origin default trims the referer to just the
    ;; source origin (not the full page-url).
    (is (= [{:url "https://api.example/post"
             :method :post
             :headers {"content-type" "application/x-www-form-urlencoded"
                       "origin" "https://app.example"
                       "referer" "https://app.example"}
             :body "q=secret"}]
           @calls))
    (is (= "https://api.example/post" (get-in submitted [:browser.session/page :browser/url])))
    (is (= "Cross posted" (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= :form/submit-fetch
           (:event (first (filter #(= :form/submit-fetch (:event %))
                                  (:browser.session/history submitted))))))
    (is (= 3 (:present-count recorded)))))

(deftest post-form-submit-redirect-enters-navigation-lifecycle
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :fetch-fn (fn [{:keys [url method] :as req}]
                                 (swap! calls conj req)
                                 (case [method url]
                                   [:post "https://app.example/post"]
                                   {:status 303
                                    :headers {"LoCaTiOn" "/done"}
                                    :body ""}
                                   [:get "https://app.example/done"]
                                   {:status 200
                                    :headers {}
                                    :body "<main><p>Done</p></main>"}))})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/post\" method=\"post\"><input id=\"q\" name=\"q\" value=\"redirect\"><button id=\"go\">Send</button></form></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        go (bridge/query-selector document "#go")
        go-op (some #(when (and (= :node (:draw/op %))
                                (= go (:id %)))
                       %)
                    (:browser/draw-ops page))
        submitted (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                              :x (+ (:x go-op) 2)
                                                              :y (+ (:y go-op) 2)})
        redirect (first (filter #(= :form/submit-redirect (:event %))
                                (:browser.session/history submitted)))
        recorded (host/recorded h)]
    ;; No explicit referrerpolicy + same-origin destination -> full page-url
    ;; referer on the initial POST. The follow-up redirect GET goes through
    ;; navigate! (not form-submit-request/request-referrer), so it is
    ;; unaffected and keeps sending no referer.
    (is (= [{:url "https://app.example/post"
             :method :post
             :headers {"content-type" "application/x-www-form-urlencoded"
                       "referer" "https://app.example/form"}
             :body "q=redirect"}
            {:url "https://app.example/done" :method :get}]
           @calls))
    (is (= {:event :form/submit-redirect
            :url "https://app.example/post"
            :to "https://app.example/done"
            :method :post
            :target "_self"
            :enctype "application/x-www-form-urlencoded"
            :status 303
            :form/id (bridge/query-selector document "#form")
            :submitter/id go
            :form/data [{:name "q" :value "redirect" :node/id (bridge/query-selector document "#q")}]}
           redirect))
    (is (= "Done" (-> submitted :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= "https://app.example/done" (get-in submitted [:browser.session/page :browser/url])))
    (is (= 3 (:present-count recorded)))))

(deftest document-radio-click-checks-group-and-commits-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://radio"
                                        :html "<main><input id=\"one\" type=\"radio\" name=\"mode\" checked><input id=\"two\" type=\"radio\" name=\"mode\"></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        one (bridge/query-selector document "#one")
        two (bridge/query-selector document "#two")
        two-op (some #(when (and (= :node (:draw/op %))
                                 (= two (:id %)))
                        %)
                     (:browser/draw-ops page))
        selected (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                             :x (+ (:x two-op) 2)
                                                             :y (+ (:y two-op) 2)})
        recorded (host/recorded h)]
    (is (= false (get-in selected [:browser.session/page :browser/document :nodes one :attrs :checked])))
    (is (= true (get-in selected [:browser.session/page :browser/document :nodes two :attrs :checked])))
    (is (= two (-> selected :browser.session/history last :node/id)))
    (is (= true (-> selected :browser.session/history last :handled?)))
    (is (= 2 (:present-count recorded)))))

(deftest focused-document-control-activates-from-keyboard-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://checkbox"
                                        :html "<main><input id=\"flag\" type=\"checkbox\"></main>"}))
        page (:browser.session/page loaded)
        document (:browser/document page)
        flag (bridge/query-selector document "#flag")
        flag-op (some #(when (and (= :node (:draw/op %))
                                  (= flag (:id %)))
                         %)
                      (:browser/draw-ops page))
        focused (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x (+ (:x flag-op) 2)
                                                            :y (+ (:y flag-op) 2)})
        activated (session/apply-document-input-event! focused {:event/type :key/down
                                                               :key " "})
        recorded (host/recorded h)]
    (is (= flag (get-in focused [:browser.session/page :browser/document :focus])))
    (is (= true (get-in focused [:browser.session/page :browser/document :nodes flag :attrs :checked])))
    (is (= false (get-in activated [:browser.session/page :browser/document :nodes flag :attrs :checked])))
    (is (= flag (-> activated :browser.session/history last :node/id)))
    (is (= true (-> activated :browser.session/history last :handled?)))
    (is (= 3 (:present-count recorded)))))

(deftest focused-document-control-dispatches-keyboard-listeners-through-session
  (let [h (host/recording-host)
        loaded (-> (session/new-session {:host h})
                   (session/load-html! {:url "kotoba://keyboard"
                                        :html "<main><input id=\"field\" value=\"abc\"></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        field (bridge/query-selector document "#field")
        document (-> document
                     (dom/add-event-listener field "keydown" 81)
                     (dom/add-event-listener field "beforeinput" 83)
                     (dom/add-event-listener field "keyup" 82))
        loaded (session/commit-document! loaded document)
        page (:browser.session/page loaded)
        field-op (some #(when (and (= :node (:draw/op %))
                                   (= field (:id %)))
                          %)
                       (:browser/draw-ops page))
        focused (session/apply-document-input-event! loaded {:event/type :pointer/click
                                                            :x (+ (:x field-op) 2)
                                                            :y (+ (:y field-op) 2)})
        down (session/apply-document-input-event! focused {:event/type :key/down
                                                          :key "Backspace"
                                                          :code "Backspace"})
        up (session/apply-document-input-event! down {:event/type :key/up
                                                      :key "Backspace"
                                                      :code "Backspace"})
        down-result (:browser.session/document-input-result down)
        up-result (:browser.session/document-input-result up)]
    (is (= field (get-in focused [:browser.session/page :browser/document :focus])))
    (is (= "ab" (get-in down [:browser.session/page :browser/document :nodes field :attrs :value])))
    (is (= field (-> down :browser.session/history last :node/id)))
    (is (= true (-> down :browser.session/history last :handled?)))
    (is (= [[:dom/dispatch-event
             81
             {:event/type "keydown"
              :target/id field
              :key "Backspace"
              :code "Backspace"}]
            [:dom/dispatch-event
             83
             {:event/type "beforeinput"
              :target/id field
              :inputType "deleteContentBackward"
              :value "abc"
              :selection/start 3
              :selection/end 3}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in down-result [:document :ops]))))
    (is (= field (-> up :browser.session/history last :node/id)))
    (is (= true (-> up :browser.session/history last :handled?)))
    (is (= [[:dom/dispatch-event
             82
             {:event/type "keyup"
              :target/id field
              :key "Backspace"
              :code "Backspace"}]]
           (filterv #(= :dom/dispatch-event (first %))
                    (get-in up-result [:document :ops]))))))

(deftest document-ime-events-commit-composition-through-session
  (let [h (host/recording-host)
        s (-> (session/new-session {:host h})
              (session/load-html! {:url "kotoba://form"
                                   :html "<main><input id=\"field\" value=\"\"></main>"})
              (session/apply-document-input-event! {:event/type :composition/start
                                                    :node/selector "#field"})
              (session/apply-document-input-event! {:event/type :composition/update
                                                    :node/selector "#field"
                                                    :text "ko"})
              (session/apply-document-input-event! {:event/type :composition/end
                                                    :node/selector "#field"
                                                    :text "こ"}))
        field (bridge/query-selector (get-in s [:browser.session/page :browser/document]) "#field")
        attrs (get-in s [:browser.session/page :browser/document :nodes field :attrs])
        recorded (host/recorded h)]
    (is (= "こ" (:value attrs)))
    (is (= 1 (:selection-start attrs)))
    (is (= "" (:composition attrs)))
    (is (= 4 (:present-count recorded)))
    (is (= [:page/commit
            :page/document-commit :document/input
            :page/document-commit :document/input
            :page/document-commit :document/input]
           (mapv :event (:browser.session/history s))))))

(deftest session-records-audit-datoms-for-page-surface-and-input
  (let [h (host/recording-host)
        acct (account/new-account {:id "local"})
        p (profile/new-profile {:id "default" :account acct})
        os (-> (surface/empty-surface {:title "Kotoba OS"})
               (surface/open-window {:app-id "editor" :title "Editor"}))
        s (-> (session/new-session {:host h :profile p :surface os})
              (session/load-html! {:url "kotoba://hello" :html "<main>Hello</main>"})
              (session/apply-input-event! {:capability "keyboard/type" :text "a"}))]
    (is (= {:page/commit 1 :surface/commit 1 :input/reduce 1}
           (audit/replay-summary (:browser.session/audit s))))
    (is (some #(= [:db/add "browser.audit/e3" :audit/event :input/reduce] %)
              (get-in s [:browser.session/audit :audit/datoms])))))

(deftest successful-page-commit-remembers-profile-navigation
  (let [h (host/recording-host)
        acct (account/new-account {:id "local"})
        p (profile/new-profile {:id "default" :account acct})
        s (session/new-session {:host h :profile p})
        s (session/load-html! s {:url "kotoba://docs"
                                 :html "<head><title>Docs</title></head><main>Docs</main>"})]
    (is (= [{:url "kotoba://docs" :title "Docs" :at 0}]
           (get-in s [:browser.session/profile :profile/history])))))

(deftest load-html-runs-discovered-page-scripts-through-runner
  (let [h (host/recording-host)
        scripts (atom [])
        s (session/new-session
           {:host h
            :script-runner (fn [session script]
                             (swap! scripts conj script)
                             (cond-> session
                               (not (:script/lifecycle-event script))
                               (update :browser.session/history conj
                                       {:event :script/run
                                        :script/type (:script/type script)})))})
        s (session/load-html! s {:url "kotoba://scripts"
                                 :html "<main><script type=\"module\">import './app.js';</script></main>"})]
    (is (= [{:event :script/run :script/type :module}]
           (filter #(= :script/run (:event %)) (:browser.session/history s))))
    (is (= [:module]
           (mapv :script/type (remove :script/lifecycle-event @scripts))))
    (is (= "import './app.js';" (-> @scripts first :script/source)))
    (is (= [["DOMContentLoaded" :document]
            ["load" :window]]
           (mapv (juxt :script/lifecycle-event :script/event-target)
                 (filter :script/lifecycle-event @scripts))))))

(deftest load-html-dispatches-page-lifecycle-after-discovered-scripts
  (let [h (host/recording-host)
        observed (atom [])
        ready-states (atom [])
        s (session/new-session
           {:host h
            :script-runner (fn [session script]
                             (swap! observed conj
                                    (or (:script/lifecycle-event script)
                                        (:script/source script)))
                             (swap! ready-states conj
                                    (get-in session [:browser.session/page
                                                     :browser/document
                                                     :ready-state]))
                             session)})
        s (session/load-html! s {:url "kotoba://scripts"
                                 :html "<main><script>globalThis.ready = true;</script></main>"})]
    (is (= ["globalThis.ready = true;"
            "DOMContentLoaded"
            "load"]
           @observed))
    (is (= ["loading" "interactive" "complete"]
           @ready-states))
    (is (= "complete"
           (get-in s [:browser.session/page :browser/document :ready-state])))
    (is (= [{:event :page/lifecycle-dispatch
             :event/target :document
             :event/type "DOMContentLoaded"
             :document/ready-state "interactive"}
            {:event :page/lifecycle-dispatch
             :event/target :window
             :event/type "load"
             :document/ready-state "complete"}]
           (filter #(= :page/lifecycle-dispatch (:event %))
                   (:browser.session/history s))))))

(deftest load-html-without-script-runner-still-completes-document-ready-state
  (let [h (host/recording-host)
        s (-> (session/new-session {:host h})
              (session/load-html! {:url "kotoba://ready"
                                   :html "<main>Ready</main>"}))]
    (is (= "complete"
           (get-in s [:browser.session/page :browser/document :ready-state])))
    (is (= "complete"
           (get-in s [:browser.session/navigation :entries 0
                      :page :browser/document :ready-state])))))

(deftest script-src-fetches-through-permission-and-caches-source
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        fetches (atom [])
        scripts (atom [])
        s (session/new-session
           {:host h
            :profile p
            :store (storage/empty-store)
            :fetch-fn (fn [req]
                        (swap! fetches conj req)
                        {:status 200
                         :body "localStorage.setItem('loaded', 'yes');"})
            :script-runner (fn [session script]
                             (swap! scripts conj script)
                             (cond-> session
                               (not (:script/lifecycle-event script))
                               (update :browser.session/history conj
                                       {:event :script/run
                                        :url (:script/url script)
                                        :cache-hit? (:script/cache-hit? script)})))})
        html "<main><script type=\"module\" src=\"/app.js\"></script></main>"
        s1 (session/load-html! s {:url "https://app.example/docs/index.html"
                                  :html html})
        s2 (session/load-html! s1 {:url "https://app.example/docs/index.html"
                                   :html html})]
    (is (= [{:url "https://app.example/app.js"
             :method :get
             :capability :script/src
             :referrer "https://app.example/docs/index.html"}]
           @fetches))
    (is (= ["localStorage.setItem('loaded', 'yes');"
            "localStorage.setItem('loaded', 'yes');"]
           (mapv :script/source (remove :script/lifecycle-event @scripts))))
    (is (= [nil true] (mapv :script/cache-hit? (remove :script/lifecycle-event @scripts))))
    ;; The per-origin storage slot holds a map keyed by the full script url
    ;; (not the bare source string) -- necessary so a second, different
    ;; script on the SAME origin gets its own cache entry instead of
    ;; colliding with this one (see script-src-cache-does-not-collide-
    ;; across-different-scripts-on-the-same-origin below).
    (is (= {"https://app.example/app.js" "localStorage.setItem('loaded', 'yes');"}
           (storage/get-value (:browser.session/store s2)
                              (:browser.session/profile s2)
                              "https://app.example/app.js"
                              :quickjs.script/source)))
    (is (= [:script/fetch :script/run :script/cache-hit :script/run]
           (->> (:browser.session/history s2)
                (filter #(contains? #{:script/fetch :script/cache-hit :script/run} (:event %)))
                (mapv :event))))))

(deftest script-src-cache-does-not-collide-across-different-scripts-on-the-same-origin
  ;; browser.storage keys every value by ORIGIN only (correct for
  ;; origin-scoped data like cookies) -- but the script-source cache used
  ;; a single fixed storage key for EVERY script, so two different
  ;; scripts served from the same origin (an extremely common real page
  ;; shape -- multiple <script src> tags from one CDN) collided: the
  ;; second script's cache lookup returned the FIRST script's cached
  ;; source instead of its own, and would have actually EXECUTED the
  ;; wrong code.
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://cdn.example" :net/fetch))
        scripts (atom [])
        s (session/new-session
           {:host h
            :profile p
            :store (storage/empty-store)
            :fetch-fn (fn [{:keys [url]}]
                        (case url
                          "https://cdn.example/lib-a.js"
                          {:status 200 :body "// lib A"}
                          "https://cdn.example/lib-b.js"
                          {:status 200 :body "// lib B, totally different"}
                          {:status 404 :body "missing"}))
            :script-runner (fn [session script]
                             (when (and (:script/source script)
                                        (not (:script/lifecycle-event script)))
                               (swap! scripts conj script))
                             session)})
        html (str "<main>"
                  "<script src=\"https://cdn.example/lib-a.js\"></script>"
                  "<script src=\"https://cdn.example/lib-b.js\"></script>"
                  "</main>")
        _s1 (session/load-html! s {:url "https://cdn.example/page" :html html})]
    (is (= [{:script/url "https://cdn.example/lib-a.js" :script/source "// lib A"}
            {:script/url "https://cdn.example/lib-b.js" :script/source "// lib B, totally different"}]
           (mapv #(select-keys % [:script/url :script/source]) @scripts)))))

(deftest script-src-resolves-against-document-base-uri
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://cdn.example" :net/fetch))
        fetches (atom [])
        scripts (atom [])
        s (session/new-session
           {:host h
            :profile p
            :store (storage/empty-store)
            :fetch-fn (fn [req]
                        (swap! fetches conj req)
                        {:status 200 :body "globalThis.baseLoaded = true;"})
            :script-runner (fn [session script]
                             (swap! scripts conj script)
                             session)})
        s (session/load-html! s {:url "https://app.example/docs/index.html"
                                 :html "<head><base href=\"https://cdn.example/assets/\"></head><main><script src=\"app.js\"></script></main>"})]
    (is (= "https://cdn.example/assets/"
           (get-in s [:browser.session/page :browser/document :base-uri])))
    (is (= [{:url "https://cdn.example/assets/app.js"
             :method :get
             :capability :script/src
             :referrer "https://app.example/docs/index.html"
             :headers {"origin" "https://app.example"}}]
           @fetches))
    (is (= ["https://cdn.example/assets/app.js"]
           (mapv :script/url (remove :script/lifecycle-event @scripts))))))

(deftest script-src-denied-by-permission-does-not-fetch-or-run
  (let [h (host/recording-host)
        p (profile/new-profile {:id "default"})
        fetches (atom [])
        scripts (atom [])
        s (session/new-session
           {:host h
            :profile p
            :store (storage/empty-store)
            :fetch-fn (fn [req]
                        (swap! fetches conj req)
                        {:status 200 :body "shouldNotRun();"})
            :script-runner (fn [session script]
                             (swap! scripts conj script)
                             session)})
        s (session/load-html! s {:url "https://app.example/docs/index.html"
                                 :html "<main><script src=\"/app.js\"></script></main>"})]
    (is (empty? @fetches))
    (is (= [["DOMContentLoaded" :document]
            ["load" :window]]
           (mapv (juxt :script/lifecycle-event :script/event-target)
                 @scripts)))
    (is (= :script/blocked
           (->> (:browser.session/history s)
                (filter #(= :script/blocked (:event %)))
                last
                :event)))
    (is (= 1 (:permission/decision
              (audit/replay-summary (:browser.session/audit s)))))))

(deftest session-owns-script-engine-lifecycle
  (let [h (host/recording-host)
        disposed (atom nil)
        engine (fn [_] {:result :ok :requests []})
        s (session/new-session
           {:host h
            :engine-factory (fn [session]
                              (is (= h (:browser.session/host session)))
                              engine)
            :dispose-engine-fn (fn [engine]
                                 (reset! disposed engine)
                                 {:disposed? true})})
        ready (session/ensure-script-engine! s)
        disposed-session (session/dispose-script-engine! ready)]
    (is (= :ready (get-in ready [:browser.session/script-engine :script-engine/status])))
    (is (fn? (get-in ready [:browser.session/script-engine :script-engine/engine])))
    (is (= engine @disposed))
    (is (= :disposed (get-in disposed-session [:browser.session/script-engine :script-engine/status])))
    (is (= {:disposed? true}
           (get-in disposed-session [:browser.session/script-engine :script-engine/engine])))))

(deftest stale-script-engine-completion-is-disposed-after-navigation
  (let [h (host/recording-host)
        disposed (atom [])
        engine {:engine/id "old"}
        start (session/begin-script-engine-start!
               (session/new-session
                {:host h
                 :engine-factory (fn [_] :pending-engine)
                 :dispose-engine-fn (fn [engine]
                                      (swap! disposed conj engine)
                                      {:disposed engine})}))
        token (:token start)
        generation (:generation start)
        navigated (session/load-html! (:session start)
                                      {:url "kotoba://new"
                                       :html "<main>New</main>"})
        completed (session/complete-script-engine-start!
                   navigated
                   {:token token
                    :generation generation
                    :engine engine})]
    (is (= [engine] @disposed))
    (is (= :empty (get-in completed [:browser.session/script-engine :script-engine/status])))
    (is (nil? (get-in completed [:browser.session/script-engine :script-engine/engine])))
    (is (= :script-engine/stale-completion
           (-> completed :browser.session/history last :event)))
    (is (= 1 (:browser.session/page-generation completed)))))

(deftest script-runner-receives-page-generation
  (let [h (host/recording-host)
        generations (atom [])
        s (session/new-session
           {:host h
            :script-runner (fn [session script]
                             (swap! generations conj (:script/generation script))
                             session)})
        s (session/load-html! s {:url "kotoba://scripts"
                                 :html "<main><script>globalThis.a = 1;</script></main>"})]
    (is (= [1 1 1] @generations))
    (is (= 1 (:browser.session/page-generation s)))))

(deftest script-document-state-commits-persistent-page-document
  (let [h (host/recording-host)
        s (-> (session/new-session {:host h})
              (session/load-html! {:url "kotoba://mutate"
                                   :html "<main id=\"root\"></main>"}))
        root (bridge/query-selector (get-in s [:browser.session/page :browser/document]) "#root")
        created (bridge/apply-mutation (get-in s [:browser.session/page :browser/document])
                                       {:dom/op :create-element :tag "p"})
        text (bridge/apply-mutation (:document created)
                                    {:dom/op :create-text :text "From script"})
        with-text (bridge/apply-mutation (:document text)
                                         {:dom/op :append-child
                                          :parent/id (:node/id created)
                                          :child/id (:node/id text)})
        appended (bridge/apply-mutation (:document with-text)
                                        {:dom/op :append-child
                                         :parent/id root
                                         :child/id (:node/id created)})
        s (session/commit-script-state! s {:document (:document appended)
                                           :result :ok
                                           :results [{:result :ok}]})
        recorded (host/recorded h)]
    (is (= "From script" (-> s :browser.session/page :browser/document dom/text-content)))
    (is (some #(and (= :text (:draw/op %))
                    (= "From script" (:text %)))
              (get-in s [:browser.session/page :browser/draw-ops])))
    (is (= 2 (:present-count recorded)))
    (is (= :script/document-state (-> s :browser.session/history last :event)))))

(deftest script-document-state-updates-current-navigation-entry
  (let [h (host/recording-host)
        s (-> (session/new-session {:host h})
              (session/load-html! {:url "kotoba://one"
                                   :html "<main id=\"root\"></main>"}))
        root (bridge/query-selector (get-in s [:browser.session/page :browser/document]) "#root")
        created (bridge/apply-mutation (get-in s [:browser.session/page :browser/document])
                                       {:dom/op :create-element :tag "p"})
        text (bridge/apply-mutation (:document created)
                                    {:dom/op :create-text :text "Script state"})
        with-text (bridge/apply-mutation (:document text)
                                         {:dom/op :append-child
                                          :parent/id (:node/id created)
                                          :child/id (:node/id text)})
        appended (bridge/apply-mutation (:document with-text)
                                        {:dom/op :append-child
                                         :parent/id root
                                         :child/id (:node/id created)})
        mutated (session/commit-script-state! s {:document (:document appended)
                                                 :result :ok
                                                 :results [{:result :ok}]})
        two (session/load-html! mutated {:url "kotoba://two"
                                         :html "<main>Second</main>"})
        back (session/back! two)
        forward (session/forward! back)]
    (is (= "Script state"
           (-> back :browser.session/page :browser/document dom/text-content)))
    (is (= "Second"
           (-> forward :browser.session/page :browser/document dom/text-content)))
    (is (= ["kotoba://one" "kotoba://two"]
           (mapv :url (get-in forward [:browser.session/navigation :entries]))))))

(deftest restored-session-uses-persisted-current-page-state
  (let [h (host/recording-host)
        provider (persistence-provider/memory-provider)
        s (-> (session/new-session {:host h
                                    :persistence-provider provider})
              (session/load-html! {:url "kotoba://restore"
                                   :html "<main id=\"root\"></main>"}))
        root (bridge/query-selector (get-in s [:browser.session/page :browser/document]) "#root")
        created (bridge/apply-mutation (get-in s [:browser.session/page :browser/document])
                                       {:dom/op :create-element :tag "p"})
        text (bridge/apply-mutation (:document created)
                                    {:dom/op :create-text :text "Restored script state"})
        with-text (bridge/apply-mutation (:document text)
                                         {:dom/op :append-child
                                          :parent/id (:node/id created)
                                          :child/id (:node/id text)})
        appended (bridge/apply-mutation (:document with-text)
                                        {:dom/op :append-child
                                         :parent/id root
                                         :child/id (:node/id created)})
        committed (session/commit-script-state! s {:document (:document appended)
                                                   :result :ok
                                                   :results [{:result :ok}]})
        restored (session/new-session {:host h
                                       :persistence-provider provider})
        resumed (session/resume-current-page! restored)
        recorded (host/recorded h)]
    (is (= "Restored script state"
           (-> committed :browser.session/page :browser/document dom/text-content)))
    (is (= "Restored script state"
           (-> restored :browser.session/page :browser/document dom/text-content)))
    (is (= "kotoba://restore"
           (get-in restored [:browser.session/page :browser/url])))
    (is (= 0 (get-in restored [:browser.session/navigation :index])))
    (is (= "Restored script state"
           (-> resumed :browser.session/page :browser/document dom/text-content)))
    (is (= 3 (:present-count recorded)))
    (is (some #(and (= :create-text (:op %))
                    (= "Restored script state" (:text %)))
              (get-in resumed [:browser.session/last-batch :ops])))
    (is (= :page/resume (-> resumed :browser.session/history last :event)))))

(deftest restored-session-preserves-selected-file-metadata
  (let [h (host/recording-host)
        provider (persistence-provider/memory-provider)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://app.example" :net/fetch))
        calls (atom [])
        loaded (-> (session/new-session
                    {:host h
                     :profile p
                     :store (storage/empty-store)
                     :persistence-provider provider
                     :fetch-fn (fn [req]
                                 (swap! calls conj req)
                                 {:status 200
                                  :headers {}
                                  :body "<main><p>Uploaded</p></main>"})})
                   (session/load-html! {:url "https://app.example/form"
                                        :html "<main><form id=\"form\" action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\"><input id=\"upload\" type=\"file\" name=\"upload\" value=\"/secret/path.txt\"><button id=\"go\" name=\"action\" value=\"save\">Save</button></form></main>"}))
        document (get-in loaded [:browser.session/page :browser/document])
        upload (bridge/query-selector document "#upload")
        go (bridge/query-selector document "#go")
        selected (session/apply-document-input-event! loaded {:event/type :file/select
                                                             :node/id upload
                                                             :files [{:name "/Users/me/report.csv"
                                                                      :type "text/csv"
                                                                      :size 42}]})
        restored (session/new-session {:host h
                                       :persistence-provider provider
                                       :fetch-fn (:browser.session/fetch-fn selected)})
        a11y-upload (let [tree (session/accessibility-tree restored)]
                      (some (fn walk [node]
                              (or (when (= "report.csv" (:a11y/value node)) node)
                                  (some walk (:a11y/children node))))
                            (:a11y/children tree)))
        submitted (session/apply-document-input-event! restored {:event/type :pointer/click
                                                                :node/id go})
        req (first @calls)]
    (is (= [{:file/name "report.csv"
             :file/type "text/csv"
             :file/size 42}]
           (get-in restored [:browser.session/page :browser/document :nodes upload :attrs :files])))
    (is (= "report.csv" (:a11y/value a11y-upload)))
    (is (re-find #"Content-Disposition: form-data; name=\"upload\"\r\n\r\nreport.csv" (:body req)))
    (is (not (str/includes? (:body req) "/Users/me")))
    (is (not (str/includes? (:body req) "/secret/path.txt")))
    (is (= [{:name "upload"
             :value "report.csv"
             :node/id upload
             :file/name "report.csv"
             :file/type "text/csv"
             :file/size 42
             :file/last-modified nil}
            {:name "action" :value "save" :node/id go}]
           (get-in submitted [:browser.session/document-input-result :form/data])))))

(deftest script-document-state-recomputes-css-and-clears-stale-style
  (let [h (host/recording-host)
        page (browser/load-html {:url "kotoba://mutate"
                                 :css ".note { color: blue; margin: 6px } .active { border-width: 2px }"
                                 :html "<main><p id=\"note\" class=\"note active\" style=\"padding: 3px\">Note</p></main>"})
        note (bridge/query-selector (:browser/document page) "#note")
        s (session/new-session {:host h
                                :page page})
        mutated (-> (:browser/document page)
                    (bridge/apply-mutation {:dom/op :set-attribute
                                            :node/id note
                                            :attr "class"
                                            :value "active"})
                    :document)
        s (session/commit-script-state! s {:document mutated
                                           :result :ok
                                           :results [{:result :ok}]})
        attrs (get-in s [:browser.session/page :browser/document :nodes note :attrs])]
    (is (= 3 (:style/padding attrs)))
    (is (= 2 (:style/border-width attrs)))
    (is (not (contains? attrs :style/color)))
    (is (not (contains? attrs :style/margin)))
    (is (= 4 (count (filter :border? (get-in s [:browser.session/page :browser/draw-ops])))))
    (is (= :script/document-state (-> s :browser.session/history last :event)))))

(deftest script-style-attribute-mutation-commits-inline-style-model
  (let [h (host/recording-host)
        page (browser/load-html {:url "kotoba://mutate"
                                 :css ".note { color: blue !important; margin: 6px }"
                                 :html "<main><p id=\"note\" class=\"note\">Note</p></main>"})
        note (bridge/query-selector (:browser/document page) "#note")
        s (session/new-session {:host h
                                :page page})
        mutated (-> (:browser/document page)
                    (bridge/apply-mutation {:dom/op :set-attribute
                                            :node/id note
                                            :attr "style"
                                            :value "color: red; padding: 4px"})
                    :document)
        s (session/commit-script-state! s {:document mutated
                                           :result :ok
                                           :results [{:result :ok}]})
        attrs (get-in s [:browser.session/page :browser/document :nodes note :attrs])]
    (is (= {:color "red" :padding 4} (:style-inline attrs)))
    (is (= "blue" (:style/color attrs)) "author important still wins over inline normal after script style mutation")
    (is (= 4 (:style/padding attrs)))
    (is (= 6 (:style/margin attrs)))))

(deftest session-builds-network-context-for-page-scripts
  (let [h (host/recording-host)
        p (-> (profile/new-profile {:id "default"})
              (profile/grant-permission "https://api.example" :net/fetch))
        fetch-fn (fn [_] {:status 200 :body "ok"})
        s (-> (session/new-session {:host h
                                    :profile p
                                    :store (storage/empty-store)
                                    :fetch-fn fetch-fn})
              (session/load-html! {:url "https://app.example/page"
                                   :html "<main></main>"}))
        ctx (session/net-context s)]
    (is (= (:browser.session/profile s) (:profile ctx)))
    (is (= (:browser.session/store s) (:store ctx)))
    (is (= "https://app.example/page" (:page-url ctx)))
    (is (= :same-origin (:credentials ctx)))
    (is (= fetch-fn (:fetch-fn ctx)))))

(deftest script-state-persists-network-store-back-to-session
  (let [h (host/recording-host)
        provider (persistence-provider/memory-provider)
        p (profile/new-profile {:id "default"})
        s (-> (session/new-session {:host h
                                    :profile p
                                    :store (storage/empty-store)
                                    :persistence-provider provider})
              (session/load-html! {:url "https://app.example/page"
                                   :html "<main></main>"}))
        store (storage/put-value (:browser.session/store s)
                                 p
                                 "https://api.example/data"
                                 :http/cache
                                 {:status 200 :body "cached"})
        committed (session/commit-script-state!
                   s
                   {:document (get-in s [:browser.session/page :browser/document])
                    :net/context {:store store}
                    :result :ok
                    :results [{:result :ok}]})
        restored (session/new-session {:host h
                                       :persistence-provider provider})]
    (is (= {:status 200 :body "cached"}
           (storage/get-value (:browser.session/store committed)
                              p
                              "https://api.example/data"
                              :http/cache)))
    (is (= true (-> committed :browser.session/history last :net/store-updated?)))
    (is (= {:status 200 :body "cached"}
           (storage/get-value (:browser.session/store restored)
                              (:browser.session/profile restored)
                              "https://api.example/data"
                              :http/cache)))))

(deftest script-state-without-document-can-persist-network-store
  (let [h (host/recording-host)
        p (profile/new-profile {:id "default"})
        s (-> (session/new-session {:host h
                                    :profile p
                                    :store (storage/empty-store)})
              (session/load-html! {:url "https://app.example/page"
                                   :html "<main></main>"}))
        store (storage/put-value (:browser.session/store s)
                                 p
                                 "https://api.example/data"
                                 :http/cache
                                 {:status 204})
        committed (session/commit-script-state! s {:net/context {:store store}})]
    (is (= :script/net-state (-> committed :browser.session/history last :event)))
    (is (= {:status 204}
           (storage/get-value (:browser.session/store committed)
                              p
                              "https://api.example/data"
                              :http/cache)))))

(deftest session-restores-and-saves-through-persistence-provider
  (let [h (host/recording-host)
        provider (persistence-provider/memory-provider)
        acct (account/new-account {:id "local"})
        p (profile/new-profile {:id "default" :account acct})
        store (storage/put-value (storage/empty-store) p "kotoba://persist" :token "abc")
        saved (-> (session/new-session {:host h
                                        :profile p
                                        :store store
                                        :persistence-provider provider})
                  (session/load-html! {:url "kotoba://persist"
                                       :html "<main>Persist</main>"})
                  (session/load-html! {:url "kotoba://next"
                                       :html "<main>Next</main>"}))
        restored (session/new-session {:host h
                                       :persistence-provider provider})]
    (is (= "default" (get-in restored [:browser.session/profile :profile/id])))
    (is (= 2 (count (get-in restored [:browser.session/profile :profile/history]))))
    (is (= "abc" (storage/get-value (:browser.session/store restored)
                                    (:browser.session/profile restored)
                                    "kotoba://persist"
                                    :token)))
    (is (= 2 (count (get-in restored [:browser.session/navigation :entries]))))
    (is (= 1 (get-in restored [:browser.session/navigation :index])))
    (is (= 2 (count (:browser.session/history restored))))
    (is (seq (get-in saved [:browser.session/audit :audit/datoms])))))

(deftest navigation-audit-is-persisted-after-back-forward-and-reload
  (let [h (host/recording-host)
        provider (persistence-provider/memory-provider)
        s (session/new-session
           {:host h
            :persistence-provider provider
            :fetch-fn (fn [{:keys [url]}]
                        {:status 200 :body (str "<main>" url "</main>")})})
        s (-> s
              (session/navigate! "kotoba://one")
              (session/navigate! "kotoba://two")
              (session/back!)
              (session/forward!)
              (session/reload!))
        snapshot (persistence-provider/load-snapshot! provider)]
    (is (= [:page/commit :page/commit :page/commit :navigation/back
            :page/commit :navigation/forward :page/commit :navigation/reload]
           (mapv :event (:browser.session/history s))))
    (is (= {:page/commit 5 :navigation/back 1 :navigation/forward 1 :navigation/reload 1}
           (audit/replay-summary (:browser.session/audit s))))
    (is (= (get-in s [:browser.session/audit :audit/datoms])
           (get-in snapshot [:snapshot/audit :audit/datoms])))))

(deftest audit-events-stay-in-chronological-append-order-past-eight-entries
  "Clojure promotes a map from PersistentArrayMap to PersistentHashMap once
   it grows past 8 entries, and only the former happens to iterate in
   insertion order. `browser.audit/events` used to group datoms by eid with
   `group-by` (a plain hash-map), so any real session logging more than 8
   audit events -- trivially reached by a handful of navigations, exactly
   like this one -- got its audit trail back in an arbitrary hash-bucket
   order instead of the order things actually happened. That silently broke
   consumers such as `browser.browser-use/debug-state`'s
   `:audit-events-tail`, whose whole point is showing the *most recent*
   activity for debugging a mis-targeted browser-use action: taking a
   `subvec` tail of a mis-ordered vector returns an arbitrary sample of
   history, not the tail."
  (let [h (host/recording-host)
        s (session/new-session
           {:host h
            :fetch-fn (fn [{:keys [url]}]
                        {:status 200 :body (str "<main>" url "</main>")})})
        s (reduce (fn [s url] (session/navigate! s url))
                  s
                  (map #(str "kotoba://page" %) (range 1 11)))]
    ;; 10 real navigations -> 10 :page/commit audit events, well past the
    ;; 8-entry array-map/hash-map cutover.
    (is (< 8 (count (get-in s [:browser.session/audit :audit/datoms])))
        "sanity: this session must actually cross the 8-datom cutover")
    (is (= (repeat 10 :page/commit)
           (mapv :audit/event (audit/events (:browser.session/audit s)))))
    (is (= (mapv #(str "kotoba://page" %) (range 1 11))
           (mapv :url (audit/events (:browser.session/audit s))))
        "events must come back oldest-first, matching real navigation order")))

(deftest navigation-lifecycle-supports-redirect-back-forward-reload-and-error-document
  (let [h (host/recording-host)
        calls (atom [])
        s (session/new-session
           {:host h
            :fetch-fn (fn [{:keys [url] :as req}]
                        (swap! calls conj req)
                        (case url
                          "kotoba://one" {:status 200 :body "<main>One</main>"}
                          "kotoba://two" {:status 200 :body "<main>Two</main>"}
                          "kotoba://redir" {:status 302 :headers {"Location" "kotoba://two"}}
                          "kotoba://fail" {:status 500 :body "<main>Fail</main>"}))})
        s1 (session/navigate! s "kotoba://one")
        s2 (session/navigate! s1 "kotoba://redir")
        back (session/back! s2)
        forward (session/forward! back)
        reloaded (session/reload! forward)
        failed (session/navigate! reloaded "kotoba://fail")]
    (is (= ["kotoba://one" "kotoba://redir" "kotoba://two" "kotoba://two" "kotoba://fail"]
           (mapv :url @calls)))
    (is (= [{:from "kotoba://redir" :to "kotoba://two" :status 302}]
           (get-in s2 [:browser.session/navigation :redirects])))
    (is (= "One" (-> back :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= "Two" (-> forward :browser.session/page :browser/document kotoba.wasm.dom/text-content)))
    (is (= :navigation/http-error (:browser.session/error failed)))
    (is (= "Navigation errorhttp-error"
           (-> failed :browser.session/error-document :browser/document kotoba.wasm.dom/text-content)))))

(deftest navigation-redirect-persists-cookie-for-next-hop
  (let [h (host/recording-host)
        p (profile/new-profile {:id "default"})
        calls (atom [])
        s (session/new-session
           {:host h
            :profile p
            :store (storage/empty-store)
            :fetch-fn (fn [{:keys [url] :as req}]
                        (swap! calls conj req)
                        (case url
                          "https://app.example/start"
                          {:status 302
                           :headers {:location "https://app.example/landing"
                                     "set-cookie" "sid=redir; Path=/"}}

                          "https://app.example/landing"
                          {:status 200 :body "<main>Landing</main>"}))})
        navigated (session/navigate! s "https://app.example/start")]
    (is (= ["https://app.example/start" "https://app.example/landing"]
           (mapv :url @calls)))
    (is (nil? (get-in (first @calls) [:headers "cookie"])))
    (is (= "sid=redir" (get-in (second @calls) [:headers "cookie"])))
    (is (= {"sid" "redir"}
           (storage/get-value (:browser.session/store navigated)
                              p
                              "https://app.example/start"
                              net/cookie-key)))
    (is (= "Landing" (-> navigated :browser.session/page :browser/document kotoba.wasm.dom/text-content)))))

(deftest navigation-redirect-resolves-relative-location
  (let [h (host/recording-host)
        calls (atom [])
        s (session/new-session
           {:host h
            :fetch-fn (fn [{:keys [url] :as req}]
                        (swap! calls conj req)
                        (case url
                          "https://app.example/account/start"
                          {:status 302 :headers {"Location" "../next"}}

                          "https://app.example/next"
                          {:status 302 :headers {"LoCaTiOn" "//cdn.example/done"}}

                          "https://cdn.example/done"
                          {:status 302 :headers {"Location" "https://cdn.example/assets/../done?ok=1#final"}}

                          "https://cdn.example/done?ok=1#final"
                          {:status 200 :body "<main>Done</main>"}))})
        navigated (session/navigate! s "https://app.example/account/start")]
    (is (= ["https://app.example/account/start"
            "https://app.example/next"
            "https://cdn.example/done"
            "https://cdn.example/done?ok=1#final"]
           (mapv :url @calls)))
    (is (= [{:from "https://app.example/account/start"
             :to "https://app.example/next"
             :status 302}
            {:from "https://app.example/next"
             :to "https://cdn.example/done"
             :status 302}
            {:from "https://cdn.example/done"
             :to "https://cdn.example/done?ok=1#final"
             :status 302}]
           (get-in navigated [:browser.session/navigation :redirects])))
    (is (= "Done" (-> navigated :browser.session/page :browser/document kotoba.wasm.dom/text-content)))))

(defn- geolocation-probe-session
  [opts]
  (-> (session/new-session (merge {:host (host/recording-host)} opts))
      (session/load-html! {:url "https://app.example/map" :html "<main>Map</main>"})))

(defn- run-geolocation-probe!
  [session]
  (let [received (atom nil)
        session (assoc session :browser.session/script-engine
                       {:script-engine/engine (fn [request]
                                                (reset! received request)
                                                {:result :ok :requests []})})]
    (quickjs-runner/run-script! session {:script/type :classic
                                         :script/source "/* probe */"
                                         :script/url "https://app.example/map"})
    (:geolocation/snapshot @received)))

(deftest run-script-threads-real-session-level-geolocation-into-the-engine-invocation
  ;; Before this fix, browser.session/new-session had no :geolocation
  ;; injection point at all (unlike :fetch-fn/:websocket-fn/:worker-fn), so
  ;; browser.compat.quickjs-runner/run-script! never supplied one to
  ;; quickjs-execution/new-state -- every real page load could only ever
  ;; report the (0.0, 0.0, 0.0) default, regardless of what a host
  ;; application wanted to simulate. This proves a real, host-owned
  ;; geolocation atom, threaded through session/new-session, actually
  ;; reaches the real geolocation-snapshot computation run-script! feeds
  ;; the engine invocation.
  (let [position (atom {:latitude 35.6812 :longitude 139.7671 :accuracy 12.5})
        profile (-> (profile/new-profile {:id "work"})
                    (profile/grant-permission "https://app.example" :geolocation/read))
        session (geolocation-probe-session {:profile profile :geolocation position})]
    (is (= {:granted true
            :position {:coords {:latitude 35.6812 :longitude 139.7671 :accuracy 12.5}}}
           (run-geolocation-probe! session)))))

(deftest run-script-geolocation-reflects-live-host-updates-within-a-page
  ;; The host owns the atom and may swap! it at any time (e.g. a real GPS
  ;; driver as the device moves) -- a later script tag within the SAME page
  ;; load must see the new value, not a value snapshotted once at session
  ;; construction.
  (let [position (atom {:latitude 1.0 :longitude 2.0 :accuracy 5.0})
        profile (-> (profile/new-profile {:id "work"})
                    (profile/grant-permission "https://app.example" :geolocation/read))
        session (geolocation-probe-session {:profile profile :geolocation position})
        first-snapshot (run-geolocation-probe! session)]
    (is (= {:latitude 1.0 :longitude 2.0 :accuracy 5.0}
           (get-in first-snapshot [:position :coords])))
    (reset! position {:latitude 9.0 :longitude 8.0 :accuracy 1.0})
    (is (= {:latitude 9.0 :longitude 8.0 :accuracy 1.0}
           (get-in (run-geolocation-probe! session) [:position :coords])))))

(deftest run-script-geolocation-reflects-live-host-updates-across-navigation
  (let [position (atom {:latitude 1.0 :longitude 2.0 :accuracy 5.0})
        profile (-> (profile/new-profile {:id "work"})
                    (profile/grant-permission "https://app.example" :geolocation/read))
        session (geolocation-probe-session {:profile profile :geolocation position})]
    (is (= {:latitude 1.0 :longitude 2.0 :accuracy 5.0}
           (get-in (run-geolocation-probe! session) [:position :coords])))
    (reset! position {:latitude 9.0 :longitude 8.0 :accuracy 1.0})
    (let [navigated (session/load-html! session {:url "https://app.example/map2" :html "<main>Map 2</main>"})]
      (is (= {:latitude 9.0 :longitude 8.0 :accuracy 1.0}
             (get-in (run-geolocation-probe! navigated) [:position :coords]))))))

(deftest run-script-geolocation-defaults-to-zero-without-host-injection
  ;; Regression guard: a session built without a :geolocation option must
  ;; keep today's default (quickjs-execution/new-state's own zeroed
  ;; {:latitude 0.0 :longitude 0.0 :accuracy 0.0}), unaffected by this
  ;; change.
  (let [profile (-> (profile/new-profile {:id "work"})
                    (profile/grant-permission "https://app.example" :geolocation/read))
        session (geolocation-probe-session {:profile profile})]
    (is (= {:latitude 0.0 :longitude 0.0 :accuracy 0.0}
           (get-in (run-geolocation-probe! session) [:position :coords])))))
