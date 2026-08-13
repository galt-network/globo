(ns is.galt.globo.ui.hud-views-test
  "Tests for the HUD radio view state machine (user-communication /
   settings / hexholds)."
  (:require
   [cljs.test :refer-macros [deftest is testing]]
   [is.galt.globo.ui.hud-views :as views]))

(def base-db
  {:ui {:active-panel :users}
   :hexholds {:colors {:h1 :red}
              :visible [{:id "h1" :color :red}]
              :hover-id "h1"
              :selected-id "h1"
              :info nil
              :messages {}}})

(def hexholds-db
  (assoc-in base-db [:ui :active-view] :hexholds))

(def settings-db
  (assoc-in base-db [:ui :active-view] :settings))

(def sync-fx :is.galt.globo.ui.events/sync-hexholds)
(def hover-fx :is.galt.globo.ui.events/update-hexhold-hover-tint)
(def refresh-fx :is.galt.globo.ui.events/refresh-hexholds-viewport)
(def info-fx :is.galt.globo.ui.events/update-hexholds-info)
(def toast-fx :is.galt.globo.ui.connection.events/system-notification)

(defn fx-ids
  "Direct effect ids of a result's fx vector."
  [result]
  (mapv first (:fx result)))

(defn event-ids
  "Ids of events dispatched via :dispatch fx."
  [result]
  (->> (:fx result)
       (filter (comp #{:dispatch} first))
       (map (comp first second))
       vec))

(deftest active-view-test
  (testing "defaults to :user-communication when unset"
    (is (= :user-communication (views/active-view {:ui {}}))))
  (testing "reads the stored view"
    (is (= :settings (views/active-view settings-db)))))

(deftest hexholds-view-test
  (testing "false for the default view"
    (is (false? (views/hexholds-view? base-db))))
  (testing "true when the hexholds view is active"
    (is (true? (views/hexholds-view? hexholds-db)))))

(deftest apply-view-noop-test
  (testing "already-active view is a no-op (pure radio)"
    (is (nil? (views/apply-view base-db :user-communication {})))
    (is (nil? (views/apply-view settings-db :settings {})))
    (is (nil? (views/apply-view hexholds-db :hexholds {:altitude 0.1})))))

(deftest apply-view-settings-test
  (testing "switching to settings activates it and touches nothing else"
    (let [result (views/apply-view base-db :settings {})]
      (is (= :settings (get-in result [:db :ui :active-view])))
      (is (= :users (get-in result [:db :ui :active-panel])))
      (is (not (contains? result :fx)))
      (is (= {:id "h1" :color :red} (get-in result [:db :hexholds :visible 0])))))
  (testing "switching back to user-communication from settings is clean"
    (let [result (views/apply-view settings-db :user-communication {})]
      (is (= :user-communication (get-in result [:db :ui :active-view])))
      (is (not (contains? result :fx))))))

(deftest apply-view-enter-hexholds-test
  (testing "within LOD: activates the view and refreshes the viewport"
    (let [result (views/apply-view base-db :hexholds {:altitude 0.1})]
      (is (= :hexholds (get-in result [:db :ui :active-view])))
      (is (= [refresh-fx info-fx] (event-ids result)))
      (is (= [{:id "h1" :color :red}] (get-in result [:db :hexholds :visible])))))
  (testing "above LOD: activates the view and shows the zoom-in toast instead"
    (let [result (views/apply-view base-db :hexholds {:altitude 1.5})]
      (is (= :hexholds (get-in result [:db :ui :active-view])))
      (is (= [toast-fx] (event-ids result)))
      (is (= "Zoom in to see hexholds"
             (get-in result [:fx 0 1 1 :content :message]))))))

(deftest apply-view-leave-hexholds-test
  (testing "leaving hexholds clears grid data and resets the sub-tab"
    (doseq [view [:settings :user-communication]]
      (let [result (views/apply-view hexholds-db view {})]
        (testing (str "target " view)
          (is (= view (get-in result [:db :ui :active-view])))
          (is (= [] (get-in result [:db :hexholds :visible])))
          (is (nil? (get-in result [:db :hexholds :hover-id])))
          (is (nil? (get-in result [:db :hexholds :selected-id])))
          (is (= :users (get-in result [:db :ui :active-panel])))
          (is (= [sync-fx hover-fx] (fx-ids result)))
          (is (= {:visible [] :colors {:h1 :red}} (get-in result [:fx 0 1])))
          (is (= {:from-id "h1" :to-id nil :colors {:h1 :red}}
                 (get-in result [:fx 1 1])))))))
  (testing "colors survive the switch for the sync fx"
    (let [result (views/apply-view hexholds-db :settings {})]
      (is (= {:h1 :red} (get-in result [:fx 0 1 :colors]))))))
