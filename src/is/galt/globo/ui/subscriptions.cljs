(ns is.galt.globo.ui.subscriptions
  "Re-frame subscriptions for app UI state."
  (:require
    [re-frame.core :as rf]))

(rf/reg-sub
  ::hud-open?
  (fn [db _]
    (get-in db [:hud-open?])))

(rf/reg-sub
  ::place-object
  (fn [db _]
    (get-in db [:place-object])))

(rf/reg-sub
  ::picking-location?
  (fn [db _]
    (get-in db [:ui :picking-location?])))

(rf/reg-sub
  ::map-classes
  :<- [::place-object]
  :<- [::picking-location?]
  (fn [[place-object picking?] _]
    (cond-> []
      (= (:status place-object) :in-progress) (conj :place-object-in-progress)
      picking?                                (conj :picking-location))))

(rf/reg-sub
  ::map-objects
  (fn [db _]
    (vals (get-in db [:map-objects]))))

(rf/reg-sub
  ::placeable-map-objects
  (fn [db _]
    (vals (get-in db [:placeable-map-objects]))))

(rf/reg-sub
  ::is-mobile?
  (fn [db _]
    (get-in db [:system-state :is-mobile?])))

(rf/reg-sub
  ::active-panel
  (fn [db _]
    (get-in db [:ui :active-panel] :users)))

(rf/reg-sub
  ::messages
  (fn [db]
    (get-in db [:messages])))

(rf/reg-sub
  ::settings-open?
  (fn [db _]
    (get-in db [:ui :settings-open?])))

(rf/reg-sub
  ::current-user
  (fn [db _]
    (let [user-id (get-in db [:connection :user-id])]
      (get-in db [:users user-id]))))
