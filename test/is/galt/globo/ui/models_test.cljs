(ns is.galt.globo.ui.models-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [is.galt.globo.ui.models :as models]))

(deftest flush-layer-action-test
  (testing "replay replaces the custom layer so fallback spheres are dropped"
    (is (= {:op :replace :objects #{{:id "p1"}}}
           (models/flush-layer-action #{{:id "p1"}}))))
  (testing "nothing to flush"
    (is (nil? (models/flush-layer-action #{})))
    (is (nil? (models/flush-layer-action nil)))))

(deftest placeables-fx-test
  (testing "mount-time empty placeables do not open the models-ready gate"
    (is (nil? (models/placeables-fx "/assets" [] :mount))))
  (testing "server empty list opens the gate so placement is not stuck"
    (is (= [[:dispatch [:is.galt.globo.ui.events/all-models-ready]]]
           (models/placeables-fx "/assets" [] :server))))
  (testing "server placeables preload instead of opening the gate immediately"
    (is (= [[:is.galt.globo.ui.events/preload-models
             {:assets-base-url "/assets"
              :placeables [{:model-id :carrot}]}]]
           (models/placeables-fx "/assets" [{:model-id :carrot}] :server)))))
