(ns is.galt.globo.ui.user-figure-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [is.galt.globo.user-figure :as uf]))

(deftest too-close?-test
  (let [other {"u2" {:id "u2" :location {:lat 0.0 :lng 0.0}}}]
    (is (true? (uf/too-close? other "u1" {:lat 0.0 :lng 0.0})))
    (is (false? (uf/too-close? other "u1" {:lat 1.0 :lng 0.0})))))

(deftest build-location-test
  (is (= "user-figure-parts" (get-in (uf/build-location 10 20) [:model :id]))))

(deftest layer-object-test
  (is (= "user-figure-u1"
         (:id (uf/layer-object {:id "u1" :location (uf/build-location 10 20)})))))

(deftest apply-pick-test
  (is (= :too-close
         (:status (uf/apply-pick {"u2" {:id "u2" :location {:lat 0.0 :lng 0.0}}}
                                 "u1" {:lat 0.0 :lng 0.0} nil))))
  (is (= :ok
         (:status (uf/apply-pick {} "u1" {:lat 10.0 :lng 20.0} nil)))))
