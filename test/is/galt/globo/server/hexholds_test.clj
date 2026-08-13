(ns is.galt.globo.server.hexholds-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.galt.globo.protocols :as protocols]
            [is.galt.globo.server.hexholds :as hexholds]))

(defn make-store
  ([] (hexholds/in-memory-hexhold-store))
  ([land-cells] (hexholds/in-memory-hexhold-store land-cells)))

(deftest paint-hexhold-round-trip-test
  (let [s (make-store)]
    (testing "paint returns the painted entry with owner"
      (is (= {:id "abc" :color :red :owner-id "u1"}
             (protocols/paint-hexhold! s "abc" :red "u1"))))
    (testing "owner overwrite with a new color"
      (is (= {:id "abc" :color :blue :owner-id "u1"}
             (protocols/paint-hexhold! s "abc" :blue "u1"))))
    (testing "string colors are keywordized"
      (is (= {:id "xyz" :color :green :owner-id "u2"}
             (protocols/paint-hexhold! s "xyz" "green" "u2"))))
    (testing "owner clears the cell and releases ownership"
      (is (= {:id "abc" :color nil :owner-id nil}
             (protocols/paint-hexhold! s "abc" nil "u1")))
      (is (nil? (get (protocols/hexhold-colors s) "abc"))))
    (testing "clearing a never-painted cell is a no-op"
      (is (= {:id "nope" :color nil :owner-id nil}
             (protocols/paint-hexhold! s "nope" nil "u9"))))))

(deftest paint-hexhold-ownership-test
  (let [s (make-store)]
    (protocols/paint-hexhold! s "a" :red "u1")
    (testing "a different user cannot repaint a claimed cell"
      (is (nil? (protocols/paint-hexhold! s "a" :blue "u2"))))
    (testing "a different user cannot clear a claimed cell"
      (is (nil? (protocols/paint-hexhold! s "a" nil "u2"))))
    (testing "the claim survives rejected attempts"
      (is (= [{:id "a" :color :red :owner-id "u1"}]
             (protocols/query-hexholds s ["a"]))))
    (testing "abandon releases the claim and another user can claim"
      (protocols/paint-hexhold! s "a" nil "u1")
      (is (= {:id "a" :color :green :owner-id "u2"}
             (protocols/paint-hexhold! s "a" :green "u2"))))
    (testing "the new owner can repaint"
      (is (= {:id "a" :color :purple :owner-id "u2"}
             (protocols/paint-hexhold! s "a" :purple "u2"))))))

(deftest hexhold-colors-test
  (let [s (make-store)]
    (protocols/paint-hexhold! s "a" :red "u1")
    (protocols/paint-hexhold! s "b" :purple "u1")
    (is (= {"a" :red "b" :purple} (protocols/hexhold-colors s)))
    (testing "empty store returns empty map"
      (is (= {} (protocols/hexhold-colors (make-store)))))))

(deftest query-hexholds-land-filter-test
  (let [s (make-store #{"a" "b" "c"})]
    (protocols/paint-hexhold! s "b" :yellow "u7")
    (testing "ocean cells are filtered out; owners are included"
      (is (= [{:id "a" :color nil :owner-id nil}
              {:id "b" :color :yellow :owner-id "u7"}
              {:id "c" :color nil :owner-id nil}]
             (protocols/query-hexholds s ["a" "b" "c" "ocean-cell"]))))
    (testing "requested cells outside the land index return nothing"
      (is (= [] (protocols/query-hexholds s ["ocean-cell" "sea"]))))))

(deftest query-hexholds-all-land-test
  (testing "nil land index treats every cell as land"
    (let [s (make-store)]
      (protocols/paint-hexhold! s "x" :red "u1")
      (is (= [{:id "x" :color :red :owner-id "u1"} {:id "y" :color nil :owner-id nil}]
             (protocols/query-hexholds s ["x" "y"]))))))

(deftest hexhold-messages-test
  (let [s (make-store)
        m (protocols/add-hexhold-message! s "a" {:id "u1" :name "Alice"} "hello")]
    (testing "adding a message returns it with id, author, content, sent-at"
      (is (= {:id (:id m) :author {:id "u1" :name "Alice"} :content "hello"}
             (select-keys m [:id :author :content])))
      (is (string? (:sent-at m)))
      (is (seq (:id m))))
    (testing "messages accumulate per hex, ids are unique"
      (protocols/add-hexhold-message! s "a" {:id "u2" :name "Bob"} "hi")
      (protocols/add-hexhold-message! s "b" {:id "u2" :name "Bob"} "other")
      (is (= 2 (count (protocols/hexhold-messages s "a"))))
      (is (= 1 (count (protocols/hexhold-messages s "b"))))
      (is (= 2 (count (distinct (map :id (protocols/hexhold-messages s "a")))))))
    (testing "unknown hex returns an empty vector"
      (is (= [] (protocols/hexhold-messages s "nope"))))))

(deftest legacy-state-shape-test
  (testing "an atom with the old {:colors ...} shape still works"
    (let [s (hexholds/in-memory-hexhold-store nil (atom {:colors {"a" :red}}))]
      (is (= {:id "a" :color :blue :owner-id "u1"}
             (protocols/paint-hexhold! s "a" :blue "u1")))
      (is (= [{:id "a" :color :blue :owner-id "u1"}]
             (protocols/query-hexholds s ["a"])))
      (is (= [] (protocols/hexhold-messages s "a"))))))

(deftest load-land-index-test
  (testing "missing resource returns nil (all-land fallback)"
    (is (nil? (hexholds/load-land-index "hexholds/does-not-exist.txt")))))

(deftest store-created-via-create-globo-test
  (testing "create-globo wires a default in-memory store"
    (let [g (is.galt.globo.server/create-globo)
          s (:hexholds g)]
      (is (satisfies? protocols/HexholdStore s))
      (protocols/paint-hexhold! s "z" :blue "u1")
      (is (= {"z" :blue} (protocols/hexhold-colors s))))))
