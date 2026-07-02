(ns browser.session
  "Session adapter from browser pages/surfaces to the kotoba:dom host ABI."
  (:require [browser.accessibility :as accessibility]
            [browser.audit :as audit]
            [browser.chrome :as chrome]
            [browser.core :as browser]
            [browser.document-input :as document-input]
            [browser.input :as input]
            [browser.net :as net]
            [browser.origin :as origin]
            [browser.page-script :as page-script]
            [browser.persistence :as persistence]
            [browser.persistence-provider :as persistence-provider]
            [browser.profile :as profile]
            [browser.script-engine :as script-engine]
            [browser.storage :as storage]
            [browser.surface :as surface]
            [clojure.string :as str]
            [kotoba.wasm.host :as host]))

(def redirect-statuses #{301 302 303 307 308})

(defn- strip-fragment
  [url]
  (let [url (str url)
        idx (.indexOf url "#")]
    (if (neg? idx) url (subs url 0 idx))))

(defn- current-navigation-page
  [navigation]
  (let [idx (:index navigation -1)]
    (get-in navigation [:entries idx :page])))

(defn- restore
  [opts]
  (if-let [provider (:persistence-provider opts)]
    (if-let [snapshot (persistence-provider/load-snapshot! provider)]
      (let [{:keys [history navigation]} (persistence/replay-session snapshot)]
        (merge {:profile (:snapshot/profile snapshot)
                :store (:snapshot/storage snapshot)
                :audit (:snapshot/audit snapshot)
                :chrome (:snapshot/chrome snapshot)
                :surface (:snapshot/surface snapshot)
                :navigation navigation
                :history history
                :page (current-navigation-page navigation)}
               opts))
      opts)
    opts))

(defn new-session
  [{:keys [host viewport theme fetch-fn surface account profile input audit
           persistence-provider store chrome navigation history page script-runner
           script-engine engine-factory dispose-engine-fn] :as opts}]
  (let [{:keys [profile store audit chrome surface navigation history page] :as opts} (restore opts)]
  {:browser.session/host host
   :browser.session/viewport (or viewport [800 600])
   :browser.session/theme theme
   :browser.session/fetch-fn fetch-fn
   :browser.session/script-runner script-runner
   :browser.session/script-engine (or script-engine
                                      (script-engine/empty-manager
                                       {:engine-factory engine-factory
                                        :dispose-fn dispose-engine-fn}))
   :browser.session/persistence-provider persistence-provider
   :browser.session/store store
   :browser.session/chrome (or chrome (browser.chrome/empty-chrome))
   :browser.session/account account
   :browser.session/profile profile
   :browser.session/input (or input (input/empty-state))
   :browser.session/page page
   :browser.session/page-generation (or (:page-generation opts) 0)
   :browser.session/surface surface
   :browser.session/last-batch nil
   :browser.session/error nil
   :browser.session/error-document nil
   :browser.session/navigation (or navigation {:entries []
                                               :index -1
                                               :redirects []})
   :browser.session/history (or history [])
   :browser.session/audit (or audit (audit/empty-log))}))

(defn- profile-id
  [session]
  (get-in session [:browser.session/profile :profile/id]))

(defn- audit!
  [session event]
  (update session :browser.session/audit audit/append-event event))

(defn- snapshot-state
  [session]
  {:profile (:browser.session/profile session)
   :store (:browser.session/store session)
   :audit (:browser.session/audit session)
   :chrome (:browser.session/chrome session)
   :surface (:browser.session/surface session)
   :session {:history (:browser.session/history session)
             :navigation (:browser.session/navigation session)}})

(defn persist!
  [session]
  (if-let [provider (:browser.session/persistence-provider session)]
    (do
      (persistence-provider/save-browser-state! provider (snapshot-state session))
      session)
    session))

(defn accessibility-tree
  "Return the current host-facing accessibility tree for page + OS surface."
  [session]
  (accessibility/session-tree session))

(defn ensure-script-engine!
  [session]
  (script-engine/ensure! session))

(defn begin-script-engine-start!
  [session]
  (let [{:keys [session token generation engine-or-promise]} (script-engine/begin-start! session)]
    {:session (update session :browser.session/history conj
                      {:event :script-engine/start
                       :script-engine/token token
                       :page/generation generation})
     :token token
     :generation generation
     :engine-or-promise engine-or-promise}))

(defn complete-script-engine-start!
  [session completion]
  (let [before (get-in session [:browser.session/script-engine :script-engine/status])
        session (script-engine/complete-start! session completion)
        after (get-in session [:browser.session/script-engine :script-engine/status])
        stale? (not= :ready after)]
    (update session :browser.session/history conj
            (cond-> {:event (if stale?
                              :script-engine/stale-completion
                              :script-engine/ready)
                     :script-engine/token (:token completion)
                     :page/generation (:generation completion)}
              before (assoc :script-engine/previous-status before)))))

(defn fail-script-engine-start!
  [session failure]
  (-> (script-engine/fail-start! session failure)
      (update :browser.session/history conj
              {:event :script-engine/error
               :script-engine/token (:token failure)
               :page/generation (:generation failure)
               :error (:error failure)})))

(defn abort-script-engine-start!
  ([session]
   (abort-script-engine-start! session :navigation/new-page))
  ([session reason]
   (let [pending (get-in session [:browser.session/script-engine :script-engine/pending])]
     (cond-> (script-engine/abort-pending! session reason)
       pending
       (update :browser.session/history conj
               {:event :script-engine/abort
                :script-engine/token (:script-engine/token pending)
                :page/generation (:script-engine/generation pending)
                :reason reason})))))

(defn dispose-script-engine!
  [session]
  (-> (script-engine/dispose! session)
      (update :browser.session/history conj {:event :script-engine/dispose})))

(defn- script-cache-source
  [session url]
  (when (and (:browser.session/store session)
             (:browser.session/profile session))
    (storage/get-value (:browser.session/store session)
                       (:browser.session/profile session)
                       url
                       page-script/script-source-storage-key)))

(defn- cache-script-source
  [session url source]
  (if (and (:browser.session/store session)
           (:browser.session/profile session))
    (update session :browser.session/store
            storage/put-value
            (:browser.session/profile session)
            url
            page-script/script-source-storage-key
            source)
    session))

(defn- navigation-cookie-header
  [session page-url url]
  (when (and (:browser.session/store session)
             (:browser.session/profile session))
    (net/cookie-header (:browser.session/store session)
                       (:browser.session/profile session)
                       page-url
                       url)))

(defn- remember-navigation-response-cookies
  [session url response]
  (if (and (:browser.session/store session)
           (:browser.session/profile session))
    (update session :browser.session/store
            net/remember-response-cookies
            (:browser.session/profile session)
            url
            response)
    session))

(defn net-context
  "Build the browser network policy context for page script execution."
  [session]
  (when-let [page-url (get-in session [:browser.session/page :browser/url])]
    (cond-> {:store (:browser.session/store session)
             :profile (:browser.session/profile session)
             :page-url page-url
             :credentials :same-origin}
      (:browser.session/fetch-fn session)
      (assoc :fetch-fn (:browser.session/fetch-fn session)))))

(defn- script-permission-decision
  [session url]
  (if-let [profile (:browser.session/profile session)]
    (profile/permission-decision profile (origin/origin url) :net/fetch)
    {:permission/decision :deny
     :origin (origin/origin url)
     :capability :net/fetch
     :profile/id nil
     :reason :permission/no-profile}))

(defn- record-script-event
  [session event]
  (update session :browser.session/history conj event))

(defn- lifecycle-script
  [session target event-type]
  (let [page-url (get-in session [:browser.session/page :browser/url])
        dispatch (case target
                   :document "document.dispatchEvent"
                   :window "window.dispatchEvent")]
    {:script/type :classic
     :script/source (str dispatch "(new Event('" event-type "'));")
     :script/url page-url
     :script/lifecycle-event event-type
     :script/event-target target}))

(defn- fetch-script-source
  [session url]
  (when-let [fetch-fn (:browser.session/fetch-fn session)]
    (net/fetch-resource (assoc (net-context session)
                               :fetch-fn fetch-fn
                               :cache? false)
                        {:url url
                         :method :get
                         :capability :script/src
                         :referrer (get-in session [:browser.session/page :browser/url])})))

(defn- document-base-uri
  [session]
  (or (get-in session [:browser.session/page :browser/document :base-uri])
      (get-in session [:browser.session/page :browser/url])))

(defn resolve-script-source!
  [session script]
  (if-not (:script/src script)
    {:session session :script script}
    (let [url (page-script/resolve-src (document-base-uri session)
                                       (:script/src script))
          decision (script-permission-decision session url)
          session (audit! session (audit/permission-event decision))]
      (cond
        (not= :allow (:permission/decision decision))
        {:session (record-script-event session {:event :script/blocked
                                                :url url
                                                :script/type (:script/type script)
                                                :reason (:reason decision)})
         :script (assoc script
                        :script/url url
                        :script/error :script/permission-denied)}

        (script-cache-source session url)
        {:session (record-script-event session {:event :script/cache-hit
                                                :url url
                                                :script/type (:script/type script)})
         :script (assoc script
                        :script/url url
                        :script/source (script-cache-source session url)
                        :script/cache-hit? true)}

        :else
        (let [fetch-result (fetch-script-source session url)
              session (cond-> session
                        (contains? fetch-result :store)
                        (assoc :browser.session/store (:store fetch-result)))
              response (:response fetch-result)
              status (:status response)]
          (if (<= 200 (or status 0) 299)
            (let [source (:body response)]
              {:session (-> session
                            (cache-script-source url source)
                            (record-script-event {:event :script/fetch
                                                  :url url
                                                  :status status
                                                  :script/type (:script/type script)}))
               :script (assoc script
                              :script/url url
                              :script/source source)})
            {:session (record-script-event session {:event :script/error
                                                    :url url
                                                    :status status
                                                    :error :script/fetch-failed})
             :script (assoc script
                            :script/url url
                            :script/error :script/fetch-failed)}))))))

(defn- remember-navigation-entry
  [session page]
  (let [nav (:browser.session/navigation session)
        idx (inc (:index nav -1))
        entries (-> (:entries nav)
                    (subvec 0 (max 0 idx))
                    (conj {:url (:browser/url page)
                           :page page}))]
    (assoc session :browser.session/navigation
           (assoc nav :entries entries :index idx))))

(defn- update-current-navigation-page
  [session page]
  (let [idx (get-in session [:browser.session/navigation :index] -1)]
    (if (get-in session [:browser.session/navigation :entries idx])
      (assoc-in session [:browser.session/navigation :entries idx :page] page)
      session)))

(defn- set-page-ready-state
  [session ready-state]
  (if-let [page (:browser.session/page session)]
    (let [page (assoc-in page [:browser/document :ready-state] ready-state)]
      (-> session
          (assoc :browser.session/page page)
          (update-current-navigation-page page)))
    session))

(defn- same-document-fragment-navigation?
  [session url]
  (let [current-url (get-in session [:browser.session/page :browser/url])]
    (and current-url
         (not= (str current-url) (str url))
         (= (strip-fragment current-url) (strip-fragment url)))))

(defn commit-page!
  ([session page]
   (commit-page! session page {:remember? true}))
  ([session page {:keys [remember?] :or {remember? true}}]
  (let [session (abort-script-engine-start! session :navigation/new-page)
        batch (host/commit! (:browser.session/host session) (:browser/ops page))
        generation (inc (:browser.session/page-generation session 0))]
    (-> session
        (assoc :browser.session/page page
               :browser.session/page-generation generation
               :browser.session/last-batch batch
               :browser.session/error nil
               :browser.session/error-document nil)
        (update :browser.session/profile
                (fn [p]
                  (if p
                    (profile/remember-navigation p {:url (:browser/url page)
                                                    :title (:browser/title page)})
                    p)))
        (cond-> remember? (remember-navigation-entry page))
        (audit! (audit/page-commit-event {:url (:browser/url page)
                                          :op-count (count (:browser/ops page))
                                          :profile-id (profile-id session)}))
        (update :browser.session/history conj {:event :page/commit
                                               :url (:browser/url page)
                                               :page/generation generation
                                               :op-count (count (:browser/ops page))})
        (persist!)))))

(defn commit-fragment-navigation!
  [session url]
  (let [page (assoc (:browser.session/page session) :browser/url url)]
    (-> session
        (assoc :browser.session/page page
               :browser.session/error nil
               :browser.session/error-document nil
               :browser.session/navigation (assoc (:browser.session/navigation session)
                                                  :redirects []))
        (update :browser.session/profile
                (fn [p]
                  (if p
                    (profile/remember-navigation p {:url url
                                                    :title (:browser/title page)})
                    p)))
        (remember-navigation-entry page)
        (audit! (audit/navigation-event {:event :navigation/fragment
                                         :url url
                                         :profile-id (profile-id session)}))
        (update :browser.session/history conj {:event :navigation/fragment
                                               :url url})
        (persist!))))

(defn resume-current-page!
  "Present the restored/current page to the host without changing navigation."
  [session]
  (if-let [page (:browser.session/page session)]
    (let [batch (host/commit! (:browser.session/host session) (:browser/ops page))]
      (-> session
          (assoc :browser.session/last-batch batch
                 :browser.session/error nil
                 :browser.session/error-document nil)
          (audit! (audit/page-commit-event {:url (:browser/url page)
                                            :op-count (count (:browser/ops page))
                                            :profile-id (profile-id session)}))
          (update :browser.session/history conj {:event :page/resume
                                                 :url (:browser/url page)
                                                 :page/generation (:browser.session/page-generation session)
                                                 :op-count (count (:browser/ops page))})
          (persist!)))
    session))

(defn commit-document!
  [session document]
  (let [page (browser/refresh-page (:browser.session/page session)
                                   {:document document
                                    :viewport (:browser.session/viewport session)
                                    :theme (:browser.session/theme session)})
        batch (host/commit! (:browser.session/host session) (:browser/ops page))]
    (-> session
        (assoc :browser.session/page page
               :browser.session/last-batch batch
               :browser.session/error nil)
        (update-current-navigation-page page)
        (audit! (audit/page-commit-event {:url (:browser/url page)
                                          :op-count (count (:browser/ops page))
                                          :profile-id (profile-id session)}))
        (update :browser.session/history conj {:event :page/document-commit
                                               :url (:browser/url page)
                                               :page/generation (:browser.session/page-generation session)
                                               :op-count (count (:browser/ops page))})
        (persist!))))

(defn- apply-script-net-store
  [session script-state]
  (if (contains? (:net/context script-state) :store)
    (assoc session :browser.session/store (get-in script-state [:net/context :store]))
    session))

(defn commit-script-state!
  [session script-state]
  (let [net-store-updated? (contains? (:net/context script-state) :store)
        session (apply-script-net-store session script-state)]
    (if-let [document (:document script-state)]
      (-> (commit-document! session document)
          (update :browser.session/history conj
                  (cond-> {:event :script/document-state
                           :result (:result script-state)
                           :response-count (count (:results script-state))}
                    net-store-updated? (assoc :net/store-updated? true)))
          (persist!))
      (if net-store-updated?
        (-> session
            (update :browser.session/history conj {:event :script/net-state
                                                   :net/store-updated? true})
            (persist!))
        session))))

(defn run-page-scripts!
  [session]
  (if-let [runner (:browser.session/script-runner session)]
    (letfn [(run-one [session script]
              (let [{:keys [session script]} (resolve-script-source! session script)]
                (if (:script/source script)
                  (runner session (assoc script :script/generation
                                         (:browser.session/page-generation session)))
                  session)))
            (run-lifecycle [session target event-type ready-state]
              (-> (set-page-ready-state session ready-state)
                  (runner (assoc (lifecycle-script session target event-type)
                                 :script/generation
                                 (:browser.session/page-generation session)))
                  (record-script-event {:event :page/lifecycle-dispatch
                                        :event/target target
                                        :event/type event-type
                                        :document/ready-state ready-state})))]
      (-> (reduce run-one
                  session
                  (page-script/executable-scripts (:browser.session/page session)))
          (run-lifecycle :document "DOMContentLoaded" "interactive")
          (run-lifecycle :window "load" "complete")
          (persist!)))
    (-> session
        (set-page-ready-state "complete")
        (persist!))))

(defn load-html!
  [session {:keys [url html]}]
  (-> (commit-page!
       session
       (browser/load-html {:url url
                           :html html
                           :viewport (:browser.session/viewport session)
                           :theme (:browser.session/theme session)}))
      (run-page-scripts!)))

(defn navigate!
  ([session url]
   (navigate! session url {}))
  ([session url request]
   (if (same-document-fragment-navigation? session url)
     (commit-fragment-navigation! session url)
     (let [fetch-fn (:browser.session/fetch-fn session)
           base-request (cond-> (select-keys request [:headers])
                          (:referrer/header request)
                          (assoc-in [:headers "referer"] (:referrer/header request)))
           page-url (or (get-in session [:browser.session/page :browser/url]) url)
        {:keys [session url response redirects]} (loop [session session url url redirects [] depth 0]
                                           (let [cookie (navigation-cookie-header session page-url url)
                                                 request (cond-> (merge base-request {:url url :method :get})
                                                           cookie (assoc-in [:headers "cookie"] cookie))
                                                 response (fetch-fn request)
                                                 session (remember-navigation-response-cookies session url response)
                                                 status (:status response)
                                                 location (net/header (:headers response) "location")]
                                             (if (and (contains? redirect-statuses status)
                                                      location
                                                      (< depth 8))
                                               (let [to-url (page-script/resolve-src url location)]
                                                 (recur session
                                                        to-url
                                                        (conj redirects {:from url :to to-url :status status})
                                                        (inc depth)))
                                               {:session session :url url :response response :redirects redirects})))
        page (if (<= 200 (or (:status response) 0) 299)
               (assoc (browser/load-html {:url url
                                          :html (:body response)
                                          :viewport (:browser.session/viewport session)
                                          :theme (:browser.session/theme session)})
                      :browser/response response)
               {:browser/url url
                :browser/response response
                :browser/error :navigation/http-error})]
    (if (:browser/error page)
      (-> session
          (abort-script-engine-start! :navigation/error)
          (assoc :browser.session/error (:browser/error page)
                 :browser.session/page page
                 :browser.session/page-generation (inc (:browser.session/page-generation session 0))
                 :browser.session/error-document
                 (browser/load-html {:url (str "kotoba://error?url=" url)
                                     :html (str "<main><h1>Navigation error</h1><p>"
                                                (name (:browser/error page))
                                                "</p></main>")
                                     :viewport (:browser.session/viewport session)
                                     :theme (:browser.session/theme session)})
                 :browser.session/navigation (assoc (:browser.session/navigation session)
                                                    :redirects redirects))
          (audit! (audit/navigation-error-event {:url url
                                                 :status (get-in page [:browser/response :status])
                                                 :error (:browser/error page)
                                                 :profile-id (profile-id session)}))
          (update :browser.session/history conj {:event :navigation/error
                                                 :url url
                                                 :status (get-in page [:browser/response :status])
                                                 :error (:browser/error page)})
          (persist!))
      (-> session
          (assoc :browser.session/navigation
                 (assoc (:browser.session/navigation session) :redirects redirects))
          (commit-page! page)
          (run-page-scripts!)))))))

(defn back!
  [session]
  (let [idx (dec (get-in session [:browser.session/navigation :index] -1))
        entry (get-in session [:browser.session/navigation :entries idx])]
    (if entry
      (-> session
          (assoc-in [:browser.session/navigation :index] idx)
          (commit-page! (:page entry) {:remember? false})
          (audit! (audit/navigation-event {:event :navigation/back
                                           :url (:url entry)
                                           :profile-id (profile-id session)}))
          (update :browser.session/history conj {:event :navigation/back
                                                 :url (:url entry)})
          (persist!))
      session)))

(defn forward!
  [session]
  (let [idx (inc (get-in session [:browser.session/navigation :index] -1))
        entry (get-in session [:browser.session/navigation :entries idx])]
    (if entry
      (-> session
          (assoc-in [:browser.session/navigation :index] idx)
          (commit-page! (:page entry) {:remember? false})
          (audit! (audit/navigation-event {:event :navigation/forward
                                           :url (:url entry)
                                           :profile-id (profile-id session)}))
          (update :browser.session/history conj {:event :navigation/forward
                                                 :url (:url entry)})
          (persist!))
      session)))

(defn reload!
  [session]
  (if-let [url (get-in session [:browser.session/page :browser/url])]
    (-> (navigate! session url)
        (audit! (audit/navigation-event {:event :navigation/reload
                                         :url url
                                         :profile-id (profile-id session)}))
        (update :browser.session/history conj {:event :navigation/reload :url url})
        (persist!))
    session))

(defn- url-encode
  [value]
  #?(:clj (-> (java.net.URLEncoder/encode (str value) "UTF-8")
              (str/replace "%20" "+"))
     :cljs (-> (js/encodeURIComponent (str value))
               (str/replace "%20" "+"))))

