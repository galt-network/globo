(ns is.galt.globo.ui.presentation.map
  "globe.gl integration: Globe construction, GLTF model preloading and
  caching, 3D object placement, rings, and teardown on hot-reload."
  (:require
   ["globe.gl" :as Globe]
   ["three" :as THREE]
   ["three/examples/jsm/loaders/DRACOLoader.js" :as DRACOLoader]
   ["three/examples/jsm/loaders/GLTFLoader.js" :as GLTFLoader]
   [applied-science.js-interop :as j]
   [camel-snake-kebab.core :as csk]
   [is.galt.globo.ui.globe-gl-helpers :refer [apply-config!]]
   [is.galt.globo.ui.hexholds :as hexholds]
   [is.galt.globo.ui.subscriptions :as ui.subs]
   [re-frame.core :as rf]
   [re-frame.db :as rf.db]
   [reagent.core :as r]))

(defonce globe-instance (atom nil))
(defonce model-cache (atom {}))
(defonce layer-data (atom {:custom-layer-data (new js/Array)}))
;; Number of GLTF loads still outstanding. When this reaches 0 we
;; dispatch ::all-models-ready so the buffer in app.ui.events can
;; flush queued map-objects onto the globe (see :models-ready? flow).
;; Safe because preload-user-models is called after reset! globe-instance
;; in `present`, so @globe-instance is non-nil by the time any load
;; completes.
(defonce pending-loads (atom 0))
;; Rings Layer state: the JS array handed to globe.ringsData, plus a map
;; of ring-id -> pending auto-removal setTimeout handle. Reconciles with
;; the app-db :rings map via sync-rings-from-db!.
(defonce rings-data (atom (new js/Array)))
(defonce ring-timers (atom {}))
;; Message Arcs Layer state: the JS array handed to globe.arcsData, plus
;; a map of arc-id -> pending auto-removal setTimeout handle. Reconciles
;; with the app-db :message-arcs map via sync-arcs-from-db!.
(defonce arcs-data (atom (new js/Array)))
(defonce arc-timers (atom {}))
;; Hexholds layer state. hexholds-data is the JS array handed to
;; globe.polygonsData; hexhold-cache maps hex-id -> the SAME JS feature
;; object, so color-only updates mutate materials in place (no geometry
;; rebuild); hexholds-version invalidates the screen-space ring cache on
;; every rebuild.
(defonce hexholds-data (atom (new js/Array)))
(defonce hexhold-cache (atom {}))
(defonce hexholds-version (atom 0))
;; Screen-space ring cache: {key -> {hex-id -> [[px py] ...]}} keyed by
;; camera state fingerprint (hexhold-cache-key).
(defonce hexhold-ring-cache (atom {}))
;; Own canvas listeners + click state for hexhold hit-testing.
(defonce hexhold-move-handler (atom nil))
;; Debounced sync timer handle (::schedule-hexholds-sync batches paint
;; echoes into a single polygonsData call).
(defonce hexhold-sync-timer (atom nil))
;; Debounced viewport-refresh timer handle for camera moves (onZoom).
(defonce hexhold-viewport-refresh-timer (atom nil))

(defn schedule-hexholds-viewport-refresh!
  "Trailing-debounced viewport refresh on camera moves (drag/zoom fly
   through the LOD boundary). Dispatches the re-frame event that re-runs
   the active? + within-lod? gates and re-queries the viewport."
  [_pov]
  (when-let [t @hexhold-viewport-refresh-timer]
    (js/clearTimeout t))
  (reset! hexhold-viewport-refresh-timer
          (js/setTimeout
           (fn []
             (reset! hexhold-viewport-refresh-timer nil)
             (rf/dispatch [:is.galt.globo.ui.events/refresh-hexholds-viewport])
             (rf/dispatch [:is.galt.globo.ui.events/update-hexholds-info]))
           250)))

(defn- default-ring-params
  "Fill in globe.gl Rings Layer defaults for any field absent from `ring`."
  [ring]
  (merge {:lat 0 :lng 0 :altitude 0.0015 :maxR 3
          :propagationSpeed 2 :repeatPeriod 1000
          :color "#ffffaa"}
         (select-keys ring [:lat :lng :altitude :maxR :propagationSpeed
                            :repeatPeriod :startRadius :color :resolution])))

