(ns is.galt.globo.ui.events
  "Re-frame events for the globo UI: mouse actions, map objects,
  favorites, chat messages, message arcs, rings, and mobile detection."
  (:require
   [applied-science.js-interop :as j]
   [clojure.set :as set]
   [is.galt.globo.ui.hexholds :as hexholds]
   [is.galt.globo.ui.message-arcs :as message-arcs]
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

(rf/reg-fx
 ::preload-models
 (fn [{:keys [assets-base-url placeables]}]
   (ui.map/preload-user-models assets-base-url placeables)))

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
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} [_ raw-point]]
   (let [point (if (map? raw-point)
                 raw-point
                 (js->clj raw-point :keywordize-keys true))
         action (get-in db [:mouse-action])]
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

       (let [paint (hexholds/click-paint-hexhold db point (:altitude globe-viewpoint))]
         (if paint
           {:fx [[:dispatch [::paint-hexhold-at paint]]]}
           {:db db}))))))

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
 (fn [{:keys [db globe-viewpoint]} [_ text]]
   (let [self-id (get-in db [:connection :user-id])
         users (get db :users)
         online-users (select-keys users (get-in db [:connection :users-online]))
         origin (message-arcs/origin-location (get-in db [:users self-id])
                                              globe-viewpoint)
         endpoints (message-arcs/endpoints-for-send text self-id users online-users)]
     {:fx (cond-> [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                               {:type :new-message
                                :content {:text text
                                          :viewport globe-viewpoint}}]]]
            (and origin (seq endpoints))
            (conj [:dispatch [::show-message-arcs origin endpoints]]))})))

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

(rf/reg-event-fx
 ::focus-user
 (fn [_ [_ coords]]
   {:fx [[::focus-globe (select-keys coords [:lat :lng])]]}))

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

;; Message arcs: transient globe.gl arcs + endpoint ripple rings shown
;; when a chat message is sent (or received). Arc dash flight is
;; arc-flight-ms; arcs are removed after arc-duration-ms.
(def arc-duration-ms 3000)
(def arc-flight-ms 1500)
(def arc-ring-duration-ms 800)
(def arc-ring-color "#00bcd4")
(def max-message-arcs 20)

(rf/reg-fx
 ::sync-arcs
 (fn [db-arcs]
   (ui.map/sync-arcs-from-db! db-arcs)))

(rf/reg-fx
 ::arc-timer
 (fn [{:keys [id duration]}]
   (when (and duration (pos? duration))
     (swap! ui.map/arc-timers assoc id
            (js/setTimeout
             (fn [] (rf/dispatch [::remove-message-arc id]))
             duration)))))

(rf/reg-fx
 ::clear-arc-timer
 (fn [arc-id]
   (when-let [timer (get @ui.map/arc-timers arc-id)]
     (js/clearTimeout timer)
     (swap! ui.map/arc-timers dissoc arc-id))))

