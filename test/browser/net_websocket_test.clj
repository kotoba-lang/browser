(ns browser.net-websocket-test
  "Verifies browser.net.websocket against a REAL local WebSocket server --
   no mocks, no external network calls, and (since the JDK ships a real
   WebSocket *client* but no server-side support at all) a hand-rolled,
   minimal RFC6455 server built directly on `java.net.ServerSocket`: it
   performs a real HTTP-Upgrade handshake (including a real
   `Sec-WebSocket-Accept` computation) and echoes back whatever real text
   frame it receives. Just enough RFC6455 to prove real bytes cross the
   wire and come back -- not a general WebSocket library.

   Coverage:
   - a bare `websocket-fn` round trip: connect, send, and receive the REAL
     echoed bytes back from the real server
   - several sends in one connection are received in order
   - a real close, reported back as `:closed? true` by a later drain
   - a real connection failure (closed port) surfacing as an error map,
     never as an uncaught exception
   - `browser.compat.quickjs-execution/apply-capability` wired to a REAL
     `:websocket-fn`: `:websocket/connect` + `:websocket/send` genuinely
     open a socket and send real bytes to the real server, and the
     REAL echoed reply shows up in the host-computed `:websocket/snapshot`
     that a later script evaluation would receive -- proving the wiring
     itself (not just the low-level client) moves real bytes, independent
     of the CLJS-only proof that a real `<script>` tag's callback actually
     fires (see `test-cljs/browser/compat/quickjs_websocket_smoke_test.cljs`)."
  (:require [browser.compat.quickjs :as quickjs]
            [browser.compat.quickjs-binding :as binding]
            [browser.compat.quickjs-execution :as execution]
            [browser.net.websocket :as ws]
            [browser.origin :as origin]
            [browser.profile :as profile]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.io InputStream OutputStream]
           [java.net InetAddress ServerSocket Socket SocketException]
           [java.security MessageDigest]
           [java.util Base64]))

;; ---------------------------------------------------------------------
;; A minimal, hand-rolled RFC6455 echo server (java.net.ServerSocket only
;; -- the JDK has no server-side WebSocket support to build on).
;; ---------------------------------------------------------------------

(def ^:private websocket-magic "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")

(defn- accept-value
  "RFC6455 4.2.2: base64(sha1(key + magic guid))."
  [key]
  (let [digest (MessageDigest/getInstance "SHA-1")
        hashed (.digest digest (.getBytes (str key websocket-magic) "UTF-8"))]
    (.encodeToString (Base64/getEncoder) hashed)))

(defn- read-line-ascii
  "Read one CRLF-terminated ASCII line from `in`, without the CRLF. Returns
   nil at EOF with nothing read."
  [^InputStream in]
  (let [sb (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (cond
          (neg? b) (when (pos? (.length sb)) (.toString sb))
          (= b 13) (do (.read in) (.toString sb)) ; consume the following \n
          :else (do (.append sb (char b)) (recur)))))))

(defn- read-request-headers
  [^InputStream in]
  (read-line-ascii in) ; request line, e.g. "GET /echo HTTP/1.1" -- unused
  (loop [headers {}]
    (let [line (read-line-ascii in)]
      (if (str/blank? line)
        headers
        (let [colon (.indexOf ^String line ":")]
          (recur (if (pos? colon)
                   (assoc headers
                          (str/lower-case (str/trim (subs line 0 colon)))
                          (str/trim (subs line (inc colon))))
                   headers)))))))

(defn- handshake!
  "Perform the real HTTP-Upgrade handshake and return the (still-open)
   input/output streams for framed WebSocket traffic."
  [^Socket socket]
  (let [in (.getInputStream socket)
        out (.getOutputStream socket)
        headers (read-request-headers in)
        accept (accept-value (get headers "sec-websocket-key"))
        response (str "HTTP/1.1 101 Switching Protocols\r\n"
                       "Upgrade: websocket\r\n"
                       "Connection: Upgrade\r\n"
                       "Sec-WebSocket-Accept: " accept "\r\n"
                       "\r\n")]
    (.write out (.getBytes response "US-ASCII"))
    (.flush out)
    {:in in :out out}))

(defn- read-exact!
  [^InputStream in ^bytes buffer]
  (.readNBytes in buffer 0 (alength buffer)))

