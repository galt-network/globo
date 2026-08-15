(ns is.galt.globo.ui.events
  "Re-frame events for the globo UI: mouse actions, map objects,
  favorites, chat messages, message arcs, rings, and mobile detection."
  (:require
   [applied-science.js-interop :as j]
    [clojure.set :as set]
    [clojure.string :as str]
    [clojure.walk :as walk]
    [is.galt.globo.ui.camera :as camera]
    [is.galt.globo.ui.hexholds :as hexholds]
    [is.galt.globo.ui.hud-views :as hud-views]
    [is.galt.globo.ui.message-arcs :as message-arcs]
    [is.galt.globo.ui.models :as models]
    [is.galt.globo.ui.natural-earth :as ne]
    [is.galt.globo.ui.presentation.map :as ui.map]
   [is.galt.globo.ui.user-name :as user-name]
   [is.galt.globo.user-figure :as uf]
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
   (let [globe @ui.map/globe-instance]
     (assoc cofx :globe-viewpoint
            (when globe
              (assoc (-> (j/call globe :pointOfView)
                         (js->clj :keywordize-keys true)
                         (select-keys [:lat :lng :altitude]))
                     ;; camera aspect — hexhold viewport sizing needs it
                     :aspect (.-aspect (j/call globe :camera))))))))

(rf/reg-fx
 ::update-map-objects
 (fn [{:keys [op objects]}]
   (case (keyword op)
     :add (doseq [p objects] (ui.map/add-to-layer :custom-layer-data (clj->js p)))
     :remove (doseq [p objects] (ui.map/remove-from-layer :custom-layer-data (clj->js p)))
     :replace (do (ui.map/reset-layer! :custom-layer-data)
                  (doseq [p objects] (ui.map/add-to-layer :custom-layer-data (clj->js p)))))))

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

