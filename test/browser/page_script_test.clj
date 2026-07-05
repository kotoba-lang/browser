(ns browser.page-script-test
  (:require [browser.core :as browser]
            [browser.page-script :as page-script]
            [clojure.test :refer [deftest is]]))

(deftest page-script-discovers-inline-classic-and-module-scripts
  (let [page (browser/load-html
              {:url "kotoba://scripts"
               :html "<main><script>globalThis.a = 1;</script><script type=\"module\">import './m.js';</script><script src=\"./external.js\"></script></main>"})
        scripts (page-script/executable-scripts page)]
    (is (= [:classic :module :classic]
           (mapv :script/type scripts)))
    (is (= ["globalThis.a = 1;" "import './m.js';" nil]
           (mapv :script/source scripts)))
    (is (= ["kotoba://scripts" "kotoba://scripts" "./external.js"]
           (mapv :script/url scripts)))))

(deftest page-script-resolves-src-against-page-url
  (is (= "https://example.com/app.js"
         (page-script/resolve-src "https://example.com/docs/index.html" "/app.js")))
  (is (= "https://example.com/docs/app.js"
         (page-script/resolve-src "https://example.com/docs/index.html" "./app.js")))
  (is (= "https://example.com/app.js?v=1#boot"
         (page-script/resolve-src "https://example.com/docs/index.html" "../app.js?v=1#boot")))
  (is (= "https://example.com/app.js?v=2#boot"
         (page-script/resolve-src "https://example.com/docs/index.html" "/assets/../app.js?v=2#boot")))
  (is (= "https://cdn.example/app.js"
         (page-script/resolve-src "https://example.com/docs/index.html" "//cdn.example/app.js")))
  (is (= "https://example.com/docs/index.html?page=2"
         (page-script/resolve-src "https://example.com/docs/index.html?old=1#top" "?page=2")))
  (is (= "https://example.com/docs/index.html?page=2#results"
         (page-script/resolve-src "https://example.com/docs/index.html?old=1#top" "?page=2#results")))
  (is (= "https://example.com/docs/index.html#details"
         (page-script/resolve-src "https://example.com/docs/index.html#top" "#details")))
  (is (= "https://example.com/docs/index.html?old=1#details"
         (page-script/resolve-src "https://example.com/docs/index.html?old=1#top" "#details")))
  (is (= "https://cdn.example/app.js"
         (page-script/resolve-src "https://example.com/docs/index.html" "https://cdn.example/app.js")))
  (is (= "https://cdn.example/app.js?v=1#run"
         (page-script/resolve-src "https://example.com/docs/index.html" "https://cdn.example/assets/../app.js?v=1#run"))))

(deftest resolve-src-honors-dot-slash-disambiguation-for-colon-first-segment
  ;; RFC 3986 4.2: a relative-path reference whose first path segment
  ;; contains a colon (e.g. "video:120/clip.js") is indistinguishable from
  ;; an absolute URI with scheme "video" -- a page author must prefix it
  ;; with "./" to force relative-path resolution. Without the "./" prefix,
  ;; this is genuinely, unavoidably ambiguous (real browsers exhibit the
  ;; same ambiguity), so the bare form is correctly still treated as
  ;; absolute here.
  (is (= "video:120/clip.js"
         (page-script/resolve-src "https://example.com/dir/page.html" "video:120/clip.js")))
  ;; With the "./" prefix, the same colon-bearing segment must resolve
  ;; relative to the page's own directory, exactly like any other
  ;; dot-relative src.
  (is (= "https://example.com/dir/video:120/clip.js"
         (page-script/resolve-src "https://example.com/dir/page.html" "./video:120/clip.js"))))
