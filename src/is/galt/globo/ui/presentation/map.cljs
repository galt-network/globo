(ns is.galt.globo.ui.presentation.map
  (:require
   [reagent.core :as r]
   [re-frame.core :as rf]
   [applied-science.js-interop :as j]
   [camel-snake-kebab.core :as csk]
   [is.galt.globo.ui.map-objects :as map-objects]
   ["globe.gl" :as Globe]
   ["three" :as THREE]
   ["three/examples/jsm/loaders/GLTFLoader.js" :as GLTFLoader]
   ["three/examples/jsm/loaders/DRACOLoader.js" :as DRACOLoader]
   [is.galt.globo.ui.globe-gl-helpers :refer [apply-config!]]))

(defonce globe-instance (r/atom nil))
(defonce model-cache (atom {}))
(defonce layer-data (atom {:custom-layer-data (new js/Array)}))
;; Number of GLTF loads still outstanding. When this reaches 0 we
;; dispatch ::all-models-ready so the buffer in app.ui.events can
;; flush queued map-objects onto the globe (see :models-ready? flow).
;; Safe because preload-user-models is called after reset! globe-instance
;; in `present`, so @globe-instance is non-nil by the time any load
;; completes.
(defonce pending-loads (atom 0))

(defn add-to-layer
  [layer-key obj]
  (.push (get @layer-data layer-key) obj)
  (j/call @globe-instance (csk/->camelCase layer-key) (get @layer-data layer-key)))

(defn remove-from-layer
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
                  ;; Optional: prepare model (shadows, etc.)
                  (swap! model-cache assoc model-key scene)
                  (when on-ready (on-ready scene)))
                (on-load-complete))
              nil
              (fn [err]
                (js/console.error "Failed to load GLTF model" url err)
                (on-load-complete))))))

;; Preload models when your app starts (e.g. in init or on user login)
(defn preload-user-models []
  (doseq [{:keys [model-id path]} map-objects/config]
    (load-gltf! path model-id #(println "Model loaded" [model-id path])))
  )

(defn create-3d-object
  [d]
  (println ">>> create-3d-object" d)
  (let [model-key (or (j/get d :model-id) "carrot")
        _ (println ">>> create-3d-object MODEL-KEY" model-key)
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
   ; :bump-image-url "//unpkg.com/three-globe/example/img/earth-topology.png"
   ; :background-image-url "//unpkg.com/three-globe/example/img/night-sky.png"

   :background-color "#000011"
   :show-atmosphere true
   :atmosphere-altitude "0.2"
   :point-of-view {:lat 20 :lng 0 :altitude 2.2}
   :on-globe-click (fn [coords]
                     (println ">>> ui.map on-globe-click" (js->clj coords :keywordize-keys true))
                     (js->clj coords :keywordize-keys true))
   :custom-three-object (fn [obj]
                          (println ">>> custom-three-object" obj)
                          (create-3d-object obj))
   ; :custom-three-object-update (fn [obj o-data] (println ">>> custom-three-object-update" [obj o-data]))
   :custom-three-object-update (fn [obj o-data]
                                 (println ">>> custom-three-object-update" {:obj obj :o-data o-data})
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
   :on-custom-layer-click (fn [o-data event coords]
                            (println ">>> on-custom-layer-click" [o-data event (clj->js coords :keywordize-keys true)]))
   :on-custom-layer-hover (fn [obj prev-obj] (println ">>> on-custom-layer-hover" [obj prev-obj]))})

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
    ;; 4. Null out the global handle
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
                                 (resize!)
                                 (preload-user-models)
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