(defn figure-layer-fx [db]
  (when (get db :models-ready?)
    (let [next (uf/layer-objects (:users db))
          {:keys [remove add]} (uf/sync-actions (or (:user-figures db) []) next)]
      {:db (assoc db :user-figures next)
       :fx (cond-> []
             (seq remove) (conj [::update-map-objects {:op :remove :objects remove}])
             (seq add) (conj [::update-map-objects {:op :add :objects add}]))})))

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
       (let [uid (get-in db [:connection :user-id])
             result (uf/apply-pick (:users db) uid point
                                   (get-in db [:users uid :location]))]
         (if (= :too-close (:status result))
           (do (js/console.log "Too close to an existing user")
               {:db db})
           (let [location (:location result)
                 prev-location (get-in db [:users uid :location])
                 db' (-> db
                         (assoc-in [:users uid :location] location)
                         (assoc-in [:mouse-action] nil))
                 layer (figure-layer-fx db')]
             {:db (get layer :db db')
              :fx (into [[:fetch {:method :post
                                  :url (get-in db [:config :send-message-url])
                                  :body {:type :update-user
                                         :connection-id (get-in db [:connection :connection-id])
                                         :user-id uid
                                         :content {:id uid :location location}}
                                  :request-content-type :json
                                  :response-content-types {#"application/.*json" :json}
                                  :on-success [::pick-location-success]
                                  :on-failure [::pick-location-failed prev-location]}]
                         [::focus-globe {:lat (:lat location)
                                         :lng (:lng location)
                                         :altitude uf/focus-altitude
                                         :duration uf/focus-ms}]]
                        (or (:fx layer) []))})))

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

       (let [altitude (:altitude globe-viewpoint)
             paint (hexholds/click-paint-hexhold db point altitude)
             mark (when-not paint
                    (hexholds/click-painted-mark db point altitude))]
         (cond
           paint {:fx [[:dispatch [::paint-hexhold-at paint]]]}
           mark (let [hex-id (:hex-id mark)]
                  {:db (assoc-in db [:ui :active-view] :hexholds)
                   :fx [[:dispatch [::select-hexhold hex-id]]
                        [::focus-globe (assoc (hexholds/cell->latlng hex-id)
                                              :altitude hexholds/marks-focus-altitude)]
                        [:dispatch [::refresh-hexholds-viewport]]]})
           :else {:db db}))))))

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
    ;; Flip the gate and replace the custom layer with buffered
    ;; map-objects so leftover fallback spheres are dropped.
   (let [figures (uf/layer-objects (:users db))
         objects (concat (seq (get db :map-objects)) figures)
         db' (assoc db :models-ready? true :user-figures figures)]
     (if-let [action (models/flush-layer-action objects)]
       {:db db'
        :fx [[::update-map-objects action]]}
       {:db db'}))))

(rf/reg-event-fx
 ::sync-user-figures
 (fn [{:keys [db]} _]
   (or (figure-layer-fx db) {:db db})))

(rf/reg-event-db
 ::pick-location-success
 (fn [db _]
   db))

(rf/reg-event-fx
 ::pick-location-failed
 (fn [{:keys [db]} [_ prev-location]]
   (js/console.log "Too close to an existing user")
   (let [uid (get-in db [:connection :user-id])
         db' (assoc-in db [:users uid :location] prev-location)
         layer (figure-layer-fx db')]
     {:db (get layer :db db')
      :fx (or (:fx layer) [])})))

(rf/reg-event-fx
 ::set-figure-color
 (fn [{:keys [db]} [_ color]]
   (let [uid (get-in db [:connection :user-id])
         loc (get-in db [:users uid :location])]
     (if-not (uf/has-figure? {:location loc})
       {:db db}
       (let [loc' (assoc-in loc [:model :color] (uf/normalize-color color))
             db' (assoc-in db [:users uid :location] loc')
             layer (figure-layer-fx db')]
         {:db (get layer :db db')
          :fx (into [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                                 {:type :update-user
                                  :content {:id uid :location loc'}}]]]
                    (or (:fx layer) []))})))))

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

(defn- clear-hop-timers! []
  (doseq [t @ui.map/hop-timers]
    (js/clearTimeout t))
  (reset! ui.map/hop-timers []))

(defn- play-hop-legs! [g legs]
  (let [[first-leg & rest-legs] legs]
    (j/call g :pointOfView
            (clj->js (dissoc first-leg :duration))
            (:duration first-leg))
    (reduce (fn [acc-ms leg]
              (let [t (js/setTimeout
                       (fn []
                         (j/call g :pointOfView
                                 (clj->js (dissoc leg :duration))
                                 (:duration leg)))
                       acc-ms)]
                (swap! ui.map/hop-timers conj t)
                (+ acc-ms (:duration leg))))
            (:duration first-leg)
            rest-legs)))

(rf/reg-fx
 ::focus-globe
 (fn [{:keys [duration] :as coords}]
   (when-let [g @ui.map/globe-instance]
     (clear-hop-timers!)
     (let [dest (merge {:altitude 1.5} (dissoc coords :duration))
           from (when duration
                  (-> (j/call g :pointOfView)
                      (js->clj :keywordize-keys true)
                      (select-keys [:lat :lng :altitude])))
           legs (when from (camera/hop-legs from dest))]
       (if legs
         (play-hop-legs! g legs)
         (j/call g :pointOfView (clj->js dest) duration))))))

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
   {:fx [[::focus-globe (merge (select-keys coords [:lat :lng])
                               {:altitude uf/focus-altitude
                                :duration uf/focus-ms})]]}))

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

(rf/reg-event-fx
 ::set-active-view
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} [_ view]]
   (or (hud-views/apply-view db view globe-viewpoint)
       {:db db})))

(rf/reg-event-db
 ::set-user-name-draft
 (fn [db [_ draft]]
   (assoc-in db [:ui :user-name-draft] draft)))

(rf/reg-event-fx
 ::save-user-name
 (fn [{:keys [db]} _]
   (let [user-id (get-in db [:connection :user-id])
         max-length (get-in db [:config :max-user-name-length] 42)
         name' (user-name/clamp-name max-length (get-in db [:ui :user-name-draft]))]
     (if (and user-id
              (not (user-name/name-unchanged?
                    (get-in db [:users user-id :name]) name')))
       {:db (assoc-in db [:ui :user-name-save-error] nil)
        :fx [[:fetch {:method :post
                      :url (get-in db [:config :send-message-url])
                      :body {:type :update-user
                             :connection-id (get-in db [:connection :connection-id])
                             :user-id user-id
                             :content {:id user-id :name name'}}
                      :request-content-type :json
                      :response-content-types {#"application/.*json" :json}
                      :on-success [::user-name-save-success]
                      :on-failure [::user-name-save-failed]}]]}
       {:db (assoc-in db [:ui :user-name-save-error] nil)}))))

(rf/reg-event-db
 ::user-name-save-success
 (fn [db _]
   (assoc-in db [:ui :user-name-save-error] nil)))

(rf/reg-event-db
 ::user-name-save-failed
 (fn [db [_ response]]
   (assoc-in db [:ui :user-name-save-error]
             (or (user-name/save-error-from-response response)
                 {:error "Could not update your name. Please try again."
                  :details nil}))))

;; Hexholds: H3 hexagon grid paint layer.
(rf/reg-fx
 ::sync-hexholds
 (fn [{:keys [visible colors]}]
   (ui.map/sync-hexholds-from-db! visible colors)))

(rf/reg-fx
 ::update-hexhold-hover-tint
 (fn [{:keys [from-id to-id colors selected-id]}]
   (ui.map/set-hexhold-hover-tint! from-id to-id colors selected-id)))

(rf/reg-fx
 ::schedule-hexholds-sync
 (fn [_]
   (ui.map/schedule-hexholds-sync!)))

(rf/reg-event-fx
 ::hexholds-sync-now
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} _]
   {:fx [[::sync-hexholds {:visible (hexholds/layer-features db globe-viewpoint)
                           :colors (get-in db [:hexholds :colors])}]]}))

(rf/reg-event-fx
 ::refresh-hexholds-viewport
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} _]
   (if (and (hud-views/hexholds-view? db)
            (hexholds/within-lod? (:altitude globe-viewpoint)))
     (let [cells (hexholds/viewport-cells globe-viewpoint)
           db' (assoc-in db [:hexholds :visible] [])]
       (if (seq cells)
         {:db db'
          :fx [[:fetch {:method :post
                        :url (get-in db [:config :hexholds-query-url])
                        :body {:cells cells}
                        :request-content-type :json
                        :response-content-types {#"application/.*json" :json}
                        :on-success [::hexholds-query-success]
                        :on-failure [::hexholds-query-failure]}]
               [:dispatch [::update-hexholds-info]]]}
         {:db db'
          :fx [[::sync-hexholds {:visible (hexholds/layer-features db' globe-viewpoint)
                                 :colors (get-in db [:hexholds :colors])}]
               [:dispatch [::update-hexholds-info]]]}))
     (let [db' (-> db
                   (assoc-in [:hexholds :visible] [])
                   (assoc-in [:hexholds :hover-id] nil))]
       {:db db'
        :fx [[::sync-hexholds {:visible (hexholds/layer-features db' globe-viewpoint)
                               :colors (get-in db [:hexholds :colors])}]
             [::update-hexhold-hover-tint
              {:from-id (get-in db [:hexholds :hover-id])
               :to-id nil
               :colors (get-in db [:hexholds :colors])}]
             [:dispatch [::update-hexholds-info]]]}))))

(rf/reg-event-fx
 ::hexholds-query-success
 (fn [{:keys [db]} [_ response]]
   (let [hexholds (get-in response [:body :hexholds] [])
         colors (get-in db [:hexholds :colors])
         visible (mapv (fn [{:keys [id color owner-id]}]
                         {:id id
                          :owner-id owner-id
                          :color (or (get colors id)
                                     (when color (keyword color)))})
                       hexholds)]
     {:db (assoc-in db [:hexholds :visible] visible)
      :fx [[::sync-hexholds {:visible visible
                             :colors (get-in db [:hexholds :colors])}]
           [:dispatch [::update-hexholds-info]]]})))

(rf/reg-event-fx
 ::hexholds-query-failure
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} _]
   (let [db' (assoc-in db [:hexholds :visible] [])]
     {:db db'
      :fx [[::sync-hexholds {:visible (hexholds/layer-features db' globe-viewpoint)
                             :colors (get-in db [:hexholds :colors])}]
           [:dispatch [::update-hexholds-info]]]})))

(rf/reg-event-fx
 ::hexholds-colors
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} [_ {:keys [colors]}]]
   (let [colors' (into {} (map (fn [[k v]] [(name k) (keyword v)])) colors)
         visible (mapv (fn [{:keys [id] :as h}]
                         (if-let [c (get colors' id)]
                           (assoc h :color c)
                           h))
                       (get-in db [:hexholds :visible]))
         db' (-> db
                 (assoc-in [:hexholds :colors] colors')
                 (assoc-in [:hexholds :visible] visible))]
     {:db db'
      :fx [[::sync-hexholds {:visible (hexholds/layer-features db' globe-viewpoint)
                             :colors colors'}]]})))

(rf/reg-event-fx
 ::hexhold-updated
 (fn [{:keys [db]} [_ {:keys [id color owner-id]}]]
   (let [color (when color (keyword color))
         db' (if color
               (assoc-in db [:hexholds :colors id] color)
               (update-in db [:hexholds :colors] dissoc id))
         visible (mapv (fn [h] (if (= id (:id h))
                                 (assoc h :owner-id owner-id)
                                 h))
                       (hexholds/update-visible-entry
                        (get-in db [:hexholds :visible]) id color))
         db'' (assoc-in db' [:hexholds :visible] visible)]
     {:db db''
      :fx [[::schedule-hexholds-sync]
           [:dispatch [::update-hexholds-info]]]})))

(rf/reg-event-fx
 ::set-hexhold-hover
 (fn [{:keys [db]} [_ hex-id]]
   (let [current (get-in db [:hexholds :hover-id])]
     (when (and (hud-views/hexholds-view? db) (not= hex-id current))
       {:db (assoc-in db [:hexholds :hover-id] hex-id)
        :fx [[::update-hexhold-hover-tint
              {:from-id current
               :to-id hex-id
               :colors (get-in db [:hexholds :colors])
               :selected-id (get-in db [:hexholds :selected-id])}]]}))))

(rf/reg-event-fx
 ::paint-hexhold-at
 (fn [{:keys [db]} [_ point]]
   (let [hex-id (hexholds/resolve-paint-hex-id point)
         user-id (get-in db [:connection :user-id])
         visible-ids (into #{} (map :id) (get-in db [:hexholds :visible]))]
     (when (and hex-id (contains? visible-ids hex-id))
       (let [color (if (contains? point :color)
                     (:color point)
                     (hexholds/next-color (get-in db [:hexholds :colors hex-id])))]
         {:fx [[:dispatch [::hexhold-updated {:id hex-id :color color :owner-id user-id}]]
               [:dispatch [:is.galt.globo.ui.connection.events/send-message
                           {:type :paint-hexhold
                            :content {:hex-id hex-id :color color}}]]]})))))

(rf/reg-event-fx
 ::change-hexhold-color
 (fn [{:keys [db]} [_ hex-id color]]
   (let [entry (some #(when (= hex-id (:id %)) %) (get-in db [:hexholds :visible]))
         user-id (get-in db [:connection :user-id])]
     (when (hexholds/can-paint? entry user-id)
       {:fx [[:dispatch [::paint-hexhold-at {:hex-id hex-id :color color}]]]}))))

(rf/reg-event-fx
 ::abandon-hexhold
 (fn [{:keys [db]} [_ hex-id]]
   (let [entry (some #(when (= hex-id (:id %)) %) (get-in db [:hexholds :visible]))
         user-id (get-in db [:connection :user-id])]
     (when (and user-id (hexholds/can-paint? entry user-id))
       {:fx [[:dispatch [::paint-hexhold-at {:hex-id hex-id :color nil}]]]}))))

(rf/reg-event-fx
 ::update-hexholds-info
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} _]
   (let [info (hexholds/viewport-info globe-viewpoint
                                      (get-in db [:hexholds :visible]))]
     (if (= info (get-in db [:hexholds :info]))
       {:db db}
       {:db (assoc-in db [:hexholds :info] info)}))))

(rf/reg-event-fx
 ::select-hexhold
 (fn [{:keys [db]} [_ hex-id]]
   (if (= hex-id (get-in db [:hexholds :selected-id]))
     {:db db}
     {:db (assoc-in db [:hexholds :selected-id] hex-id)
      :fx (cond-> [[::schedule-hexholds-sync]]
            hex-id (conj [:fetch {:method :post
                                  :url (get-in db [:config :hexholds-messages-url])
                                  :body {:hex-id hex-id}
                                  :request-content-type :json
                                  :response-content-types {#"application/.*json" :json}
                                  :on-success [::hexholds-messages-success hex-id]
                                  :on-failure [::hexholds-messages-failure hex-id]}]))})))

(rf/reg-event-fx
 ::hexholds-messages-success
 (fn [{:keys [db]} [_ hex-id response]]
   {:db (assoc-in db [:hexholds :messages hex-id]
                  (get-in response [:body :messages] []))}))

(rf/reg-event-fx
 ::hexholds-messages-failure
 (fn [{:keys [db]} [_ hex-id _]]
   {:db (assoc-in db [:hexholds :messages hex-id] [])}))

(rf/reg-event-fx
 ::leave-hexhold-message
 (fn [{:keys [db]} [_ hex-id text]]
   (let [user-id (get-in db [:connection :user-id])
         text (str/trim text)]
     (when (and (seq text) user-id hex-id)
       {:fx [[:dispatch [:is.galt.globo.ui.connection.events/send-message
                         {:type :hexhold-message
                          :content {:hex-id hex-id :text text}}]]]}))))

(rf/reg-event-fx
 ::receive-hexhold-message
 (fn [{:keys [db]} [_ {:keys [hex-id message]}]]
   (let [messages' (hexholds/upsert-message
                    (get-in db [:hexholds :messages hex-id] [])
                    message)]
     {:db (assoc-in db [:hexholds :messages hex-id] messages')})))

(defn- natural-earth-sync
  [db]
  [::sync-natural-earth
   (ne/overlay-view db (get-in db [:config :assets-base-url]))])

(rf/reg-fx
 ::sync-natural-earth
 (fn [view]
   (ui.map/sync-natural-earth! view)))

(rf/reg-event-fx
 ::load-natural-earth
 (fn [{:keys [db]} _]
   (let [assets (get-in db [:config :assets-base-url])
         fetches (for [{:keys [path file kind]} ne/overlay-sources]
                   [:fetch {:method :get
                            :url (ne/overlay-url assets file)
                            :response-content-types {#"application/.*json" :json
                                                     #"text/.*" :json}
                            :on-success [::natural-earth-layer-loaded path kind]
                            :on-failure [::natural-earth-layer-failed file]}])]
     {:fx (vec fetches)})))

(rf/reg-event-fx
 ::natural-earth-layer-loaded
 (fn [{:keys [db]} [_ path kind response]]
   (let [fc (walk/keywordize-keys (or (:body response) response))
         convert (if (= kind :paths) ne/paths-from-geojson ne/labels-from-geojson)
         db' (assoc-in db (into [:natural-earth :layers] path) (convert fc))]
     {:db db'
      :fx [(natural-earth-sync db')]})))

(rf/reg-event-fx
 ::natural-earth-layer-failed
 (fn [_ [_ file]]
   (js/console.warn "Natural Earth layer failed" file)
   {}))

(rf/reg-event-fx
 ::toggle-natural-earth
 (fn [{:keys [db]} [_ k]]
   (let [db' (update-in db [:natural-earth k] not)]
     {:db db'
      :fx [(natural-earth-sync db')
           [:dispatch [::refresh-natural-earth-scale]]]})))

(rf/reg-event-fx
 ::refresh-natural-earth-scale
 [(rf/inject-cofx ::globe-viewpoint)]
 (fn [{:keys [db globe-viewpoint]} _]
   (let [altitude (or (:altitude globe-viewpoint) 2.2)
         prev (get-in db [:natural-earth :altitude] 2.2)
         db' (assoc-in db [:natural-earth :altitude] altitude)
         close? (< altitude ne/close-altitude)
         kinds (ne/close-query-kinds (:natural-earth db'))]
     (cond
       (and close? (seq kinds))
       {:db db'
        :fx [[:fetch {:method :post
                      :url (get-in db [:config :overlays-query-url])
                      :body {:kinds kinds
                             :bbox (ne/viewport-bbox globe-viewpoint)
                             :altitude altitude}
                      :request-content-type :json
                      :response-content-types {#"application/.*json" :json}
                      :on-success [::natural-earth-overlays-loaded]
                      :on-failure [::natural-earth-overlays-failed]}]
             (natural-earth-sync db')]}
       (= (ne/layers-for-altitude altitude) (ne/layers-for-altitude prev))
       {}
       :else
       (let [cleared (assoc-in db' [:natural-earth :close] {:paths [] :labels []})]
         {:db cleared
          :fx [(natural-earth-sync cleared)]})))))

(rf/reg-event-fx
 ::natural-earth-overlays-loaded
 (fn [{:keys [db]} [_ response]]
   (let [body (walk/keywordize-keys (or (:body response) response))
         db' (assoc-in db [:natural-earth :close]
                       {:paths (or (:paths body) [])
                        :labels (or (:labels body) [])})]
     {:db db'
      :fx [(natural-earth-sync db')]})))

(rf/reg-event-fx
 ::natural-earth-overlays-failed
 (fn [_ _]
   (js/console.warn "Natural Earth overlay query failed")
   {}))
