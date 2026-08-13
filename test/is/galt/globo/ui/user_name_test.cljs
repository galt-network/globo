(ns is.galt.globo.ui.user-name-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [is.galt.globo.ui.user-name :as user-name]))

(deftest clamp-name-test
  (testing "truncates names longer than the limit"
    (is (= (apply str (repeat 42 "x"))
           (user-name/clamp-name 42 (apply str (repeat 43 "x"))))))
  (testing "short names pass through"
    (is (= "Alice" (user-name/clamp-name 42 "Alice"))))
  (testing "nil passes through"
    (is (nil? (user-name/clamp-name 42 nil)))))

(deftest name-unchanged?-test
  (testing "equal names are unchanged"
    (is (true? (user-name/name-unchanged? "Me" "Me")))
    (is (true? (user-name/name-unchanged? nil "")))
    (is (true? (user-name/name-unchanged? "" nil))))
  (testing "different names are changed"
    (is (false? (user-name/name-unchanged? "Me" "You")))))

(deftest save-error-from-response-test
  (testing "409 body yields structured error"
    (is (= {:error "Name must be at most 42 characters."
            :details {:max 42 :actual 43}}
           (user-name/save-error-from-response
            {:ok? false :status 409
             :body {:status "error"
                    :error "Name must be at most 42 characters."
                    :details {:max 42 :actual 43}}}))))
  (testing "success response yields nil"
    (is (nil? (user-name/save-error-from-response
               {:ok? true :status 200 :body {:status "sent"}}))))
  (testing "network problem yields nil"
    (is (nil? (user-name/save-error-from-response
               {:ok? false :problem :body :problem-message "failed"})))))
