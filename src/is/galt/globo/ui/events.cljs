(ns is.galt.globo.ui.events
  (:require
    [is.galt.globo.ui.presentation.map :as ui.map]
    [applied-science.js-interop :as j]
    [clojure.set :as set]
    [re-frame.core :as rf]))

(defonce mobile-media-query-list
  (.matchMedia js/window "(max-width: 1023px)"))

(defonce setup-mobile-detection
  (.addEventListener
    mobile-media-query-list
    "change"
    (fn [e]
      (println ">>> mobile-media-query-list event" {:e e :matches (.-matches e)})
      (rf/dispatch [::set-system-state :is-mobile? (.-matches e)]))))

(rf/reg-cofx
  ::is-mobile?
  (fn [cofx]
    (assoc cofx :is-mobile? (.-matches mobile-media-query-list))))

(rf/reg-fx
 ::update-map-objects
 (fn [{:keys [op objects]}]
   (case (keyword op)
     :add (doseq [p objects] (ui.map/add-to-layer :custom-layer-data (clj->js p)))
     :remove (doseq [p objects] (ui.map/remove-from-layer :custom-layer-data (clj->js p))))))

(defn round-to
  "Round a number to `decimals` decimal places."
  [n decimals]
  (let [factor (js/Math.pow 10 decimals)]
    (/ (js/Math.round (* n factor)) factor)))

(defn point-id-hash
  "Fixed-length ID using a simple hash of rounded coords."
  ([p]
   (point-id-hash p 6))
  ([{:keys [lat lng]} precision]
   (let [rx (round-to lat precision)
         ry (round-to lng precision)
         h (hash (str rx ":" ry))]
     (str "p_" (js/Math.abs h)))))

(rf/reg-event-fx
 ::click-globe
 (fn [{:keys [db]} [_ point]]
   (let [model-id (get-in db [:place-object :model-id])]
     (when (and (= :in-progress (get-in db [:place-object :status]))
                model-id)
       (let [model-params (get-in db [:placeable-map-objects model-id])
             id-point (merge point {:id (point-id-hash point)} model-params)
             point-action {:op :add :objects [id-point]}]
         {:fx [[:dispatch [::place-objects point-action]]
               [:dispatch [:is.galt.globo.ui.connection.events/send-message {:type :update-object :content point-action}]]
               [:dispatch [::finish-place-object]]]})))))

(rf/reg-event-fx
 ::place-objects
 (fn [{:keys [db]} [_ point-action]]
   (println ">>> :is.galt.globo.ui.events/place-objects" point-action)
   (let [op (:op point-action)
         objects (set (:objects point-action))
         db' (case op
               :add (update-in db [:map-objects] into objects)
               :remove (update-in db [:map-objects] set/difference objects)
               db)]
     (if (get-in db [:models-ready?])
       {:db db'
        :fx [[::update-map-objects point-action]]}
       {:db db'}))))

(rf/reg-event-fx
 ::all-models-ready
 (fn [{:keys [db]} _]
   (println ">>> ::all-models-ready; flushing buffered map-objects onto globe")
   ;; Flip the gate and replay every object that was buffered while
   ;; models were still loading. ::place-objects now sees
   ;; :models-ready? true and emits ::update-map-objects for them.
   ;; The (into #{} ...) inside ::place-objects is idempotent for
   ;; the buffered set (set-union with itself), so db :map-objects
   ;; is unchanged but the globe.fx is fired for the real placement.
   (let [buffered (seq (get-in db [:map-objects]))
         db' (assoc db :models-ready? true)]
     (if buffered
       {:db db'
        :fx [[:dispatch [::place-objects {:op :add :objects buffered}]]]}
       {:db db'}))))

(rf/reg-event-fx
  ::send-chat-message
  (fn [{:keys [db]} [_ text]]
    (let [viewport (some-> @ui.map/globe-instance
                           (j/call :pointOfView)
                           (js->clj :keywordize-keys true)
                           (select-keys [:lat :lng :altitude]))]
      {:fx [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                        {:type :new-message
                         :content {:text text
                                   :viewport viewport}}]]]})))

(rf/reg-event-db
  ::set-hud-open
 (fn [db [_ open?]]
   (println ">>> ::set-hud-open?" open?)
   (assoc-in db [:hud-open?] open?)))

(rf/reg-event-db
 ::start-place-object
 (fn [db [_ o]]
   (println ">>> ::start-place-object" o)
   (assoc-in db [:place-object] {:status :in-progress :model-id o})))

(rf/reg-event-db
 ::cancel-place-object
 (fn [db [_ o]]
   (println ">>> ::cancel-place-object" o)
   (assoc-in db [:place-object :status] :cancelled)))

(rf/reg-event-db
 ::finish-place-object
 (fn [db [_ o]]
   (println ">>> ::finish-place-object" o)
   (assoc-in db [:place-object] nil)))

(rf/reg-event-db
  ::set-system-state
  (fn [db [_ k v]]
    (assoc-in db [:system-state k] v)))

(rf/reg-event-db
  ::set-active-panel
  (fn [db [_ active-panel]]
    (assoc-in db [:ui :active-panel] active-panel)))

(rf/reg-event-db
  ::set-settings-open
  (fn [db [_ open?]]
    (-> db
        (assoc-in [:ui :settings-open?] open?)
        ;; leaving settings cancels any in-flight location pick
        (assoc-in [:ui :picking-location?] (when open? false)))))

(rf/reg-event-fx
  ::set-user-name
  (fn [{:keys [db]} [_ name]]
    (let [user-id (get-in db [:connection :user-id])]
      (println ">>> ::ui.events/set-user-name" {:name name :uid user-id})
      (if user-id
        {:db (assoc-in db [:users user-id :name] name)
         :fx [[:dispatch [:is.galt.globo.ui.connection.events/send-message {:type :update-user
                                                               :content {:id user-id :name name}}]]]}
        {:db db}))))

(rf/reg-event-db
  ::start-pick-location
  (fn [db _]
    (assoc-in db [:ui :picking-location?] true)))

(rf/reg-event-db
  ::cancel-pick-location
  (fn [db _]
    (assoc-in db [:ui :picking-location?] false)))

(rf/reg-event-db
  ::set-user-location
  (fn [db [_ {:keys [lat lng]}]]
    (let [uid (get-in db [:connection :user-id])]
      (-> db
          (assoc-in [:users uid :location] {:lat lat :lng lng})
          (assoc-in [:ui :picking-location?] false)))))