(defn- ring->js
  "Convert app-db ring data into a globe.gl ring JS object, tagged with a
   hidden :__ring-id so sync-rings-from-db! can reconcile by identity."
  [id ring-data]
  (-> ring-data
      default-ring-params
      (assoc :__ring-id id)
      clj->js))

(defn sync-rings-from-db!
  "Reconcile the globe-side rings JS array with the app-db :rings map.
   Rings already on the globe keep their JS object identity so their ripple
   animation is not interrupted; new rings are converted and appended;
   removed rings are filtered out. Calls globe.ringsData once if mounted."
  [db-rings]
  (let [by-id (into {} (map (fn [r] [(j/get r :__ring-id) r])) @rings-data)
        js-rings (reduce
                  (fn [acc id]
                    (conj acc (or (get by-id id) (ring->js id (get db-rings id)))))
                  []
                  (keys db-rings))]
    (reset! rings-data (clj->js js-rings))
    (when-let [g @globe-instance]
      (j/call g :ringsData @rings-data))))

(defn- arc->js
  "Convert app-db arc data into a globe.gl arc JS object, tagged with a
   hidden :__arc-id so sync-arcs-from-db! can reconcile by identity."
  [id arc]
  (-> arc
      (assoc :color "#00bcd4" :__arc-id id)
      clj->js))

(defn sync-arcs-from-db!
  "Reconcile the globe-side arcs JS array with the app-db :message-arcs
   map. Arcs already on the globe keep their JS object identity so the
   dash animation is not interrupted; new arcs are converted and appended;
   removed arcs are filtered out. Calls globe.arcsData once if mounted."
  [db-arcs]
  (let [by-id (into {} (map (fn [a] [(j/get a :__arc-id) a])) @arcs-data)
        js-arcs (reduce
                 (fn [acc id]
                   (conj acc (or (get by-id id) (arc->js id (get db-arcs id)))))
                 []
                 (keys db-arcs))]
    (reset! arcs-data (clj->js js-arcs))
    (when-let [g @globe-instance]
      (j/call g :arcsData @arcs-data))))

;; Hexholds layer
(defn- current-viewpoint
  []
  (when-let [g @globe-instance]
    (assoc (-> (j/call g :pointOfView)
               (js->clj :keywordize-keys true)
               (select-keys [:lat :lng :altitude]))
           :aspect (.-aspect (j/call g :camera)))))

(defn- idle-stroke
  [claiming?]
  (if claiming? hexholds/default-stroke hexholds/mark-stroke))

(defn- hexhold->js-feature
  [{:keys [id color]} marks?]
  (-> (hexholds/polygon-feature id color)
      (merge (hexholds/hexhold->props color marks?))
      clj->js))

(defn- apply-hover-tint!
  "Re-apply hover + selection tints (fill + teal border stroke) after a
   paint or rebuild sync (both reset cap-color and stroke-color to the
   painted state). One full pass over the cache: hovered and selected
   cells get the teal stroke, everything else reverts to painted fill +
   idle stroke — so a selection change also un-highlights the
   previously selected cell."
  []
  (let [app-db @rf.db/app-db
        hover-id (get-in app-db [:hexholds :hover-id])
        selected-id (get-in app-db [:hexholds :selected-id])
        claiming? (hexholds/claiming? app-db (:altitude (current-viewpoint)))
        stroke (idle-stroke claiming?)]
    (doseq [[id f] @hexhold-cache]
      (let [color (get-in app-db [:hexholds :colors id])]
        (cond
          (= id hover-id)
          (do (aset f "cap-color" (hexholds/hover-fill-color color))
              (aset f "stroke-color" hexholds/highlight-stroke-color))
          (= id selected-id)
          (do (aset f "cap-color" (hexholds/fill-color color))
              (aset f "stroke-color" hexholds/highlight-stroke-color))
          :else
          (do (aset f "cap-color" (hexholds/fill-color color))
              (aset f "stroke-color" stroke)))))
    (when-let [g @globe-instance]
      (j/call g :polygonsData @hexholds-data))))

