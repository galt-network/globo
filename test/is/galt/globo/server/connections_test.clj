(ns is.galt.globo.server.connections-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.galt.globo.protocols :as protocols]
            [is.galt.globo.server.connections :as connections]))

(deftest round-trip-test
  (let [c (connections/in-memory-connection-store)]
    (is (= {} (protocols/registry c)))
    (protocols/add-connection! c "c1" :channel-1)
    (protocols/add-connection! c "c2" :channel-2)
    (is (= {"c1" :channel-1 "c2" :channel-2} (protocols/registry c)))
    (testing "channels-for returns channels for known ids, ignoring unknown"
      (is (= [:channel-1 :channel-2] (protocols/channels-for c ["c1" "c2"])))
      (is (= [:channel-2] (protocols/channels-for c ["c2" "ghost"])))
      (is (empty? (protocols/channels-for c []))))
    (protocols/remove-connection! c "c1")
    (is (= {"c2" :channel-2} (protocols/registry c)))))

(deftest constructor-test
  (testing "wraps an existing atom (back-compat)"
    (let [existing (atom {"c1" :channel-1})
          c (connections/in-memory-connection-store existing)]
      (protocols/add-connection! c "c2" :channel-2)
      (is (= {"c1" :channel-1 "c2" :channel-2} @existing)))))
