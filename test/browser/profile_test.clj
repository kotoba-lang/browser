(ns browser.profile-test
  (:require [browser.account :as account]
            [browser.profile :as profile]
            [clojure.test :refer [deftest is]]))

(deftest account-carries-local-or-verified-identity
  (let [local (account/new-account {:handle "jun"})
        verified (account/new-account {:id "did:example:jun"
                                       :display-name "Jun"
                                       :trust :verified})]
    (is (= "jun" (:account/id local)))
    (is (not (account/verified? local)))
    (is (account/verified? verified))))

(deftest profile-partitions-storage-and-permissions
  (let [acct (account/new-account {:id "did:example:jun"})
        p (-> (profile/new-profile {:id "work" :account acct})
              (profile/grant-permission "kotoba://shell" :input/event)
              (profile/grant-permission "kotoba://shell" :dom/event))]
    (is (= "did:example:jun" (:profile/account-id p)))
    (is (profile/permission? p "kotoba://shell" :input/event))
    (is (not (profile/permission? p "kotoba://docs" :input/event)))
    (is (= ["profile:work" "kotoba://shell" :session]
           (profile/storage-key p "kotoba://shell" :session)))))

(deftest permission-decision-allows-internal-defaults-and-denies-unknown
  (let [acct (account/new-account {:id "local"})
        p (profile/new-profile {:id "default" :account acct})]
    (is (= :allow (:permission/decision
                   (profile/permission-decision p "kotoba://shell" :input/event))))
    (is (= {:permission/decision :deny
            :origin "https://example.com"
            :capability :input/event
            :profile/id "default"
            :reason :permission/not-granted}
           (profile/permission-decision p "https://example.com" :input/event)))))

(deftest browser-state-selects-active-profile
  (let [acct (account/new-account {:id "local"})
        personal (profile/new-profile {:id "personal" :account acct})
        work (profile/new-profile {:id "work" :account acct})
        state (profile/browser-state {:account acct
                                      :profiles [personal work]
                                      :active-profile-id "work"})]
    (is (= "work" (:profile/id (profile/active-profile state))))))