(defn- form-query
  [form-data]
  (->> form-data
       (map (fn [{:keys [name value]}]
              (str (url-encode name) "=" (url-encode value))))
       (str/join "&")))

(defn- form-text-plain
  [form-data]
  (->> form-data
       (map (fn [{:keys [name value]}]
              (str name "=" value)))
       (str/join "\r\n")))

(def multipart-boundary "kotoba-browser-form-boundary")

(defn- form-multipart
  [form-data]
  (str
   (->> form-data
        (map (fn [{:keys [name value]}]
               (str "--" multipart-boundary "\r\n"
                    "Content-Disposition: form-data; name=\"" name "\"\r\n\r\n"
                    value "\r\n")))
        (apply str))
   "--" multipart-boundary "--\r\n"))

(defn- submit-method
  [value]
  (case (str/lower-case (str value))
    "post" :post
    "dialog" :dialog
    :get))

(defn- submit-enctype
  [value]
  (case (str/lower-case (str value))
    "text/plain" "text/plain"
    "multipart/form-data" "multipart/form-data"
    "application/x-www-form-urlencoded"))

(defn- append-query
  [url query]
  (let [url (strip-fragment url)]
    (if (str/blank? query)
      url
      (str url (if (str/includes? url "?") "&" "?") query))))

(defn- request-referrer
  [page-url policy]
  (case (str/lower-case (str policy))
    "no-referrer" nil
    "origin" (origin/origin page-url)
    (when policy page-url)))