(rf/reg-fx
 ::schedule-dispatch
 (fn [{:keys [delay event]}]
   (js/setTimeout #(rf/dispatch event) delay)))

(rf/reg-event-fx
 ::show-message-arcs
 (fn [{:keys [db]} [_ origin endpoints]]
   (let [new-arcs (into {}
                        (map (fn [endpoint]
                               [(str (random-uuid))
                                (message-arcs/arc-data origin endpoint)]))
                        endpoints)
         db' (update db :message-arcs
                     (fn [m]
                       (let [m (or m {})
                             merged (into m new-arcs)
                             overflow (- (count merged) max-message-arcs)]
                         (if (pos? overflow)
                           (into {} (drop overflow merged))
                           merged))))
         ring-opts {:color arc-ring-color
                    :maxR 3
                    :propagationSpeed 2
                    :repeatPeriod 800
                    :duration arc-ring-duration-ms}
         source-ring (merge (select-keys origin [:lat :lng]) ring-opts)
         target-rings (mapv #(merge (select-keys % [:lat :lng]) ring-opts)
                            endpoints)]
     {:db db'
      :fx (vec (concat
                [[::sync-arcs (:message-arcs db')]
                 [:dispatch [::add-ring source-ring]]]
                (map (fn [[arc-id _]]
                       [::arc-timer {:id arc-id :duration arc-duration-ms}])
                     new-arcs)
                (map (fn [ring]
                       [::schedule-dispatch {:delay arc-flight-ms
                                             :event [::add-ring ring]}])
                     target-rings)))})))

(rf/reg-event-fx
 ::remove-message-arc
 (fn [{:keys [db]} [_ arc-id]]
   (let [db' (update db :message-arcs dissoc arc-id)]
     {:db db'
      :fx [[::clear-arc-timer arc-id]
           [::sync-arcs (:message-arcs db')]]})))

(rf/reg-event-db
 ::clear-message-arcs
 (fn [db _]
   (assoc db :message-arcs {})))

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

;; Hexholds: H3 hexagon grid paint layer.
(rf/reg-fx
 ::sync-hexholds
 (fn [{:keys [visible]}]
   (ui.map/sync-hexholds-from-db! visible)))

(rf/reg-fx
 ::update-hexhold-highlight
 (fn [hover-id]
   (when-let [g @ui.map/globe-instance]
     (ui.map/update-hexhold-highlight! g hover-id))))

(rf/reg-fx
 ::update-hexhold-hover-tint
 (fn [{:keys [from-id to-id colors]}]
   (ui.map/set-hexhold-hover-tint! from-id to-id colors)))

(rf/reg-fx
 ::schedule-hexholds-sync
 (fn [_]
   (ui.map/schedule-hexholds-sync!)))

(rf/reg-event-fx
 ::hexholds-sync-now
 (fn [{:keys [db]} _]
   {:fx [[::sync-hexholds (select-keys (:hexholds db) [:visible])]]}))

(rf/reg-event-fx
 ::toggle-hexholds
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} _]
   (let [active? (get-in db [:hexholds :active?])
         db' (assoc-in db [:hexholds :active?] (not active?))]
     (if active?
       ;; turning OFF: clear grid, hover, highlight
       (let [db'' (-> db'
                      (assoc-in [:hexholds :visible] [])
                      (assoc-in [:hexholds :hover-id] nil))]
         {:db db''
          :fx [[::sync-hexholds {:visible []}]
               [::update-hexhold-highlight nil]]})
       ;; turning ON
       (if (hexholds/within-lod? (:altitude globe-viewpoint))
         {:db db'
          :fx [[:dispatch [::refresh-hexholds-viewport]]]}
         {:db db'
          :fx [[:dispatch [:is.galt.globo.ui.connection.events/system-notification
                           {:content {:message "Zoom in to see hexholds"
                                      :severity :info}}]]]})))))

(rf/reg-event-fx
 ::refresh-hexholds-viewport
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} _]
   (if (and (get-in db [:hexholds :active?])
            (hexholds/within-lod? (:altitude globe-viewpoint)))
     (let [cells (hexholds/viewport-cells (hexholds/viewpoint->bbox globe-viewpoint))
           db' (assoc-in db [:hexholds :visible] [])]
       (if (seq cells)
         {:db db'
          :fx [[:fetch {:method :post
                        :url (get-in db [:config :hexholds-query-url])
                        :body {:cells cells}
                        :request-content-type :json
                        :response-content-types {#"application/.*json" :json}
                        :on-success [::hexholds-query-success]
                        :on-failure [::hexholds-query-failure]}]]}
         {:db db'
          :fx [[::sync-hexholds {:visible []}]]}))
     ;; out of gate: clear grid + hover
     {:db (-> db
              (assoc-in [:hexholds :visible] [])
              (assoc-in [:hexholds :hover-id] nil))
      :fx [[::sync-hexholds {:visible []}]
           [::update-hexhold-highlight nil]]})))

(rf/reg-event-fx
 ::hexholds-query-success
 (fn [{:keys [db]} [_ response]]
   (let [hexholds (get-in response [:body :hexholds] [])
         colors (get-in db [:hexholds :colors])
         visible (mapv (fn [{:keys [id color]}]
                         {:id id
                          :color (or (get colors id)
                                     (when color (keyword color)))})
                       hexholds)]
     {:db (assoc-in db [:hexholds :visible] visible)
      :fx [[::sync-hexholds {:visible visible}]]})))

(rf/reg-event-db
 ::hexholds-query-failure
 (fn [db _]
   (assoc-in db [:hexholds :visible] [])))

(rf/reg-event-fx
 ::hexholds-colors
 (fn [{:keys [db]} [_ {:keys [colors]}]]
   (let [colors' (into {} (map (fn [[k v]] [(name k) (keyword v)])) colors)
         visible (mapv (fn [{:keys [id] :as h}]
                         (if-let [c (get colors' id)]
                           (assoc h :color c)
                           h))
                       (get-in db [:hexholds :visible]))]
     {:db (assoc-in db [:hexholds :colors] colors')
      :fx [[::sync-hexholds {:visible visible}]]})))

(rf/reg-event-fx
 ::hexhold-updated
 (fn [{:keys [db]} [_ {:keys [id color]}]]
   (let [color (when color (keyword color))
         db' (if color
               (assoc-in db [:hexholds :colors id] color)
               (update-in db [:hexholds :colors] dissoc id))
         visible (mapv (fn [{:keys [id'] :as h}]
                         (if (= id' id)
                           (assoc h :color color)
                           h))
                       (get-in db [:hexholds :visible]))
         db'' (assoc-in db' [:hexholds :visible] visible)]
     {:db db''
      :fx [[::schedule-hexholds-sync]]})))

(rf/reg-event-fx
 ::set-hexhold-hover
 (fn [{:keys [db]} [_ hex-id]]
   (let [active? (get-in db [:hexholds :active?])
         current (get-in db [:hexholds :hover-id])]
     (when (and active? (not= hex-id current))
       {:db (assoc-in db [:hexholds :hover-id] hex-id)
        :fx [[::update-hexhold-highlight hex-id]
             [::update-hexhold-hover-tint
              {:from-id current
               :to-id hex-id
               :colors (get-in db [:hexholds :colors])}]]}))))

(rf/reg-event-fx
 ::paint-hexhold-at
 (fn [{:keys [db]} [_ point]]
   (let [hex-id (hexholds/resolve-paint-hex-id point)
         visible-ids (into #{} (map :id) (get-in db [:hexholds :visible]))]
     (when (and hex-id (contains? visible-ids hex-id))
       (let [color (hexholds/next-color (get-in db [:hexholds :colors hex-id]))]
         {:fx [[:dispatch [::hexhold-updated {:id hex-id :color color}]]
               [:dispatch [:is.galt.globo.ui.connection.events/send-message
                           {:type :paint-hexhold
                            :content {:hex-id hex-id :color color}}]]]})))))