(defn- read-frame
  "Read one RFC6455 frame from `in` (client frames are always masked).
   Returns {:opcode int :payload bytes} or nil at EOF."
  [^InputStream in]
  (let [b0 (.read in)
        b1 (.read in)]
    (when (and (>= b0 0) (>= b1 0))
      (let [opcode (bit-and b0 0x0F)
            masked? (bit-test b1 7)
            len0 (bit-and b1 0x7F)
            len (cond
                  (= len0 126) (let [buf (byte-array 2)]
                                 (read-exact! in buf)
                                 (bit-or (bit-shift-left (bit-and (aget buf 0) 0xFF) 8)
                                         (bit-and (aget buf 1) 0xFF)))
                  (= len0 127) (let [buf (byte-array 8)]
                                 (read-exact! in buf)
                                 (reduce (fn [acc b] (bit-or (bit-shift-left acc 8) (bit-and b 0xFF)))
                                         0 buf))
                  :else len0)
            mask-key (when masked?
                       (let [buf (byte-array 4)] (read-exact! in buf) buf))
            payload (byte-array len)]
        (read-exact! in payload)
        (when masked?
          (dotimes [i len]
            (aset payload i (unchecked-byte (bit-xor (aget payload i)
                                                       (aget ^bytes mask-key (mod i 4)))))))
        {:opcode opcode :payload payload}))))

(defn- write-frame!
  "Write one unmasked RFC6455 frame (servers never mask) with FIN set."
  [^OutputStream out opcode ^bytes payload]
  (let [len (alength payload)]
    (.write out (bit-or 0x80 (bit-and opcode 0x0F)))
    (cond
      (< len 126) (.write out len)
      (< len 65536) (do (.write out 126)
                         (.write out (bit-and (bit-shift-right len 8) 0xFF))
                         (.write out (bit-and len 0xFF)))
      :else (do (.write out 127)
                (dotimes [i 8]
                  (.write out (bit-and (bit-shift-right len (* 8 (- 7 i))) 0xFF)))))
    (.write out payload)
    (.flush out)))

(defn- serve-connection!
  "Handshake, then loop echoing real text frames until a close frame or EOF."
  [^Socket socket]
  (try
    (let [{:keys [in out]} (handshake! socket)]
      (loop []
        (when-let [{:keys [opcode payload]} (read-frame in)]
          (case opcode
            1 (do (write-frame! out 1 payload) (recur)) ; text -> echo
            8 (write-frame! out 8 (byte-array 0))        ; close -> close reply
            9 (do (write-frame! out 0xA payload) (recur)) ; ping -> pong
            (recur)))))
    (catch Exception _ nil)
    (finally (try (.close socket) (catch Exception _ nil)))))

