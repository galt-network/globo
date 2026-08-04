(ns is.galt.globo.ui.events
  "Re-frame events for the globo UI: mouse actions, map objects,
  favorites, chat messages, rings, and mobile detection."
  (:require
   [applied-science.js-interop :as j]
   [clojure.set :as set]
   [is.galt.globo.ui.presentation.map :as ui.map]
   [re-frame.core :as rf]))

(defonce mobile-media-query-list
  (.matchMedia js/window "(max-width: 1023px)"))

(defonce mobile-listener-setup
  (delay
    (.addEventListener
     mobile-media-query-list
     "change"
     (fn [e]
       (rf/dispatch [::set-system-state :is-mobile? (.-matches e)])))))

(defn setup-mobile-detection!
  "Register the mobile media-query listener exactly once."
  []
  @mobile-listener-setup)

(rf/reg-cofx
 ::is-mobile?
 (fn [cofx]
   (assoc cofx :is-mobile? (.-matches mobile-media-query-list))))

(rf/reg-cofx
 ::globe-viewpoint
 (fn [cofx]
   (assoc cofx :globe-viewpoint
          (some-> @ui.map/globe-instance
                  (j/call :pointOfView)
                  (js->clj :keywordize-keys true)
                  (select-keys [:lat :lng :altitude])))))

(rf/reg-fx
 ::update-map-objects
 (fn [{:keys [op objects]}]
   (case (keyword op)
     :add (doseq [p objects] (ui.map/add-to-layer :custom-layer-data (clj->js p)))
     :remove (doseq [p objects] (ui.map/remove-from-layer :custom-layer-data (clj->js p))))))

(rf/reg-fx
 ::sync-rings
 (fn [db-rings]
   (ui.map/sync-rings-from-db! db-rings)))

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
   (let [action (get-in db [:mouse-action])]
     (case (:type action)
       :place-object
       (let [model-id (:model-id action)
             model-params (get-in db [:placeable-map-objects model-id])
             id-point (merge point {:id (point-id-hash point)} model-params)
             point-action {:op :add :objects [id-point]}]
         {:db (assoc-in db [:mouse-action] nil)
          :fx [[:dispatch [::place-objects point-action]]
               [:dispatch [:is.galt.globo.ui.connection.events/send-message
                           {:type :update-object :content point-action}]]]})

       :pick-user-location
       (let [uid (get-in db [:connection :user-id])]
         {:db (-> db
                  (assoc-in [:users uid :location] point)
                  (assoc-in [:mouse-action] nil))
          :fx [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                           {:type :update-user
                            :content {:id uid :location point}}]]]})

       :set-favorite
       (let [index (:index action)
             existing (get-in db [:favorites index] {:label "" :lat nil :lng nil})
             partial {:lat (:lat point) :lng (:lng point)}
             fav' (merge existing partial)]
         {:db (-> db
                  (assoc-in [:favorites index] fav')
                  (assoc-in [:mouse-action] nil))
          :fx [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                           {:type :update-favorite
                            :content {:index index :partial partial}}]]]})

       {:db db}))))

(rf/reg-event-fx
 ::place-objects
 (fn [{:keys [db]} [_ point-action]]
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
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [globe-viewpoint]} [_ text]]
   {:fx [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                     {:type :new-message
                      :content {:text text
                                :viewport globe-viewpoint}}]]]}))

(rf/reg-event-db
 ::set-hud-open
 (fn [db [_ open?]]
   (assoc-in db [:hud-open?] open?)))

(rf/reg-event-db
 ::set-mouse-action
 (fn [db [_ action]]
   (assoc-in db [:mouse-action] action)))

(rf/reg-event-db
 ::clear-mouse-action
 (fn [db _]
   (assoc-in db [:mouse-action] nil)))

(rf/reg-fx
 ::focus-globe
 (fn [coords]
   (when-let [g @ui.map/globe-instance]
     (j/call g :pointOfView
             (clj->js (merge coords {:altitude 1.5}))))))

(rf/reg-event-fx
 ::go-to-favorite
 (fn [{:keys [db]} [_ index]]
   (let [fav (get-in db [:favorites index])
         loc (when (and (:lat fav) (:lng fav))
               (select-keys fav [:lat :lng]))]
     (when loc
       {:fx [[::focus-globe loc]
             [:dispatch [::add-ring
                         (merge loc
                                {:color "#ffcc00"
                                 :maxR 3
                                 :propagationSpeed 2
                                 :repeatPeriod 800
                                 :duration 3500})]]]}))))

(rf/reg-fx
 ::ring-timer
 (fn [{:keys [id duration]}]
   (when (and duration (pos? duration))
     (swap! ui.map/ring-timers assoc id
            (js/setTimeout
             (fn [] (rf/dispatch [::remove-ring id]))
             duration)))))

(rf/reg-fx
 ::clear-ring-timer
 (fn [ring-id]
   (when-let [timer (get @ui.map/ring-timers ring-id)]
     (js/clearTimeout timer)
     (swap! ui.map/ring-timers dissoc ring-id))))

(rf/reg-event-fx
 ::add-ring
 (fn [{:keys [db]} [_ {:keys [id] :as ring-data}]]
   (let [ring-id (or id (str (random-uuid)))
         db' (assoc-in db [:rings ring-id] (assoc ring-data :id ring-id))]
     {:db db'
      :fx (cond-> [[::sync-rings (:rings db')]]
            (and (:duration ring-data) (pos? (:duration ring-data)))
            (conj [::ring-timer {:id ring-id
                                 :duration (:duration ring-data)}]))})))

(rf/reg-event-fx
 ::remove-ring
 (fn [{:keys [db]} [_ ring-id]]
   (let [db' (update db :rings dissoc ring-id)]
     {:db db'
      :fx [[::clear-ring-timer ring-id]
           [::sync-rings (:rings db')]]})))

(rf/reg-event-fx
 ::rename-favorite
 (fn [{:keys [db]} [_ index new-name]]
   (let [fav' (assoc (get-in db [:favorites index]) :label new-name)]
     {:db (assoc-in db [:favorites index] fav')
      :fx [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                       {:type :update-favorite
                        :content {:index index :partial {:label new-name}}}]]]})))

(rf/reg-event-fx
 ::add-favorite
 (fn [{:keys [db]} _]
   {:fx [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                     {:type :add-favorite}]]]}))

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
   (assoc-in db [:ui :settings-open?] open?)))

(rf/reg-event-fx
 ::set-user-name
 (fn [{:keys [db]} [_ name]]
   (let [user-id (get-in db [:connection :user-id])]
     (if user-id
       {:db (assoc-in db [:users user-id :name] name)
        :fx [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                         {:type :update-user
                          :content {:id user-id :name name}}]]]}
       {:db db}))))