(defn sync-hexholds-from-db!
  "Reconcile the globe polygonsData array with the app-db :hexholds
   state. Layer ids come from layer-features (grid while claiming within
   LOD, else nearest painted marks). :colors owns paint colors — feature
   colors are ALWAYS derived from :colors, never from entry colors.
   When the id set matches the cache, only the color fields are mutated
   on the SAME cached JS objects and the same array is re-passed to
   polygonsData. When the id set differs the whole array is rebuilt."
  [_visible colors]
  (let [db @rf.db/app-db
        viewpoint (current-viewpoint)
        visible (hexholds/layer-features db viewpoint)
        colors (or colors (get-in db [:hexholds :colors] {}))
        marks? (not (hexholds/claiming? db (:altitude viewpoint)))
        cache @hexhold-cache
        ids (into #{} (map :id) visible)
        cached-ids (into #{} (keys cache))]
    (if (= ids cached-ids)
      (do
        (doseq [{:keys [id]} visible]
          (when-let [feature (get cache id)]
            (aset feature "color" (get colors id))
            (aset feature "cap-color" (hexholds/fill-color (get colors id)))))
        (when-let [g @globe-instance]
          (j/call g :polygonsData @hexholds-data))
        (apply-hover-tint!))
      (let [features (->> visible
                          (mapv (fn [{:keys [id]}]
                                  (hexhold->js-feature {:id id :color (get colors id)} marks?)))
                          clj->js)]
        (reset! hexholds-data features)
        (swap! hexholds-version inc)
        (reset! hexhold-ring-cache {})
        (reset! hexhold-cache (into {} (map (fn [f] [(aget f "id") f])) features))
        (when-let [g @globe-instance]
          (j/call g :polygonsData features))
        (apply-hover-tint!)))))

(defn set-hexhold-hover-tint!
  "Hover transition for a hover change: the previously hovered cell
   (from-id) reverts to its painted fill + idle stroke, the newly hovered
   cell (to-id) gets the hover fill + teal border stroke. The teal stroke
   is the cell's OWN border (rendered by the polygons layer at 1+alt+1e-4,
   exactly over the caps) — so the outline is pixel-aligned with the cell
   by construction, at any camera angle. Mutates the cached JS features in
   place — no geometry rebuild."
  [from-id to-id colors selected-id]
  (let [cache @hexhold-cache
        from-f (when from-id (get cache from-id))
        to-f (when to-id (get cache to-id))
        stroke (idle-stroke (hexholds/claiming? @rf.db/app-db
                                                (:altitude (current-viewpoint))))]
    (when (or from-f to-f)
      (when from-f
        (aset from-f "cap-color" (hexholds/fill-color (get colors from-id)))
        (aset from-f "stroke-color"
              (if (= from-id selected-id)
                hexholds/highlight-stroke-color
                stroke)))
      (when to-f
        (aset to-f "cap-color" (hexholds/hover-fill-color (get colors to-id)))
        (aset to-f "stroke-color" hexholds/highlight-stroke-color))
      (when-let [g @globe-instance]
        (j/call g :polygonsData @hexholds-data)))))

(defn- project-ring-vertex
  "Project one [lng lat] vertex to canvas pixel coords. The vertex is
   placed at ring-altitude (the polygon caps' render scale), so projected
   rings line up with the on-screen polygons."
  [globe camera w h lng lat]
  (let [v (j/call globe :getCoords lat lng hexholds/ring-altitude)
        vec3 (THREE/Vector3. (.-x v) (.-y v) (.-z v))]
    (j/call vec3 :applyMatrix4 (j/get camera :matrixWorldInverse))
    (j/call vec3 :applyMatrix4 (j/get camera :projectionMatrix))
    [(* (+ (.-x vec3) 1) 0.5 w)
     (* (- 1 (.-y vec3)) 0.5 h)]))

(defn projected-cell-rings
  "Screen-space rings {hex-id -> [[px py] ...closed-ring]} for every
   feature currently in hexholds-data, projected from world coords with
   the current camera matrices."
  [globe]
  (let [renderer (j/call globe :renderer)
        canvas (j/get renderer :domElement)
        w (.-clientWidth canvas)
        h (.-clientHeight canvas)
        camera (j/call globe :camera)]
    (j/call camera :updateMatrixWorld true)
    (into {}
          (map (fn [feature]
                 (let [coords (j/get-in feature [:geometry :coordinates 0])
                       ring (mapv (fn [v] (project-ring-vertex globe camera w h (aget v 0) (aget v 1)))
                                  coords)]
                   [(aget feature "id") ring])))
          @hexholds-data)))

(defn hexhold-cache-key
  "Fingerprint of the camera state + canvas size + layer version. When it
   matches the cached key the projected rings are reused verbatim."
  [globe]
  (let [camera (j/call globe :camera)
        pos (j/get camera :position)
        quat (j/get camera :quaternion)
        canvas (j/get (j/call globe :renderer) :domElement)]
    (str @hexholds-version
         "|" (j/get camera :zoom)
         "|" (j/get pos :x) "," (j/get pos :y) "," (j/get pos :z)
         "|" (j/get quat :x) "," (j/get quat :y) "," (j/get quat :z) "," (j/get quat :w)
         "|" (.-clientWidth canvas) "x" (.-clientHeight canvas))))

(defn hit-test-hexhold
  "Screen-space hit test: the hexagon under the pointer (cursor position
   from the event), or nil. Returns {:hex-id id :lat lat :lng lng} with
   the CELL CENTER coords."
  [globe ev]
  (let [renderer (j/call globe :renderer)
        canvas (j/get renderer :domElement)
        rect (j/call canvas :getBoundingClientRect)
        px (- (.-pageX ev) (+ (.-left rect) (.-scrollX js/window)))
        py (- (.-pageY ev) (+ (.-top rect) (.-scrollY js/window)))
        w (.-clientWidth canvas)
        h (.-clientHeight canvas)
        computed-key (hexhold-cache-key globe)
        {:keys [key rings]} @hexhold-ring-cache
        rings (if (= computed-key key)
                rings
                (let [rings' (projected-cell-rings globe)]
                  (swap! hexhold-ring-cache assoc :key computed-key :rings rings')
                  rings'))
        hit (hexholds/hit-test-point rings px py)]
    (when hit
      (let [{:keys [lat lng]} (hexholds/cell->latlng hit)]
        {:hex-id hit :lat lat :lng lng}))))

(def hover-throttle-ms 30)
(def click-max-drift-px 5)
(def click-max-elapsed-ms 400)

(defn install-hexhold-listeners!
  "Own canvas pointer listeners for hexhold hover + click. The internal
   three-render-objects click pipeline is broken for real mice (P6), so
   everything is driven from these + the screen-space hit test."
  [globe]
  (let [canvas (j/get (j/call globe :renderer) :domElement)
        hover-handler
        (fn [ev]
          (let [now (js/Date.now)
                last-fire (:last-fire @hexhold-move-handler)]
            (when (or (nil? last-fire) (>= (- now last-fire) hover-throttle-ms))
              (swap! hexhold-move-handler assoc :last-fire now)
              (rf/dispatch [:is.galt.globo.ui.events/set-hexhold-hover
                            (:hex-id (hit-test-hexhold globe ev))]))))
        down-handler
        (fn [ev]
          (swap! hexhold-move-handler assoc
                 :down-x (.-pageX ev)
                 :down-y (.-pageY ev)
                 :down-t (js/Date.now)))
        up-handler
        (fn [ev]
          (let [{:keys [down-x down-y down-t]} @hexhold-move-handler
                dx (js/Math.abs (- (.-pageX ev) (or down-x -100000)))
                dy (js/Math.abs (- (.-pageY ev) (or down-y -100000)))
                elapsed (- (js/Date.now) (or down-t 0))]
            (when (and down-t
                       (< elapsed click-max-elapsed-ms)
                       (<= dx click-max-drift-px)
                       (<= dy click-max-drift-px))
              (when-let [point (hit-test-hexhold globe ev)]
                (rf/dispatch [:is.galt.globo.ui.events/click-globe point])))))]
    (.addEventListener canvas "pointermove" hover-handler)
    (.addEventListener canvas "pointerdown" down-handler)
    (.addEventListener canvas "pointerup" up-handler)
    (reset! hexhold-move-handler {:hover-handler hover-handler
                                  :down-handler down-handler
                                  :up-handler up-handler
                                  :last-fire nil
                                  :down-x nil
                                  :down-y nil
                                  :down-t nil})))

(defn schedule-hexholds-sync!
  "Trailing-debounced polygonsData sync (120ms), so bursts of paint
   echoes collapse into one material update."
  []
  (when-let [t @hexhold-sync-timer]
    (js/clearTimeout t))
  (reset! hexhold-sync-timer
          (js/setTimeout
           (fn []
             (reset! hexhold-sync-timer nil)
             (rf/dispatch [:is.galt.globo.ui.events/hexholds-sync-now]))
           120)))

(defn reset-layer!
  [layer-key]
  (swap! layer-data assoc layer-key (new js/Array))
  (when-let [g @globe-instance]
    (j/call g (csk/->camelCase layer-key) (get @layer-data layer-key))))

(defn add-to-layer
  [layer-key obj]
  (.push (get @layer-data layer-key) obj)
  (j/call @globe-instance (csk/->camelCase layer-key) (get @layer-data layer-key)))

(defn remove-from-layer
  [layer-key obj]
  [layer-key obj]
  (let [id (:id obj)
        layer-objects (get @layer-data layer-key)
        idx (loop [i 0]
              (cond
                (>= i (.-length layer-objects)) -1
                (= id (aget layer-objects i :id)) i
                :else (recur (inc i))))]
    (if (neg? idx)
      false
      (do
        (.splice layer-objects idx 1)
        (j/call @globe-instance (csk/->camelCase layer-key) layer-objects)
        true))))

(defn load-gltf!
  "Load a GLTF model (with DRACO decompression) and cache its scene
  under `model-key`. Increments `pending-loads` for the duration of
  the fetch; when the count returns to 0 (i.e. all preloaded models
  have arrived, success or failure) dispatches
  `:is.galt.globo.ui.events/all-models-ready` so the events layer can flush
  buffered map-objects onto the now-ready globe."
  [url model-key on-ready]
  (let [loader (new GLTFLoader/GLTFLoader)
        draco-loader (new DRACOLoader/DRACOLoader)]
    (j/call draco-loader :setDecoderPath "https://www.gstatic.com/draco/versioned/decoders/1.5.7/")
    (j/call loader :setDRACOLoader draco-loader)
    (swap! pending-loads inc)
    (let [on-load-complete
          (fn []
            (when (zero? (swap! pending-loads dec))
              ;; All preloads finished -> tell the events layer to
              ;; place any objects that were buffered while we were
              ;; loading. Disable until at least one globe is mounted
              ;; so we do not nil-deref @globe-instance in add-to-layer.
              (when @globe-instance
                (rf/dispatch [:is.galt.globo.ui.events/all-models-ready]))))]
      (j/call loader :load
              url
              (fn [gltf]
                (let [scene (j/get gltf :scene)]
                  (swap! model-cache assoc model-key scene)
                  (when on-ready (on-ready scene)))
                (on-load-complete))
              nil
              (fn [err]
                (js/console.error "Failed to load GLTF model" url err)
                (on-load-complete))))))

(defn preload-user-models
  "Preload every placeable GLB model into `model-cache` so placing
   objects does not hit the green-sphere fallback.

   Empty placeables are a no-op — the models-ready gate is opened by
   `::all-models-ready` after loads finish, or by the server's
   :placeable-map-objects event when it sends an empty list."
  [assets-base-url placeables]
  (doseq [{:keys [model-id path]} placeables]
    (let [url (str assets-base-url "/" path)]
      (load-gltf! url model-id nil))))

(defn create-3d-object
  [d]
  (let [model-key (or (j/get d :model-id) "carrot")
        base (get @model-cache model-key)]
    (if base
      ;; Clone so each placed object is independent
      (let [clone (j/call base :clone true)]
        ;; Scale to reasonable size on globe (tweak per model)
        (j/update! clone :scale (fn [s] (j/call s :setScalar (j/get d :scale))))
        ;; Optional: slight random rotation for variety
        (when-let [rot (j/get d :rotation)]
          (j/call clone :rotation :set
                  (or (j/get rot :x) 0)
                  (or (j/get rot :y) 0)
                  (or (j/get rot :z) 0)))
        clone)
      ;; Fallback simple marker while loading
      (let [geom (THREE/SphereGeometry. 0.5 16 16)
            mat (THREE/MeshLambertMaterial. #js {:color 0x00ff88})]
        (THREE/Mesh. geom mat)))))

(def globe-gl-config
  {:height 600
   :width 800
   :globe-image-url "//unpkg.com/three-globe/example/img/earth-blue-marble.jpg"
   :background-color "#000011"
   :show-atmosphere true
   :atmosphere-altitude "0.2"
   :point-of-view {:lat 20 :lng 0 :altitude 2.2}
   :ring-max-radius "maxR"
   :ring-propagation-speed "propagationSpeed"
   :ring-repeat-period "repeatPeriod"
   :ring-altitude "altitude"
   :ring-color "color"
   :arc-color "color"
   :arc-dash-length 0.35
   :arc-dash-gap 4
   :arc-dash-initial-gap 1
   :arc-dash-animate-time 1500
   :arcs-transition-duration 0
   :on-globe-click (fn [coords]
                     (js->clj coords :keywordize-keys true))
   :custom-three-object create-3d-object
   :custom-three-object-update (fn [obj o-data]
                                 (let [g ^js @globe-instance
                                       lat (j/get o-data :lat)
                                       lng (j/get o-data :lng)
                                       alt (or (j/get o-data :alt) 0.01)
                                       coords ^js (.getCoords g lat lng alt)]
                                   (j/call ^js (.-position obj) :set
                                           (.-x coords)
                                           (.-y coords)
                                           (.-z coords)))
                                 obj)
   :on-custom-layer-click (fn [_ _ _] nil)
   :on-custom-layer-hover (fn [_ _] nil)
   :polygons-data []
   :polygon-geo-json-geometry "geometry"
   :polygon-cap-color "cap-color"
   :polygon-side-color (constantly nil)
   :polygon-stroke-color "stroke-color"
   :polygon-altitude "altitude"
   :polygons-transition-duration 0
   :on-polygon-click (fn [_ _ _] nil)
   :on-zoom schedule-hexholds-viewport-refresh!})

(defn- dispose-globe!
  "Tear down a Globe instance: stop the render loop, dispose Three.js
   resources (geometries/materials/textures), detach the canvas, and
   drop the JS reference so the browser can GC the WebGL context.

   Intentionally does NOT call renderer.dispose() or forceContextLoss():
   those mark the context as lost synchronously but Chrome only reclaims
   it after webglcontextlost fires, which can trip the 16-context cap
   on rapid hot-reloads and break the next Globe mount."
  [^js globe]
  (when globe
    ;; 1. Stop the render loop (cancelAnimationFrame chain)
    (try
      (.pauseAnimation globe)
      (catch :default _))
    ;; 2. Traverse scene and dispose geometries / materials / textures
    (try
      (let [scene (.scene globe)]
        (.traverse scene
                   (fn [obj]
                     (when-let [g (j/get obj :geometry)] (.dispose ^js g))
                     (when-let [m (j/get obj :material)]
                       (if (js/Array.isArray m)
                         (doseq [mm m] (.dispose ^js mm))
                         (.dispose ^js m)))
                     (when-let [t (j/get obj :texture)] (.dispose ^js t)))))
      (catch :default _))
    ;; 3. Remove canvas from DOM.
    ;; Deliberately NOT calling renderer.dispose() / forceContextLoss():
    ;; those mark the context as lost synchronously, but Chrome only reclaims
    ;; it asynchronously (after webglcontextlost fires). That window lets us
    ;; briefly exceed the 16-active-context cap on hot-reload, causing the
    ;; next WebGLRenderer to throw "Error creating WebGL context." and the
    ;; new globe to fail to mount. Detaching the canvas + dropping the JS
    ;; reference lets the browser GC the context on its own schedule.
    (try
      (let [renderer (.renderer ^js globe)
            canvas (.-domElement ^js renderer)]
        (when (and canvas (.-parentNode ^js canvas))
          (.removeChild ^js (.-parentNode ^js canvas) canvas)))
      (catch :default _))
    ;; 4. Clear pending ring auto-removal timers and reset ring state.
    ;; Rings live in app-db :rings and are re-synced on the next mount.
    (doseq [[_ t] @ring-timers]
      (js/clearTimeout t))
    (reset! ring-timers {})
    (reset! rings-data (new js/Array))
    ;; 5. Clear pending arc auto-removal timers and reset the arc layer.
    ;; Arcs are transient (3s) effects, not re-synced on remount; drop
    ;; any stale app-db :message-arcs entries so they don't replay when
    ;; the next ::show-message-arcs syncs against the db.
    (doseq [[_ t] @arc-timers]
      (js/clearTimeout t))
    (reset! arc-timers {})
    (reset! arcs-data (new js/Array))
    (rf/dispatch [:is.galt.globo.ui.events/clear-message-arcs])
    ;; 6. Hexholds: remove own canvas listeners, cancel pending timers,
    ;; reset layer state. The app-db :hexholds :visible is re-synced on
    ;; the next mount.
    (when-let [{:keys [hover-handler down-handler up-handler]} @hexhold-move-handler]
      (try
        (let [canvas (j/get (j/call globe :renderer) :domElement)]
          (.removeEventListener canvas "pointermove" hover-handler)
          (.removeEventListener canvas "pointerdown" down-handler)
          (.removeEventListener canvas "pointerup" up-handler))
        (catch :default _))
      (reset! hexhold-move-handler nil))
    (when-let [t @hexhold-sync-timer]
      (js/clearTimeout t)
      (reset! hexhold-sync-timer nil))
    (when-let [t @hexhold-viewport-refresh-timer]
      (js/clearTimeout t)
      (reset! hexhold-viewport-refresh-timer nil))
    (reset! hexhold-ring-cache {})
    (swap! hexholds-version inc)
    (reset! hexhold-cache {})
    (reset! hexholds-data (new js/Array))
    (swap! layer-data assoc :custom-layer-data (new js/Array))
    ;; 7. Null out the global handle
    (reset! globe-instance nil)))

(defn present
  [{:keys [css-classes]
    :or {css-classes []}
    :as app-config}]
  (r/with-let [container-ref (atom nil)
               resize! (fn []
                         (when-let [c @container-ref]
                           (let [g @globe-instance
                                 w (.-clientWidth c)
                                 h (.-clientHeight c)]
                             (when g
                               (.width g w)
                               (.height g h)))))
               ;; Stable identity across renders: React only invokes this on
               ;; mount (el=element) and unmount (el=nil), not on re-renders.
               on-ref (fn [el]
                        (when el
                          ;; Hot-reload: a previous globe may still be live
                          ;; because useEffect cleanup runs *after* this ref
                          ;; fires. Dispose it synchronously before mounting.
                          (when @globe-instance
                            (dispose-globe! @globe-instance))
                          ;; Defer new Globe construction by one frame so the
                          ;; browser has a chance to process the old canvas's
                          ;; teardown (DOM detach + GC) before we ask for a
                          ;; fresh WebGL context. Without this defer, on rapid
                          ;; hot-reloads the previous context can still be
                          ;; counted against Chrome's 16-context cap and the
                          ;; new WebGLRenderer throws on getContext().
                          (js/requestAnimationFrame
                           (fn []
                             ;; If the component unmounted before the frame
                             ;; fired, el is detached (parentNode is null).
                             ;; Bail out so we don't leak a Globe onto a
                             ;; detached element.
                             (when (and el (.-parentNode el))
                               (reset! container-ref el)
                               (let [globe (new Globe el)]
                                 (apply-config! globe globe-gl-config app-config)
                                 (reset! globe-instance globe)
                                 (install-hexhold-listeners! globe)
                                 ;; Re-sync any rings that were in app-db
                                 ;; before this globe was (re)mounted, so
                                 ;; they survive hot-reloads.
                                 (sync-rings-from-db! (get-in @rf.db/app-db [:rings]))
                                 (sync-hexholds-from-db!
                                  (get-in @rf.db/app-db [:hexholds :visible])
                                  (get-in @rf.db/app-db [:hexholds :colors]))
                                 (resize!)
                                 (let [assets-base-url @(rf/subscribe [::ui.subs/assets-base-url])
                                       placeables (vals (get-in @rf.db/app-db [:placeable-map-objects]))]
                                   (preload-user-models assets-base-url placeables))
                                 (js/window.addEventListener "resize" resize!)
                                 (js/window.addEventListener "orientationchange" resize!)))))))]
    [:div#globe-container {:class css-classes :ref on-ref}]
    (finally
      ;; Real unmount cleanup. dispose-globe! is idempotent: if on-ref
      ;; already disposed during hot-reload, this is a no-op.
      (js/window.removeEventListener "resize" resize!)
      (js/window.removeEventListener "orientationchange" resize!)
      (dispose-globe! @globe-instance)
      (reset! container-ref nil))))
