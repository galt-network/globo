(ns is.galt.globo.server.hexholds-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.galt.globo.protocols :as protocols]
            [is.galt.globo.server.hexholds :as hexholds]))

(defn make-store
  ([] (hexholds/in-memory-hexhold-store))
  ([land-cells] (hexholds/in-memory-hexhold-store land-cells)))

(deftest paint-hexhold-round-trip-test
  (let [s (make-store)]
    (testing "paint returns the painted entry"
      (is (= {:id "abc" :color :red}
             (protocols/paint-hexhold! s "abc" :red))))
    (testing "overwrite with a new color"
      (is (= {:id "abc" :color :blue}
             (protocols/paint-hexhold! s "abc" :blue))))
    (testing "string colors are keywordized"
      (is (= {:id "xyz" :color :green}
             (protocols/paint-hexhold! s "xyz" "green"))))
    (testing "nil clears the cell"
      (is (= {:id "abc" :color nil}
             (protocols/paint-hexhold! s "abc" nil)))
      (is (nil? (get (protocols/hexhold-colors s) "abc"))))
    (testing "clearing a never-painted cell is a no-op"
      (is (= {:id "nope" :color nil}
             (protocols/paint-hexhold! s "nope" nil))))))

(deftest hexhold-colors-test
  (let [s (make-store)]
    (protocols/paint-hexhold! s "a" :red)
    (protocols/paint-hexhold! s "b" :purple)
    (is (= {"a" :red "b" :purple} (protocols/hexhold-colors s)))
    (testing "empty store returns empty map"
      (is (= {} (protocols/hexhold-colors (make-store)))))))

(deftest query-hexholds-land-filter-test
  (let [s (make-store #{"a" "b" "c"})]
    (protocols/paint-hexhold! s "b" :yellow)
    (testing "ocean cells are filtered out"
      (is (= [{:id "a" :color nil} {:id "b" :color :yellow} {:id "c" :color nil}]
             (protocols/query-hexholds s ["a" "b" "c" "ocean-cell"]))))
    (testing "requested cells outside the land index return nothing"
      (is (= [] (protocols/query-hexholds s ["ocean-cell" "sea"]))))))

(deftest query-hexholds-all-land-test
  (testing "nil land index treats every cell as land"
    (let [s (make-store)]
      (protocols/paint-hexhold! s "x" :red)
      (is (= [{:id "x" :color :red} {:id "y" :color nil}]
             (protocols/query-hexholds s ["x" "y"]))))))

(deftest load-land-index-test
  (testing "missing resource returns nil (all-land fallback)"
    (is (nil? (hexholds/load-land-index "hexholds/does-not-exist.txt")))))

(deftest store-created-via-create-globo-test
  (testing "create-globo wires a default in-memory store"
    (let [g (is.galt.globo.server/create-globo)
          s (:hexholds g)]
      (is (satisfies? protocols/HexholdStore s))
      (protocols/paint-hexhold! s "z" :blue)
      (is (= {"z" :blue} (protocols/hexhold-colors s))))))
