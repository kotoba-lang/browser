(ns browser.net
  "Capability-scoped network policy helpers.

   Fetch I/O is still injected by the host. This namespace owns the browser-side
   data decisions around origin, CORS, cookies, and profile-scoped cache state."
  (:require [browser.origin :as origin]
            [browser.profile :as profile-model]
            [browser.storage :as storage]
            [clojure.string :as str]))

(def cache-key :http/cache)
(def cache-variant-key :http/cache-variants)
(def cookie-key :http/cookies)
(def cookie-path-key :http/cookie-paths)
(def cookie-domain-key :http/cookie-domains)
(def cookie-same-site-key :http/cookie-same-sites)
(def cookie-http-only-key :http/cookie-http-only)
(def cookie-secure-key :http/cookie-secure)
(def cookie-expires-at-key :http/cookie-expires-at)
(def cookie-variant-key :http/cookie-variants)

(defn- lower-name [x]
  (str/lower-case (name x)))

(defn header
  [headers name]
  (let [want (lower-name name)]
    (some (fn [[k v]]
            (when (= want (lower-name k)) v))
          headers)))

(defn- parse-set-cookie
  [set-cookie]
  (let [[pair & attrs] (str/split (str set-cookie) #";")
        [k v] (str/split pair #"=" 2)
        k (str/trim (or k ""))
        attrs (->> attrs
                   (map str/trim)
                   (remove str/blank?)
                   (map (fn [attr]
                          (let [[ak av] (str/split attr #"=" 2)]
                            [(str/lower-case (str/trim (or ak "")))
                             (some-> av str/trim)])))
                   (into {}))]
    (when (seq k)
      {:name k
       :value (str (or v ""))
       :secure? (contains? attrs "secure")
       :http-only? (contains? attrs "httponly")
       :domain (some-> (get attrs "domain") str/trim)
       :path (some-> (get attrs "path") str/trim)
       :same-site (some-> (get attrs "samesite") str/lower-case)
       :max-age (some-> (get attrs "max-age") str/trim)
       :expires (some-> (get attrs "expires") str/trim)})))

(defn- parse-int-value
  [s]
  #?(:clj (try
            (Long/parseLong (str/trim (str s)))
            (catch Exception _ nil))
     :cljs (let [parsed (js/parseInt (str/trim (str s)) 10)]
             (when-not (js/isNaN parsed) parsed))))

(defn- current-time-ms
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- expires-date-ms
  [expires]
  (when expires
    #?(:clj (try
              (.toEpochMilli (.toInstant (java.time.ZonedDateTime/parse
                                          (str expires)
                                          java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME)))
              (catch Exception _ nil))
       :cljs (let [parsed (js/Date.parse (str expires))]
               (when-not (js/isNaN parsed) parsed)))))

(defn- expired-expires-date?
  [expires]
  (when expires
    (or (when-let [ms (expires-date-ms expires)]
          (<= ms (current-time-ms)))
        (let [s (str/lower-case (str expires))]
          (or (str/includes? s "01 jan 1970")
              (str/includes? s "1 jan 1970")
              (str/includes? s "31 dec 1969"))))))

(defn- expired-cookie?
  [{:keys [max-age expires]}]
  (if max-age
    (when-let [n (parse-int-value max-age)]
      (<= n 0))
    (expired-expires-date? expires)))

(defn- cookie-expires-at-ms
  [{:keys [max-age expires]}]
  (if max-age
    (when-let [n (parse-int-value max-age)]
      (when (pos? n)
        (+ (current-time-ms) (* n 1000))))
    (expires-date-ms expires)))

(defn- cookie-variant-expired?
  [variant]
  (when-let [expires-at (:expires-at-ms variant)]
    (<= expires-at (current-time-ms))))

(defn- cookie-prefix-acceptable?
  [{:keys [name secure? domain path]} https?]
  (cond
    (str/starts-with? name "__Host-")
    (and https? secure? (nil? domain) (= "/" path))

    (str/starts-with? name "__Secure-")
    (and https? secure?)

    :else
    true))

(defn- default-cookie-path
  [url]
  (let [path (:path (origin/parse-url url))
        slash (.lastIndexOf (str path) "/")]
    (cond
      (or (str/blank? path) (= "/" path) (not (str/starts-with? path "/"))) "/"
      (<= slash 0) "/"
      :else (subs path 0 slash))))

