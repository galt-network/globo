(ns is.galt.globo.server.overlays
  "Default MapOverlayProvider: in-memory / static feature lists."
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [is.galt.globo.protocols :as protocols]))

(def label-class
  {:adm0-names "ne-label-adm0"
   :adm1-names "ne-label-adm1"
   :cities "ne-label-city"})

(defn bbox-intersects?
  [{aw :west as :south ae :east an :north}
   {bw :west bs :south be :east bn :north}]
  (and (<= aw be) (>= ae bw) (<= as bn) (>= an bs)))

(defn- path-out
  [{:keys [id coords kind]}]
  {:id id :coords coords :kind kind})

(defn- label-out
  [{:keys [id text lat lng kind pop-max]}]
  (cond-> {:id id :text text :lat lat :lng lng :class (get label-class kind "ne-label")}
    pop-max (assoc :pop-max pop-max)))

(defn- wanted?
  [kinds bbox {:keys [kind] :as item}]
  (and (contains? kinds kind)
       (bbox-intersects? bbox (:bbox item))))

(defn label-budget
  [altitude]
  (cond
    (nil? altitude) {:adm1 14 :cities 4}
    (>= altitude 0.22) {:adm1 14 :cities 4}
    (>= altitude 0.12) {:adm1 22 :cities 8}
    :else {:adm1 36 :cities 16}))

(defn- abs-num [n]
  (Math/abs (double n)))

(defn- same-name?
  [a b]
  (= (str/lower-case (or (:text a) ""))
     (str/lower-case (or (:text b) ""))))

(defn- near?
  [a b]
  (and (< (abs-num (- (:lat a) (:lat b))) 0.15)
       (< (abs-num (- (:lng a) (:lng b))) 0.15)))

(defn select-labels
  [labels altitude]
  (let [adm1-all (filterv #(= :adm1-names (:kind %)) labels)
        city-all (filterv #(= :cities (:kind %)) labels)
        budget (label-budget altitude)
        city-cap (if (< (count adm1-all) 3)
                   (max (:cities budget) 7)
                   (:cities budget))
        pop-by-name (into {}
                          (map (fn [c] [(str/lower-case (or (:text c) ""))
                                        (long (or (:pop-max c) 0))]))
                          city-all)
        kept-adm1 (->> adm1-all
                       (sort-by (juxt #(or (:labelrank %) 99)
                                      #(- (double (or (:area-sqkm %) 0)))
                                      #(- (long (get pop-by-name
                                                     (str/lower-case (or (:text %) ""))
                                                     0)))))
                       (take (:adm1 budget))
                       vec)]
    (into kept-adm1
          (->> city-all
               (remove (fn [c]
                         (some (fn [a] (or (same-name? c a) (near? c a)))
                               kept-adm1)))
               (sort-by #(- (long (or (:pop-max %) 0))))
               (take city-cap)))))

(defrecord StaticOverlayProvider [paths labels]
  protocols/MapOverlayProvider
  (query-overlays [_ {:keys [kinds bbox altitude]}]
    (let [hit-paths (into [] (comp (filter #(wanted? kinds bbox %)) (map path-out)) paths)
          hit-labels (into [] (filter #(wanted? kinds bbox %)) labels)]
      {:paths hit-paths
       :labels (mapv label-out (select-labels hit-labels altitude))})))

(defn static-overlay-provider
  ([] (static-overlay-provider {}))
  ([{:keys [paths labels]}]
   (->StaticOverlayProvider (or paths []) (or labels []))))

(defn- keywordize-item
  [item]
  (cond-> item
    (:kind item) (update :kind keyword)))

(defn file-overlay-provider
  "Load a preprocess JSON file {:paths :labels} with :kind/:bbox on each item."
  [path]
  (let [data (json/parse-string (slurp (io/file path)) true)]
    (static-overlay-provider
     {:paths (mapv keywordize-item (:paths data))
      :labels (mapv keywordize-item (:labels data))})))
