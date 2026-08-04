(ns is.galt.globo.server.sse-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [is.galt.globo.server.sse :as sse]))

(deftest sse-event-test
  (testing "data-only event"
    (is (= "data: {\"type\":\"connected\"}\n\n"
           (sse/sse-event {:type :connected}))))
  (testing "event with a name"
    (is (= "event: greeting\ndata: {\"hi\":\"there\"}\n\n"
           (sse/sse-event "greeting" {:hi "there"})))))

(deftest send!-test
  (testing "sends to every channel and returns true"
    (let [sent (atom [])]
      (with-redefs [org.httpkit.server/send!
                    (fn [ch data _opts] (swap! sent conj [ch data]))]
        (is (true? (sse/send! [:a :b] {:x 1})))
        (is (= [[:a "data: {\"x\":1}\n\n"]
                [:b "data: {\"x\":1}\n\n"]]
               @sent)))))
  (testing "returns false when there are no channels"
    (with-redefs [org.httpkit.server/send! (fn [_ _ _] (throw (AssertionError. "should not be called")))]
      (is (false? (sse/send! [] {:x 1}))))))
