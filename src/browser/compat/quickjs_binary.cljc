(ns browser.compat.quickjs-binary
  "QuickJS WASM binary loading contract."
  #?(:clj (:import [java.nio.file Files Path]
                   [java.security MessageDigest])))

(def wasm-magic [0 97 115 109])

(defn wasm-bytes?
  [bytes]
  (let [xs (take 4 (seq bytes))]
    (= wasm-magic (mapv #(bit-and 0xff %) xs))))

#?(:clj
   (defn sha256-hex
     [bytes]
     (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
       (apply str (map #(format "%02x" (bit-and 0xff %)) digest)))))

(defn descriptor
  [{:keys [bytes path sha256] :as opts}]
  (let [size (count bytes)]
    {:quickjs.binary/path path
     :quickjs.binary/sha256 #?(:clj (or sha256 (sha256-hex bytes))
                               :cljs sha256)
     :quickjs.binary/size size
     :quickjs.binary/wasm? (wasm-bytes? bytes)
     :quickjs.binary/bytes bytes
     :quickjs.binary/meta (dissoc opts :bytes :path :sha256)}))

(defn verify-integrity
  [binary expected-sha256]
  (let [actual (:quickjs.binary/sha256 binary)]
    {:quickjs.binary/integrity-ok? (= expected-sha256 actual)
     :quickjs.binary/expected-sha256 expected-sha256
     :quickjs.binary/actual-sha256 actual}))

#?(:clj
   (defn load-wasm-file
     [path]
     (let [p (Path/of (str path) (make-array String 0))
           bytes (Files/readAllBytes p)]
       (descriptor {:path (str path) :bytes bytes}))))

#?(:cljs
   (defn load-url
     ([url]
      (load-url url {}))
     ([url {:keys [signal]}]
      (let [opts #js {}]
        (when signal
          (aset opts "signal" signal))
     (-> (js/fetch url opts)
         (.then #(.arrayBuffer %))
         (.then (fn [buf]
                  (let [bytes (js/Uint8Array. buf)]
                    (descriptor {:path url :bytes bytes})))))))))

(defn attach-binary
  ([binding binary]
   (attach-binary binding binary {}))
  ([binding binary {:keys [expected-sha256]}]
  (if (and (:quickjs.binary/wasm? binary)
           (or (nil? expected-sha256)
               (:quickjs.binary/integrity-ok? (verify-integrity binary expected-sha256))))
    (assoc binding :quickjs/binary (dissoc binary :quickjs.binary/bytes))
    (assoc binding :quickjs/error {:reason (if (:quickjs.binary/wasm? binary)
                                             :quickjs/integrity-check-failed
                                             :quickjs/invalid-wasm-binary)
                                   :path (:quickjs.binary/path binary)
                                   :integrity (when expected-sha256
                                                (verify-integrity binary expected-sha256))}))))