(defn- form-submit-request
  [session result]
  (let [document (:document result)
        form-id (:form/id result)
        form (get-in document [:nodes form-id])
        attrs (:attrs form)
        submitter-attrs (get-in document [:nodes (:node/id result) :attrs])
        method (submit-method (or (:formmethod submitter-attrs)
                                  (:method attrs)
                                  "get"))
        target (str (or (:formtarget submitter-attrs)
                        (:target attrs)
                        "_self"))
        referrer-policy (or (:formreferrerpolicy submitter-attrs)
                            (:referrerpolicy submitter-attrs)
                            (:referrerpolicy attrs))
        enctype (submit-enctype (or (:formenctype submitter-attrs)
                                    (:enctype attrs)
                                    "application/x-www-form-urlencoded"))]
    (when (:submitted? result)
      (let [page-url (get-in session [:browser.session/page :browser/url])
            action (str (or (:formaction submitter-attrs)
                            (:action attrs)
                            page-url))
            action-url (if (str/blank? action)
                         page-url
                         (page-script/resolve-src page-url action))
            referrer (request-referrer page-url referrer-policy)
            urlencoded-body (form-query (:form/data result))
            text-body (form-text-plain (:form/data result))
            multipart-body (form-multipart (:form/data result))
            post-body (case enctype
                        "text/plain" text-body
                        "multipart/form-data" multipart-body
                        urlencoded-body)
            content-type (case enctype
                           "text/plain" "text/plain"
                           "multipart/form-data" (str "multipart/form-data; boundary=" multipart-boundary)
                           "application/x-www-form-urlencoded")]
        (case method
          :get (cond-> {:method :get
                        :url (append-query action-url urlencoded-body)
                        :target target
                        :enctype enctype
                        :form/id form-id
                        :submitter/id (:node/id result)
                        :form/data (:form/data result)}
                 referrer-policy (assoc :referrer-policy referrer-policy)
                 referrer (assoc :referrer/header referrer))
          :post (cond-> {:method :post
                         :url (strip-fragment action-url)
                         :target target
                         :enctype enctype
                         :headers {"content-type" content-type}
                         :body post-body
                         :form/id form-id
                         :submitter/id (:node/id result)
                         :form/data (:form/data result)}
                  referrer-policy (assoc :referrer-policy referrer-policy)
                  referrer (assoc :referrer/header referrer))
          :dialog {:method :dialog
                   :target target
                   :referrer-policy referrer-policy
                   :enctype enctype
                   :form/id form-id
                   :submitter/id (:node/id result)
                   :form/data (:form/data result)}
          nil)))))