(defn- cookie-path
  [url path]
  (let [path (str path)]
    (if (str/starts-with? path "/")
      path
      (default-cookie-path url))))

(defn- cookie-path-matches?
  [request-path cookie-path]
  (or (nil? cookie-path)
      (= "/" cookie-path)
      (= request-path cookie-path)
      (and (str/starts-with? request-path cookie-path)
           (or (str/ends-with? cookie-path "/")
               (= "/" (subs request-path (count cookie-path) (inc (count cookie-path))))))))

(defn- url-host
  [url]
  (some-> (:authority (origin/parse-url url))
          (str/split #":")
          first
          str/lower-case))

(defn- normalize-cookie-domain
  [domain]
  (let [domain (str/lower-case (str/trim (str domain)))]
    (when (seq domain)
      (if (str/starts-with? domain ".")
        (subs domain 1)
        domain))))

(def public-suffix-like-domains
  #{"co.uk" "org.uk" "ac.uk" "gov.uk"
    "com.au" "net.au" "org.au"
    "co.jp" "ne.jp" "or.jp" "go.jp"
    "com.br" "com.cn" "com.mx"})

(defn- public-suffix-like-domain?
  [domain]
  (let [labels (str/split (str domain) #"\.")]
    (or (< (count labels) 2)
        (contains? public-suffix-like-domains domain))))

(defn- ipv4-address?
  [host]
  (boolean (re-matches #"(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9]|0)\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9]|0)"
                       (str host))))

(defn- domain-matches?
  "RFC 6265 5.1.3: a suffix match only counts when the request host is a
  real host NAME -- if it's an IP address, only an EXACT match is a
  domain-match, never a subdomain-style suffix. Without this, an IPv4
  host like \"192.168.1.1\" would accept a cookie's Domain=168.1.1 as a
  valid \"parent domain\" and that same cookie would then leak to any
  OTHER, completely unrelated IP host sharing the numeric suffix (e.g.
  \"10.168.1.1\") -- a real, reachable cookie leak between unrelated
  devices on a local network, since IP-literal URLs are ordinary,
  supported page URLs here (LAN admin panels, local dev servers, ...)."
  [host domain]
  (or (= host domain)
      (and (not (ipv4-address? host))
           (str/ends-with? host (str "." domain))
           (< (count domain) (count host)))))

(defn- cookie-domain
  [url domain]
  (let [host (url-host url)
        domain (normalize-cookie-domain domain)]
    (when (and host
               domain
               (not (public-suffix-like-domain? domain))
               (domain-matches? host domain))
      domain)))

(defn- cookie-store-url
  [url domain]
  (if-let [domain (cookie-domain url domain)]
    (str (:scheme (origin/parse-url url)) "://" domain "/")
    url))

(defn- cookie-domain-candidate-urls
  [url]
  (let [{:keys [scheme]} (origin/parse-url url)
        host (url-host url)
        labels (str/split (str host) #"\.")]
    (->> (range 0 (count labels))
         (map #(str/join "." (drop % labels)))
         (remove str/blank?)
         (map #(str scheme "://" % "/"))
         distinct
         vec)))

(defn- cookie-domain-matches?
  [request-host cookie-domain]
  (or (nil? cookie-domain)
      (domain-matches? request-host cookie-domain)))

(defn- site-host
  "The SameSite/CSRF-relevant \"site\" for a URL: the registrable domain,
   i.e. one label ABOVE the public suffix -- not just a naive last-2-labels
   guess. A bare last-2-labels join wrongly conflates any two hosts that
   happen to share a multi-label public suffix like `co.uk`/`com.au`
   (`victim.co.uk` and `attacker.co.uk` would both naively reduce to the
   same \"co.uk\"), which would make a SameSite=Strict cookie set on one
   wrongly get sent on a cross-site request whose page is the other -- a
   real CSRF-protection bypass, confirmed via a direct REPL reproduction
   before this fix. Reuses this file's own `public-suffix-like-domains`
   (the same set `cookie-domain` above already consults for a related
   purpose): when the naive last-2-labels join IS a known public suffix,
   one more label is included instead."
  [url]
  (let [labels (str/split (str (url-host url)) #"\.")
        n (count labels)]
    (cond
      (< n 2) (first labels)
      (and (>= n 3) (contains? public-suffix-like-domains (str/join "." (take-last 2 labels))))
      (str/join "." (take-last 3 labels))
      :else (str/join "." (take-last 2 labels)))))

(defn- same-site?
  [a b]
  (let [a-url (origin/parse-url a)
        b-url (origin/parse-url b)]
    (and (= (:scheme a-url) (:scheme b-url))
         (= (site-host a) (site-host b)))))

(defn- cookie-same-site-matches?
  ([page-url request-url same-site]
   (cookie-same-site-matches? page-url request-url same-site false))
  ([page-url request-url same-site top-level-navigation?]
   ;; RFC 6265bis: a cookie with no SameSite attribute (nil here) defaults to
   ;; Lax, not None. Only an *explicit* SameSite=None opts a cookie into being
   ;; sent on cross-site requests; every other value (missing, "lax",
   ;; "strict", or an unrecognized token) must still satisfy the same-site
   ;; check, matching real browsers' Lax-by-default CSRF mitigation --
   ;; EXCEPT that Lax (and the missing/unrecognized default, which also
   ;; behaves as Lax) specifically carves out one case real browsers still
   ;; allow cross-site: a top-level, safe-method (GET) navigation, e.g.
   ;; clicking an ordinary link from another site straight into this one.
   ;; That's the entire reason Lax exists as a *distinct*, less restrictive
   ;; level than Strict -- Strict blocks that case too, and must keep doing
   ;; so even here. Without this carve-out, a page whose session cookie
   ;; relies on the (extremely common) Lax/default behavior would appear
   ;; logged out the instant a user arrives via any inbound cross-site
   ;; link, since every other real browser still sends it.
   (let [same-site-kw (str/lower-case (str same-site))]
     (cond
       (= "none" same-site-kw) true
       (and top-level-navigation? (not= "strict" same-site-kw)) true
       :else (same-site? page-url request-url)))))

(defn- cookie-secure-matches?
  [request-url secure?]
  (or (not secure?)
      (= "https" (:scheme (origin/parse-url request-url)))))

(defn- distinct-cookie-names
  [entries]
  (loop [remaining entries
         seen #{}
         result []]
    (if-let [[name :as entry] (first remaining)]
      (if (contains? seen name)
        (recur (rest remaining) seen result)
        (recur (rest remaining) (conj seen name) (conj result entry)))
      result)))

(defn- cookie-variant-id
  [name domain path]
  [name (or domain "") path])

(defn- cookie-variant-path-length
  [variant]
  (count (str (or (:path variant) ""))))

(defn- legacy-cookie-variants
  [cookies cookie-paths cookie-domains cookie-same-sites cookie-http-only cookie-secure cookie-expires-at]
  (into {}
        (map (fn [[name value]]
               (let [path (or (get cookie-paths name) "/")
                     domain (get cookie-domains name)
                     variant {:name name
                              :value value
                              :path path
                              :domain domain
                              :same-site (get cookie-same-sites name)
                              :http-only? (boolean (get cookie-http-only name))
                              :secure? (boolean (get cookie-secure name))
                              :expires-at-ms (get cookie-expires-at name)}]
                 [(cookie-variant-id name domain path) variant])))
        cookies))

(defn- cookie-variants
  [store profile store-url]
  (let [variants (storage/get-value store profile store-url cookie-variant-key)]
    (if (seq variants)
      variants
      (legacy-cookie-variants
       (or (storage/get-value store profile store-url cookie-key) {})
       (storage/get-value store profile store-url cookie-path-key)
       (storage/get-value store profile store-url cookie-domain-key)
       (storage/get-value store profile store-url cookie-same-site-key)
       (storage/get-value store profile store-url cookie-http-only-key)
       (storage/get-value store profile store-url cookie-secure-key)
       (storage/get-value store profile store-url cookie-expires-at-key)))))

(defn- projected-cookie-metadata
  [variants k pred]
  (reduce (fn [result [_ {:keys [name] :as variant}]]
            (if (and (pred variant) (not (contains? result name)))
              (assoc result name (k variant))
              result))
          {}
          (sort-by (fn [[_ variant]]
                     [(:name variant) (- (cookie-variant-path-length variant))])
                   variants)))

(defn- projected-cookie-values
  [variants]
  (reduce (fn [result [_ {:keys [name value]}]]
            (if (contains? result name)
              result
              (assoc result name value)))
          {}
          (sort-by (fn [[_ variant]]
                     [(:name variant) (- (cookie-variant-path-length variant))])
                   variants)))

(defn- put-cookie-variants
  [store profile store-url variants]
  (let [cookies (projected-cookie-values variants)
        cookie-paths (projected-cookie-metadata variants :path :path)
        cookie-domains (projected-cookie-metadata variants :domain :domain)
        cookie-same-sites (projected-cookie-metadata variants :same-site :same-site)
        cookie-http-only (projected-cookie-metadata variants (constantly true) :http-only?)
        cookie-secure (projected-cookie-metadata variants (constantly true) :secure?)
        cookie-expires-at (projected-cookie-metadata variants :expires-at-ms :expires-at-ms)]
    (-> store
        (storage/put-value profile store-url cookie-variant-key variants)
        (storage/put-value profile store-url cookie-key cookies)
        (storage/put-value profile store-url cookie-path-key cookie-paths)
        (storage/put-value profile store-url cookie-domain-key cookie-domains)
        (storage/put-value profile store-url cookie-same-site-key cookie-same-sites)
        (storage/put-value profile store-url cookie-http-only-key cookie-http-only)
        (storage/put-value profile store-url cookie-secure-key cookie-secure)
        (storage/put-value profile store-url cookie-expires-at-key cookie-expires-at))))

(defn- cookie-entries
  ([store profile page-url url include-http-only?]
   (cookie-entries store profile page-url url include-http-only? false))
  ([store profile page-url url include-http-only? top-level-navigation?]
   (let [request-path (:path (origin/parse-url url))
         request-host (url-host url)]
     (distinct-cookie-names
      (mapcat
       (fn [store-url]
         (let [variants (cookie-variants store profile store-url)]
           (keep (fn [[_ {:keys [name value path domain same-site http-only? secure?] :as variant}]]
                   (when (and (cookie-path-matches? request-path path)
                              (cookie-domain-matches? request-host domain)
                              (cookie-same-site-matches? page-url url same-site top-level-navigation?)
                              (cookie-secure-matches? url secure?)
                              (not (cookie-variant-expired? variant))
                              (or include-http-only?
                                  (not http-only?)))
                     [name value]))
                 (sort-by (fn [[_ variant]]
                            (- (cookie-variant-path-length variant)))
                          variants))))
       (cookie-domain-candidate-urls url))))))

(defn- purge-expired-cookies
  [store profile url]
  (if (and store profile)
    (reduce (fn [store store-url]
              (let [variants (cookie-variants store profile store-url)
                    live-variants (into {}
                                        (remove (fn [[_ variant]]
                                                  (cookie-variant-expired? variant)))
                                        variants)]
                (if (= variants live-variants)
                  store
                  (put-cookie-variants store profile store-url live-variants))))
            store
            (cookie-domain-candidate-urls url))
    store))

(defn- cookie-header-from-entries
  [entries]
  (not-empty
   (str/join "; "
             (map (fn [[k v]] (str k "=" v))
                  entries))))

(defn cookie-header
  ([store profile url]
   (cookie-header store profile url url))
  ([store profile page-url url]
   (cookie-header store profile page-url url false))
  ([store profile page-url url top-level-navigation?]
   (cookie-header-from-entries
    (cookie-entries store profile page-url url true top-level-navigation?))))

(defn script-cookie-header
  ([store profile url]
   (script-cookie-header store profile url url))
  ([store profile page-url url]
   (cookie-header-from-entries
    (cookie-entries store profile page-url url false))))

(defn- set-cookie-values
  [response]
  (let [value (header (:headers response) "set-cookie")]
    (cond
      (nil? value) []
      (sequential? value) value
      :else [value])))

(defn- remember-one-set-cookie
  [store profile url set-cookie allow-http-only?]
  (if-let [{:keys [name value secure? domain path same-site] :as cookie} (parse-set-cookie set-cookie)]
    (let [https? (= "https" (:scheme (origin/parse-url url)))
          cookie-domain (cookie-domain url domain)
          acceptable? (and (or (not secure?) https?)
                           (or (not= "none" same-site) secure?)
                           (cookie-prefix-acceptable? cookie https?)
                           (or (nil? domain) cookie-domain))
          store-url (cookie-store-url url domain)]
      (if acceptable?
        (let [expired? (expired-cookie? cookie)
              path (cookie-path url path)
              variant-id (cookie-variant-id name cookie-domain path)
              variants (cond-> (cookie-variants store profile store-url)
                         expired? (dissoc variant-id)
                        (not expired?) (assoc variant-id
                                               {:name name
                                                :value value
                                                :path path
                                                :domain cookie-domain
                                                :same-site same-site
                                                :http-only? (and allow-http-only? (:http-only? cookie))
                                                :secure? secure?
                                                :expires-at-ms (cookie-expires-at-ms cookie)}))]
          (put-cookie-variants store profile store-url variants))
        store))
    store))

(defn- remember-set-cookie
  [store profile url response]
  (reduce #(remember-one-set-cookie %1 profile url %2 true)
          store
          (set-cookie-values response)))

(defn remember-response-cookies
  "Apply network-visible Set-Cookie headers from response into profile storage."
  [store profile url response]
  (remember-set-cookie store profile url response))

(defn script-set-cookie
  [store profile url cookie]
  (remember-one-set-cookie store profile url cookie false))

(defn cors-allowed?
  ([page-url response-url response]
   (cors-allowed? page-url response-url response false))
  ([page-url response-url response credentials?]
   (or (origin/same-origin? page-url response-url)
       (let [allow-origin (header (:headers response) "access-control-allow-origin")
             allow-credentials? (= "true" (str/lower-case
                                           (str (header (:headers response)
                                                        "access-control-allow-credentials"))))
             page-origin (origin/origin page-url)]
         (if credentials?
           (and (= page-origin allow-origin) allow-credentials?)
           (or (= "*" allow-origin)
               (= page-origin allow-origin)))))))

(def simple-methods #{:get :head :post})

(def simple-headers #{"accept" "accept-language" "content-language" "content-type"})

(def simple-content-types
  #{"application/x-www-form-urlencoded" "multipart/form-data" "text/plain"})

(def browser-managed-headers #{"cookie" "origin" "referer"})

(defn- content-type-value
  [value]
  (first (str/split (str/lower-case (str value)) #";")))

(defn- simple-header?
  [[k v]]
  (let [header-name (lower-name k)]
    (or (contains? browser-managed-headers header-name)
        (and (contains? simple-headers header-name)
             (or (not= "content-type" header-name)
                 (contains? simple-content-types (content-type-value v)))))))

(defn- author-header-names
  [headers]
  (->> headers
       (map (comp lower-name key))
       (remove browser-managed-headers)
       distinct
       sort
       vec))

(defn- preflight-required?
  [page-url request]
  (and (not (origin/same-origin? page-url (:url request)))
       (or (not (contains? simple-methods (or (:method request) :get)))
           (some (complement simple-header?) (:headers request)))))

(defn- preflight-request
  [page-url request]
  (let [header-names (author-header-names (:headers request))]
    (cond-> {:url (:url request)
             :method :options
             :headers {"origin" (origin/origin page-url)
                       "access-control-request-method" (str/upper-case (name (or (:method request) :get)))}}
      (seq header-names)
      (assoc-in [:headers "access-control-request-headers"] (str/join ", " header-names)))))

(defn- token-list
  [value]
  (->> (str/split (str/lower-case (str value)) #",")
       (map str/trim)
       (remove str/blank?)
       set))

(defn- preflight-allowed?
  [page-url request response credentials?]
  (let [method (str/lower-case (name (or (:method request) :get)))
        requested-headers (set (author-header-names (:headers request)))
        allowed-methods (token-list (header (:headers response) "access-control-allow-methods"))
        allowed-headers (token-list (header (:headers response) "access-control-allow-headers"))]
    (and (<= 200 (or (:status response) 0) 299)
         (cors-allowed? page-url (:url request) response credentials?)
         (or (contains? allowed-methods method)
             (and (not credentials?) (contains? allowed-methods "*")))
         (or (empty? requested-headers)
             (and (not credentials?) (contains? allowed-headers "*"))
             (every? allowed-headers requested-headers)))))

(defn cached-response
  [store profile url]
  (storage/get-value store profile url cache-key))

(defn- cache-credentials-id
  [send-credentials?]
  (if send-credentials? :credentialed :anonymous))

(defn- cache-variant-id
  [page-url url response send-credentials?]
  (cond
    (origin/same-origin? page-url url)
    [:same-origin (cache-credentials-id send-credentials?)]

    (= "*" (header (:headers response) "access-control-allow-origin"))
    :cross-origin/*

    :else
    [:cross-origin (origin/origin page-url) (cache-credentials-id send-credentials?)]))

(defn- cached-response-for
  [store profile page-url url send-credentials?]
  (let [variants (storage/get-value store profile url cache-variant-key)
        origin-id (origin/origin page-url)
        credentials-id (cache-credentials-id send-credentials?)]
    (or (get variants [:cross-origin origin-id credentials-id])
        (get variants [:cross-origin origin-id])
        (when-not send-credentials?
          (get variants :cross-origin/*))
        (get variants [:same-origin credentials-id])
        (get variants :same-origin)
        (when-not (seq variants)
          (cached-response store profile url)))))

(defn- remember-cache-response
  [store profile page-url request response send-credentials?]
  (let [url (:url request)
        variant-id (cache-variant-id page-url url response send-credentials?)
        variants (assoc (or (storage/get-value store profile url cache-variant-key) {})
                        variant-id
                        response)]
    (-> store
        (storage/put-value profile url cache-variant-key variants)
        (storage/put-value profile url cache-key response))))

(defn cacheable?
  [request response]
  (and (= :get (or (:method request) :get))
       (<= 200 (or (:status response) 0) 299)
       (not (str/includes? (str/lower-case (str (header (:headers response) "cache-control")))
                           "no-store"))))

(defn permission-decision
  [{:keys [profile permission?]} url]
  (cond
    permission?
    (permission? url :net/fetch)

    profile
    (profile-model/permission-decision profile (origin/origin url) :net/fetch)

    :else
    {:permission/decision :deny
     :origin (origin/origin url)
     :capability :net/fetch
     :profile/id nil
     :reason :permission/no-profile}))

(defn allowed-decision? [decision]
  (= :allow (:permission/decision decision)))

(defn fetch-resource
  [{:keys [store profile fetch-fn page-url cache? credentials] :as context
    :or {cache? true credentials :same-origin}}
   request]
  (let [url (:url request)
        method (or (:method request) :get)
        store (purge-expired-cookies store profile url)
        same-origin? (origin/same-origin? page-url url)
        send-credentials? (or (= :include credentials)
                              (and (= :same-origin credentials) same-origin?))
        decision (permission-decision context url)
        cached (when (and cache? (= :get method) store profile)
                 (cached-response-for store profile page-url url send-credentials?))
        cached-allowed? (when cached
                          (cors-allowed? page-url url cached send-credentials?))]
    (cond
      (not (allowed-decision? decision))
      {:store store
       :request request
       :response {:status 0
                  :error (:reason decision)
                  :permission/decision decision}
       :cache/hit? false
       :allowed? false
       :permission/decision decision}

      cached-allowed?
      {:store store
       :request request
       :response (assoc cached :cache/hit? true)
       :cache/hit? true
       :allowed? true
       :permission/decision decision}

      :else
      (let [request (assoc request :method method)
            preflight-response (when (preflight-required? page-url request)
                                 (fetch-fn (preflight-request page-url request)))
            preflight-ok? (or (nil? preflight-response)
                              (preflight-allowed? page-url request preflight-response send-credentials?))]
        (if-not preflight-ok?
          {:store store
           :request request
           :preflight/response preflight-response
           :response {:status 0
                      :error :cors/preflight-blocked}
           :cache/hit? false
           :allowed? false
           :permission/decision decision}
          (let [cookie (when (and send-credentials? store profile)
                         (cookie-header store profile page-url url))
                referrer-header (:referrer/header request)
                request (cond-> request
                          (not same-origin?) (assoc-in [:headers "origin"] (origin/origin page-url))
                          referrer-header (assoc-in [:headers "referer"] referrer-header)
                          cookie (assoc-in [:headers "cookie"] cookie)
                          true (dissoc :referrer/header))
                response (fetch-fn request)
                allowed? (cors-allowed? page-url url response send-credentials?)
                store (cond-> store
                        (and allowed? send-credentials? store profile)
                        (remember-set-cookie profile url response)

                        (and allowed? cache? store profile (cacheable? request response))
                        (remember-cache-response profile page-url request response send-credentials?))]
            {:store store
             :request request
             :preflight/response preflight-response
             :response (cond-> response (not allowed?) (assoc :error :cors/blocked))
             :cache/hit? false
             :allowed? allowed?
             :permission/decision decision}))))))
