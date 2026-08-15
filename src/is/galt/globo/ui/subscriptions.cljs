(ns is.galt.globo.ui.subscriptions
  "Re-frame subscriptions for app UI state."
  (:require
    [is.galt.globo.ui.hexholds :as hexholds]
    [is.galt.globo.user-figure :as uf]
    [re-frame.core :as rf]))

(rf/reg-sub
 ::hud-open?
 (fn [db _]
   (get-in db [:hud-open?])))

(rf/reg-sub
 ::mouse-action
 (fn [db _]
   (get-in db [:mouse-action])))

(rf/reg-sub
 ::favorites
 (fn [db _]
   (get-in db [:favorites])))

(rf/reg-sub
 ::max-favorite-places
 (fn [db _]
   (get-in db [:config :max-favorite-places])))

(rf/reg-sub
 ::assets-base-url
 (fn [db _]
   (get-in db [:config :assets-base-url])))

(rf/reg-sub
 ::max-user-name-length
 (fn [db _]
   (get-in db [:config :max-user-name-length] 42)))

(rf/reg-sub
 ::user-name-save-error
 (fn [db _]
   (get-in db [:ui :user-name-save-error])))

(rf/reg-sub
 ::user-name-draft
 (fn [db _]
   (get-in db [:ui :user-name-draft])))

(rf/reg-sub
 ::map-classes
 :<- [::mouse-action]
 (fn [action _]
   (cond-> []
     (= :place-object (:type action)) (conj :place-object-in-progress)
     (= :pick-user-location (:type action)) (conj :picking-location)
     (= :set-favorite (:type action)) (conj :setting-favorite))))

(rf/reg-sub
 ::map-objects
 (fn [db _]
   (get-in db [:map-objects])))

(rf/reg-sub
 ::placeable-map-objects
 (fn [db _]
    (remove #(uf/user-figure-model? (:model-id %))
            (vals (get-in db [:placeable-map-objects])))))

(rf/reg-sub
 ::is-mobile?
 (fn [db _]
   (get-in db [:system-state :is-mobile?])))

(rf/reg-sub
 ::active-panel
 (fn [db _]
   (get-in db [:ui :active-panel] :users)))

(rf/reg-sub
 ::active-view
 (fn [db _]
   (get-in db [:ui :active-view] :user-communication)))

(rf/reg-sub
 ::messages
 (fn [db _]
   (get-in db [:messages])))

(rf/reg-sub
 ::current-user
 (fn [db _]
   (let [user-id (get-in db [:connection :user-id])]
     (get-in db [:users user-id]))))

(rf/reg-sub
 ::rings
 (fn [db _]
   (vals (get-in db [:rings]))))

(rf/reg-sub
 ::system-notifications
 (fn [db _]
   (get-in db [:system-notifications])))

(rf/reg-sub
 ::hexholds-visible
 (fn [db _]
   (get-in db [:hexholds :visible])))

(rf/reg-sub
 ::hexholds-hover-id
 (fn [db _]
   (get-in db [:hexholds :hover-id])))

(rf/reg-sub
 ::hexholds-colors
 (fn [db _]
   (get-in db [:hexholds :colors])))

(rf/reg-sub
 ::hexholds-selected-id
 (fn [db _]
   (get-in db [:hexholds :selected-id])))

(rf/reg-sub
 ::hexholds-info
 (fn [db _]
   (get-in db [:hexholds :info])))

(rf/reg-sub
 ::my-hexholds
 (fn [db _]
   (hexholds/my-hexholds (get-in db [:hexholds :visible])
                         (get-in db [:connection :user-id]))))

(rf/reg-sub
 ::hexholds-messages
 (fn [db _]
   (get-in db [:hexholds :messages
               (get-in db [:hexholds :selected-id])]
           [])))

(rf/reg-sub
 ::hexholds-messages-map
 (fn [db _]
   (get-in db [:hexholds :messages] {})))

(rf/reg-sub
 ::hexholds-selected-entry
 (fn [db _]
   (let [selected-id (get-in db [:hexholds :selected-id])]
     (first (filter #(= selected-id (:id %))
                    (get-in db [:hexholds :visible]))))))

(rf/reg-sub
 ::natural-earth
 (fn [db _]
   (:natural-earth db)))