(defn- link-navigation-request
  [session result]
  (when-let [href (:navigation/href result)]
    (let [page-url (get-in session [:browser.session/page :browser/url])]
      {:url (page-script/resolve-src page-url href)
       :href href
       :target (:navigation/target result)
       :rel (:navigation/rel result)
       :referrer-policy (:navigation/referrer-policy result)
       :download? (:navigation/download? result)
       :download/filename (:download/filename result)
       :link/id (:link/id result)
       :node/id (:node/id result)})))

(defn download-request
  "Build an explicit download capability request from a link navigation request."
  [link-request]
  (when (:download? link-request)
    (cond-> {:capability :download/request
             :url (:url link-request)
             :href (:href link-request)
             :target (:target link-request)
             :link/id (:link/id link-request)
             :node/id (:node/id link-request)}
      (:download/filename link-request)
      (assoc :download/filename (:download/filename link-request))

      (:referrer-policy link-request)
      (assoc :referrer-policy (:referrer-policy link-request)))))

(defn- current-target?
  [target]
  (contains? #{"" "_self" "_top" "_parent"} (str/lower-case (str target))))

(defn- token-attr?
  [value token]
  (contains? (set (str/split (str/lower-case (str value)) #"\s+"))
             token))

(defn- link-noreferrer?
  [request]
  (token-attr? (:rel request) "noreferrer"))

(defn- link-request-referrer
  [session request]
  (when-not (link-noreferrer? request)
    (request-referrer (get-in session [:browser.session/page :browser/url])
                      (:referrer-policy request))))

(defn- link-navigation-event
  [session request]
  (let [referrer (link-request-referrer session request)]
    (cond-> {:event :link/navigation
             :url (:url request)
             :href (:href request)
             :target (:target request)
             :link/id (:link/id request)
             :node/id (:node/id request)}
      (:rel request) (assoc :rel (:rel request))
      (:referrer-policy request) (assoc :referrer-policy (:referrer-policy request))
      (or (:rel request) (:referrer-policy request) referrer)
      (assoc :referrer referrer))))

(defn- link-context-request-event
  [session request]
  (let [noreferrer? (link-noreferrer? request)
        noopener? (or (token-attr? (:rel request) "noopener")
                      noreferrer?
                      (= "_blank" (:target request)))]
    {:event :link/context-request
     :url (:url request)
     :href (:href request)
     :target (:target request)
     :rel (:rel request)
     :referrer-policy (:referrer-policy request)
     :referrer (when-not noreferrer?
                 (get-in session [:browser.session/page :browser/url]))
     :opener? (not noopener?)
     :link/id (:link/id request)
     :node/id (:node/id request)}))

(defn- form-context-request-event
  [session request]
  (cond-> {:event :form/context-request
           :url (:url request)
           :method (:method request)
           :target (:target request)
           :referrer-policy (:referrer-policy request)
           :referrer (when (not= "no-referrer" (str/lower-case (str (:referrer-policy request))))
                       (get-in session [:browser.session/page :browser/url]))
           :enctype (:enctype request)
           :form/id (:form/id request)
           :submitter/id (:submitter/id request)
           :form/data (:form/data request)}
    (:headers request) (assoc :headers (:headers request))
    (:body request) (assoc :body (:body request))))

(defn- commit-form-post-response!
  [session request fetch-result]
  (let [session (cond-> session
                  (contains? fetch-result :store)
                  (assoc :browser.session/store (:store fetch-result)))
        response (:response fetch-result)
        status (:status response)
        location (net/header (:headers response) "location")]
    (cond
      (and (:allowed? fetch-result)
           (contains? redirect-statuses status)
           location)
      (let [to-url (page-script/resolve-src (:url request) location)]
        (-> session
            (update :browser.session/history conj
                    {:event :form/submit-redirect
                     :url (:url request)
                     :to to-url
                     :method :post
                     :target (:target request)
                     :enctype (:enctype request)
                     :status status
                     :form/id (:form/id request)
                     :submitter/id (:submitter/id request)
                     :form/data (:form/data request)})
            (navigate! to-url)))

      (and (:allowed? fetch-result)
           (<= 200 (or status 0) 299))
      (-> session
          (update :browser.session/history conj
                  {:event :form/submit-fetch
                   :url (:url request)
                   :method :post
                   :target (:target request)
                   :enctype (:enctype request)
                   :status status
                   :form/id (:form/id request)
                   :submitter/id (:submitter/id request)
                   :form/data (:form/data request)})
          (commit-page! (assoc (browser/load-html {:url (:url request)
                                                   :html (:body response)
                                                   :viewport (:browser.session/viewport session)
                                                   :theme (:browser.session/theme session)})
                               :browser/response response)))

      :else
      (-> session
          (update :browser.session/history conj
                  {:event :form/submit-fetch-blocked
                   :url (:url request)
                   :method :post
                   :target (:target request)
                   :enctype (:enctype request)
                   :status status
                   :error (:error response)
                   :allowed? (:allowed? fetch-result)
                   :form/id (:form/id request)
                   :submitter/id (:submitter/id request)})
          (persist!)))))

(defn render-surface!
  [session]
  (let [rendered (surface/render-surface (:browser.session/surface session))
        batch (host/commit! (:browser.session/host session) (:ops rendered))]
    (-> session
        (assoc :browser.session/last-batch batch
               :browser.session/error nil)
        (audit! (audit/surface-commit-event {:surface-id (get-in rendered [:surface :surface/id])
                                             :op-count (count (:ops rendered))
                                             :profile-id (profile-id session)}))
        (update :browser.session/history conj {:event :surface/commit
                                               :surface/id (get-in rendered [:surface :surface/id])
                                               :op-count (count (:ops rendered))})
        (persist!))))

(defn resume-current-surface!
  "Present the restored/current OS surface to the host without reducing input."
  [session]
  (if-let [surface-state (:browser.session/surface session)]
    (let [rendered (surface/render-surface surface-state)
          batch (host/commit! (:browser.session/host session) (:ops rendered))]
      (-> session
          (assoc :browser.session/last-batch batch
                 :browser.session/error nil)
          (audit! (audit/surface-commit-event {:surface-id (get-in rendered [:surface :surface/id])
                                               :op-count (count (:ops rendered))
                                               :profile-id (profile-id session)}))
          (update :browser.session/history conj {:event :surface/resume
                                                 :surface/id (get-in rendered [:surface :surface/id])
                                                 :op-count (count (:ops rendered))})
          (persist!)))
    session))

(defn apply-surface-action!
  [session action]
  (-> session
      (update :browser.session/surface surface/apply-action action)
      (render-surface!)))

(defn apply-input-event!
  [session event]
  (let [{:keys [surface input actions]}
        (input/reduce-event (:browser.session/surface session)
                            (:browser.session/input session)
                            event)]
    (-> session
        (assoc :browser.session/surface surface
               :browser.session/input input)
        (render-surface!)
        (audit! (audit/input-event {:event-type (:event/type (input/normalize-event event))
                                    :action-count (count actions)
                                    :profile-id (profile-id session)}))
        (update :browser.session/history conj {:event :input/reduce
                                               :input/event-type (:event/type (input/normalize-event event))
                                               :action-count (count actions)})
        (persist!))))

(defn- point-in-node?
  [x y op]
  (and (number? x)
       (number? y)
       (<= (:x op) x (+ (:x op) (:w op)))
       (<= (:y op) y (+ (:y op) (:h op)))))

(defn- intersect-rect [a b]
  (let [x0 (max (:x a) (:x b))
        y0 (max (:y a) (:y b))
        x1 (min (+ (:x a) (:w a)) (+ (:x b) (:w b)))
        y1 (min (+ (:y a) (:h a)) (+ (:y b) (:h b)))]
    (when (and (< x0 x1) (< y0 y1))
      {:x x0 :y y0 :w (- x1 x0) :h (- y1 y0)})))

(defn- active-clip [clip-stack]
  (reduce intersect-rect (first clip-stack) (rest clip-stack)))

(defn- hit-nodes
  [draw-ops]
  (loop [remaining draw-ops
         clip-stack []
         nodes []]
    (if-let [op (first remaining)]
      (case (:draw/op op)
        :clip
        (if (= :push (:clip/op op))
          (recur (rest remaining)
                 (conj clip-stack (select-keys op [:x :y :w :h]))
                 nodes)
          (recur (rest remaining)
                 (if (seq clip-stack) (pop clip-stack) clip-stack)
                 nodes))

        :node
        (let [clip (active-clip clip-stack)]
          (recur (rest remaining)
                 clip-stack
                 (conj nodes (cond-> op
                                clip (assoc :hit/clip clip)))))

        (recur (rest remaining) clip-stack nodes))
      nodes)))

(defn- node-at
  [page x y pred]
  (->> (hit-nodes (:browser/draw-ops page))
       reverse
       (some (fn [op]
               (when (and (point-in-node? x y op)
                          (not= "none" (:pointer-events op))
                          (if-let [clip (:hit/clip op)]
                            (point-in-node? x y clip)
                            true)
                          (pred op))
                 (:id op))))))

(defn- resolve-document-input-target
  [session event]
  (let [event (input/normalize-event event)]
    (cond
      (or (:node/id event) (:node/selector event))
      event

      (= :pointer/wheel (:event/type event))
      (if-let [node-id (node-at (:browser.session/page session)
                                (:x event)
                                (:y event)
                                #(contains? #{"auto" "scroll"} (:overflow %)))]
        (assoc event :node/id node-id)
        event)

      (and (contains? #{:pointer/move :pointer/up :pointer/cancel}
                      (:event/type event))
           (get-in session [:browser.session/page
                            :browser/document
                            :pointer/capture
                            (document-input/pointer-id event)]))
      (assoc event :node/id
             (get-in session [:browser.session/page
                              :browser/document
                              :pointer/capture
                              (document-input/pointer-id event)]))

      (contains? #{:pointer/down :pointer/up :pointer/cancel
                   :pointer/click :pointer/move}
                 (:event/type event))
      (if-let [node-id (node-at (:browser.session/page session)
                                (:x event)
                                (:y event)
                                (constantly true))]
        (assoc event :node/id node-id)
        event)

      :else event)))

(defn apply-document-input-event!
  [session event]
  (if-let [document (get-in session [:browser.session/page :browser/document])]
    (let [event (resolve-document-input-target session event)
          result (document-input/reduce-event document event)
          document (:document result)
          node-id (:node/id result)
          handled? (:handled? result)
          event (:event result)
          commit? (if (contains? result :commit?)
                    (:commit? result)
                    handled?)
          session (if commit?
                    (commit-document! session document)
                    session)]
      (let [session (-> session
                        (audit! (audit/input-event {:event-type (:event/type event)
                                                    :action-count (if handled? 1 0)
                                                    :profile-id (profile-id session)}))
                        (update :browser.session/history conj {:event :document/input
                                                               :input/event-type (:event/type event)
                                                               :node/id node-id
                                                               :handled? handled?})
                        (assoc :browser.session/document-input-result result)
                        (persist!))
            submit-request (when (:browser.session/fetch-fn session)
                             (form-submit-request session result))
            link-request (link-navigation-request session result)]
        (case (:method submit-request)
          :get
          (if (current-target? (:target submit-request))
            (-> session
                (update :browser.session/history conj
                        (cond-> {:event :form/submit-navigation
                                 :url (:url submit-request)
                                 :target (:target submit-request)
                                 :enctype (:enctype submit-request)
                                 :form/id (:form/id submit-request)
                                 :submitter/id (:submitter/id submit-request)
                                 :form/data (:form/data submit-request)}
                          (:referrer-policy submit-request)
                          (assoc :referrer-policy (:referrer-policy submit-request))
                          (:referrer/header submit-request)
                          (assoc :referrer (:referrer/header submit-request))))
                (navigate! (:url submit-request)
                           (select-keys submit-request [:headers :referrer/header]))
                (assoc :browser.session/document-input-result result))
            (-> session
                (update :browser.session/history conj (form-context-request-event session submit-request))
                (persist!)
                (assoc :browser.session/document-input-result result)))

          :post
          (if (current-target? (:target submit-request))
            (let [fetch-result (net/fetch-resource (assoc (net-context session)
                                                  :fetch-fn (:browser.session/fetch-fn session)
                                                  :cache? false)
                                                  (select-keys submit-request
                                                               [:url :method :headers :body :referrer/header]))
                  session (commit-form-post-response! session submit-request fetch-result)]
              (assoc session :browser.session/document-input-result result))
            (-> session
                (update :browser.session/history conj (form-context-request-event session submit-request))
                (persist!)
                (assoc :browser.session/document-input-result result)))

          :dialog
          (-> session
              (update :browser.session/history conj {:event :form/submit-dialog
                                                     :target (:target submit-request)
                                                     :enctype (:enctype submit-request)
                                                     :form/id (:form/id submit-request)
                                                     :submitter/id (:submitter/id submit-request)
                                                     :form/data (:form/data submit-request)})
              (persist!)
              (assoc :browser.session/document-input-result result))

          (if (:download? link-request)
            (-> session
                (update :browser.session/history conj
                        (cond-> {:event :link/download-request
                                 :url (:url link-request)
                                 :href (:href link-request)
                                 :target (:target link-request)
                                 :link/id (:link/id link-request)
                                 :node/id (:node/id link-request)}
                          (:download/filename link-request)
                          (assoc :download/filename (:download/filename link-request))))
                (assoc :browser.session/download-request (download-request link-request))
                (persist!)
                (assoc :browser.session/document-input-result result))
            (if (and link-request (not (current-target? (:target link-request))))
              (-> session
                  (update :browser.session/history conj (link-context-request-event session link-request))
                  (persist!)
                  (assoc :browser.session/document-input-result result))
              (if (and link-request (:browser.session/fetch-fn session))
                (let [referrer (link-request-referrer session link-request)
                      navigate-request (cond-> {}
                                         referrer (assoc :referrer/header referrer))]
                  (-> session
                      (update :browser.session/history conj (link-navigation-event session link-request))
                      (navigate! (:url link-request) navigate-request)
                      (assoc :browser.session/document-input-result result)))
                session))))))
    session))
