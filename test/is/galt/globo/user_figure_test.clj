(ns is.galt.globo.user-figure-test
  (:require [clojure.test :refer [deftest is testing]]
            [is.galt.globo.user-figure :as uf]))

(deftest too-close?-test
  (let [other {"u2" {:id "u2" :location {:lat 0.0 :lng 0.0}}}]
    (testing "same spot as another user is too close"
      (is (true? (uf/too-close? other "u1" {:lat 0.0 :lng 0.0}))))
    (testing "self at same spot is not too close"
      (is (false? (uf/too-close? {"u1" {:id "u1" :location {:lat 0.0 :lng 0.0}}}
                                 "u1" {:lat 0.0 :lng 0.0}))))
    (testing "farther than neighbor spacing is allowed"
      (is (false? (uf/too-close? other "u1" {:lat 1.0 :lng 0.0}))))))

(deftest build-location-test
  (testing "nests default model on coords"
    (is (= {:lat 10.0 :lng 20.0
            :model {:id "user-figure-parts" :scale 0.15 :color :blue}}
           (uf/build-location 10.0 20.0))))
  (testing "keeps an existing color on relocate"
    (is (= :red
           (get-in (uf/build-location 1.0 2.0 {:color :red}) [:model :color])))))

(deftest layer-object-test
  (testing "user with a model becomes a layer object"
    (is (= {:id "user-figure-u1"
            :lat 10.0 :lng 20.0
            :model-id "user-figure-parts"
            :scale 0.15
            :color :blue
            :user-id "u1"}
           (uf/layer-object {:id "u1"
                             :location (uf/build-location 10.0 20.0)}))))
  (testing "coords-only location yields no layer object"
    (is (nil? (uf/layer-object {:id "u1" :location {:lat 1 :lng 2}})))))

(deftest user-figure-model?-test
  (is (true? (uf/user-figure-model? "user-figure-parts")))
  (is (true? (uf/user-figure-model? "user-figure-simple")))
  (is (false? (uf/user-figure-model? "ancap-flag"))))

(deftest has-figure?-test
  (is (true? (uf/has-figure? {:location (uf/build-location 1 2)})))
  (is (false? (uf/has-figure? {:location {:lat 1 :lng 2}})))
  (is (false? (uf/has-figure? {}))))

(deftest apply-pick-test
  (testing "too close yields no location"
    (is (= {:status :too-close}
           (uf/apply-pick {"u2" {:id "u2" :location {:lat 0.0 :lng 0.0}}}
                          "u1" {:lat 0.0 :lng 0.0} nil))))
  (testing "ok pick builds location and keeps color"
    (is (= {:status :ok
            :location {:lat 10.0 :lng 20.0
                       :model {:id "user-figure-parts" :scale 0.15 :color :red}}}
           (uf/apply-pick {} "u1" {:lat 10.0 :lng 20.0}
                          {:lat 1 :lng 2 :model {:color :red}})))))

(deftest sync-actions-test
  (testing "color change removes then adds the figure"
    (let [blue (uf/layer-object {:id "u1" :location (uf/build-location 10 20)})
          red (uf/layer-object {:id "u1" :location (uf/build-location 10 20 {:color :red})})]
      (is (= {:remove [blue] :add [red]}
             (uf/sync-actions [blue] [red])))))
  (testing "unchanged figure is a no-op"
    (let [obj (uf/layer-object {:id "u1" :location (uf/build-location 10 20)})]
      (is (= {:remove [] :add []}
             (uf/sync-actions [obj] [obj])))))
  (testing "layer-objects keeps only users with a model"
    (is (= 1 (count (uf/layer-objects
                     {"u1" {:id "u1" :location (uf/build-location 1 2)}
                      "u2" {:id "u2" :location {:lat 3 :lng 4}}}))))))
