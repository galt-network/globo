(ns is.galt.globo.ui.natural-earth
  "Pure helpers for Natural Earth overlays and textures.")

(def close-altitude 0.35)

(defn scale-for-altitude
  "LoD: 110m when altitude is 1.0 or higher, otherwise 50m."
  [altitude]
  (if (>= altitude 1.0) :110m :50m))

(defn layers-for-altitude
  [altitude]
  {:adm0-scale (scale-for-altitude altitude)
   :layers (if (>= altitude close-altitude)
             #{:adm0-borders :adm0-names}
             #{:adm0-borders :adm1-borders :adm1-names :cities})})

(defn paths-from-geojson
  "Convert a LineString FeatureCollection into globe.gl path maps.
   GeoJSON coordinates are [lng lat]; globe.gl path points are [lat lng]."
  [fc]
  (mapv (fn [feat]
          {:id (get-in feat [:properties :id])
           :coords (mapv (fn [[lng lat]] [lat lng])
                         (get-in feat [:geometry :coordinates]))})
        (:features fc)))

(defn labels-from-geojson
  "Convert a Point FeatureCollection into globe.gl label maps."
  [fc]
  (mapv (fn [feat]
          (let [[lng lat] (get-in feat [:geometry :coordinates])]
            {:id (or (get-in feat [:properties :id])
                    (get-in feat [:properties :adm0_a3]))
             :text (get-in feat [:properties :name])
             :lat lat
             :lng lng}))
        (:features fc)))

(defn- in-band?
  [auto-layers kind]
  (contains? auto-layers kind))

(defn- with-class
  [labels class]
  (mapv #(assoc % :class class) labels))

(defn visible-paths
  [{:keys [altitude layers close] :as ne}]
  (let [{:keys [adm0-scale] auto-layers :layers} (layers-for-altitude (or altitude 2.2))]
    (cond-> []
      (and (in-band? auto-layers :adm0-borders) (:adm0-borders? ne))
      (into (mapv #(assoc % :kind :adm0-borders)
                  (get-in layers [:adm0 adm0-scale :paths] [])))
      (and (in-band? auto-layers :adm1-borders) (:adm1-borders? ne))
      (into (get-in close [:paths] [])))))

(defn visible-labels
  [{:keys [altitude layers close] :as ne}]
  (let [{:keys [adm0-scale] auto-layers :layers} (layers-for-altitude (or altitude 2.2))]
    (cond-> []
      (and (in-band? auto-layers :adm0-names) (:adm0-names? ne))
      (into (with-class (get-in layers [:adm0 adm0-scale :labels] []) "ne-label-adm0"))
      (and (in-band? auto-layers :adm1-names) (:adm1-names? ne))
      (into (filter #(= "ne-label-adm1" (:class %)) (get-in close [:labels] [])))
      (and (in-band? auto-layers :cities) (:cities? ne))
      (into (filter #(= "ne-label-city" (:class %)) (get-in close [:labels] []))))))

(defn viewport-bbox
  [{:keys [lat lng altitude]}]
  (let [half (max 3.0 (* (or altitude 0.2) 40))]
    {:west (- lng half)
     :south (max -90 (- lat half))
     :east (+ lng half)
     :north (min 90 (+ lat half))}))

(defn close-query-kinds
  [ne]
  (cond-> []
    (:adm1-borders? ne) (conj "adm1-borders")
    (:adm1-names? ne) (conj "adm1-names")
    (:cities? ne) (conj "cities")))

(defn globe-image-url
  [assets-base-url]
  (str assets-base-url "/natural-earth/ne-hyp-sr-ob-dr-8k.webp"))

(def overlay-sources
  [{:path [:adm0 :110m :paths] :file "ne_110m_admin_0_boundary_lines_land.json" :kind :paths}
   {:path [:adm0 :110m :labels] :file "ne_110m_admin_0_countries_labels.json" :kind :labels}
   {:path [:adm0 :50m :paths] :file "ne_50m_admin_0_boundary_lines_land.json" :kind :paths}
   {:path [:adm0 :50m :labels] :file "ne_50m_admin_0_countries_labels.json" :kind :labels}])

(defn overlay-url
  [assets-base-url file]
  (str assets-base-url "/natural-earth/" file))

(defn overlay-view
  [{:keys [natural-earth]} assets-base-url]
  {:globe-image-url (globe-image-url assets-base-url)
   :paths (visible-paths natural-earth)
   :labels (visible-labels natural-earth)})