(defn start-server!
  "Start a real, minimal RFC6455 echo server bound to loopback on an
   OS-assigned port. Returns a handle for `server-url`/`stop-server!`."
  []
  (let [server-socket (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        running? (atom true)
        accept-thread (Thread.
                       ^Runnable
                       (fn []
                         (while @running?
                           (try
                             (let [socket (.accept server-socket)]
                               (.start (Thread. ^Runnable (fn [] (serve-connection! socket)))))
                             (catch SocketException _ nil)
                             (catch Exception _ nil)))))]
    (.setDaemon accept-thread true)
    (.start accept-thread)
    {:server-socket server-socket :running? running?}))

(defn stop-server!
  [{:keys [server-socket running?]}]
  (reset! running? false)
  (.close ^ServerSocket server-socket))

(defn- server-url
  [{:keys [^ServerSocket server-socket]} path]
  (str "ws://127.0.0.1:" (.getLocalPort server-socket) path))

(defn- closed-port-url
  "Mirrors `net_http_test.clj`'s helper of the same purpose: an ephemeral
   port that is bound then immediately freed, so a connection there
   reliably fails with connection-refused instead of flaking."
  []
  (let [port (with-open [probe (ServerSocket. 0)] (.getLocalPort probe))]
    (str "ws://127.0.0.1:" port "/")))

;; ---------------------------------------------------------------------
;; Low-level browser.net.websocket coverage
;; ---------------------------------------------------------------------

(deftest real-websocket-connect-send-and-echo-round-trips-real-bytes
  (let [server (start-server!)]
    (try
      (let [ws-fn (ws/websocket-fn {:connect-timeout-ms 2000})
            connected (ws-fn {:op :connect :url (server-url server "/echo")})]
        (is (:ok? connected))
        (let [handle (:handle connected)
              sent (ws-fn {:op :send :handle handle :data "hello real socket"})]
          (is (:ok? sent))
          (let [drained (ws-fn {:op :drain :handle handle :wait-ms 2000})]
            (is (= ["hello real socket"] (:messages drained)))
            (is (false? (:closed? drained))))))
      (finally (stop-server! server)))))

(defn- drain-until-count!
  "Repeatedly calls `:drain` (each with its own small bounded wait) until
   `expected-count` real messages have genuinely been collected or
   `timeout-ms` has elapsed. `drain-messages!`'s own bounded wait only
   waits out for the FIRST real message to show up in an otherwise-empty
   queue (see that fn's docstring) -- it does not wait for a whole batch
   of rapid sends to all land, so a test asserting on several sends needs
   to poll like this rather than expect one `:drain` call to catch them
   all."
  [ws-fn handle expected-count timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [collected []]
      (let [drained (ws-fn {:op :drain :handle handle :wait-ms 200})
            collected (into collected (:messages drained))]
        (if (or (= expected-count (count collected))
                (> (System/currentTimeMillis) deadline))
          collected
          (recur collected))))))

(deftest real-websocket-multiple-sends-are-echoed-in-order
  (let [server (start-server!)]
    (try
      (let [ws-fn (ws/websocket-fn {:connect-timeout-ms 2000})
            {:keys [handle]} (ws-fn {:op :connect :url (server-url server "/echo")})]
        (doseq [message ["one" "two" "three"]]
          (is (:ok? (ws-fn {:op :send :handle handle :data message}))))
        (is (= ["one" "two" "three"] (drain-until-count! ws-fn handle 3 2000))))
      (finally (stop-server! server)))))

(deftest real-websocket-close-is-reported-by-a-later-drain
  (let [server (start-server!)]
    (try
      (let [ws-fn (ws/websocket-fn {:connect-timeout-ms 2000})
            {:keys [handle]} (ws-fn {:op :connect :url (server-url server "/echo")})
            closed (ws-fn {:op :close :handle handle :code 1000 :reason "done"})]
        (is (:ok? closed))
        (let [drained (ws-fn {:op :drain :handle handle :wait-ms 2000})]
          (is (true? (:closed? drained)))))
      (finally (stop-server! server)))))

(deftest real-websocket-connection-refused-returns-error-not-exception
  (let [ws-fn (ws/websocket-fn {:connect-timeout-ms 2000})
        result (ws-fn {:op :connect :url (closed-port-url)})]
    (is (false? (:ok? result)))
    (is (keyword? (:error result)))))

;; ---------------------------------------------------------------------
;; quickjs-execution wiring: real :websocket/connect + :websocket/send +
;; the host-computed :websocket/snapshot, all against the real server.
;; ---------------------------------------------------------------------

(deftest quickjs-websocket-wiring-moves-real-bytes-through-apply-capability
  (let [server (start-server!)]
    (try
      (let [url (server-url server "/echo")
            profile (-> (profile/new-profile {:id "work"})
                        (profile/grant-permission (origin/origin url) :websocket/connect))
            adapter (quickjs/new-adapter {:origin "https://app.example"
                                          :profile-id "work"})
            received-payload (atom nil)
            state (execution/new-state
                   {:binding (binding/empty-binding adapter)
                    :net-context {:profile profile
                                  :page-url "https://app.example/chat"}
                    :websocket-fn (ws/websocket-fn {:connect-timeout-ms 2000})
                    :engine (fn [request]
                              (reset! received-payload request)
                              {:result :websocket
                               :requests [{:request/id "connect"
                                           :capability :websocket/connect
                                           :websocket/id "websocket-1"
                                           :url url}
                                          {:request/id "send"
                                           :capability :websocket/send
                                           :websocket/id "websocket-1"
                                           :data "hello real socket"}]})})
            state (execution/evaluate! state {:source "const ws = new WebSocket('...')"})]
        ;; Real connect + real send both happened for real via apply-capability.
        (is (= :open (get-in state [:websocket/connections "websocket-1" :ready-state])))
        (is (some? (get-in state [:websocket/handles "websocket-1"])))
        ;; A SECOND evaluate! (standing in for a later <script> tag on the
        ;; same page, exactly the way browser.compat.quickjs-runner threads
        ;; page-lifetime state across real script tags) computes a fresh
        ;; websocket-snapshot BEFORE invoking the engine -- draining the
        ;; real echo reply from the real server into the payload the
        ;; (fake, but request-capturing) engine receives.
        (let [state (execution/evaluate! state {:source "/* second script tag */"})
              snapshot (:websocket/snapshot @received-payload)]
          (is (= ["hello real socket"]
                 (get-in snapshot ["websocket-1" :messages]))
              (str "expected the real echoed reply to show up in the host-computed "
                   "websocket-snapshot passed to the next script's invocation -- got "
                   (pr-str snapshot)))
          (is (false? (get-in snapshot ["websocket-1" :closed?])))
          (is (map? state))))
      (finally (stop-server! server)))))
