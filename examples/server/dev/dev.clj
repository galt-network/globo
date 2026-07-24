(ns dev
  (:require
   [babashka.process :as process]
   [clj-reload.core :as reload]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [server.main]))

(alter-var-root #'*warn-on-reflection* (constantly true))

(reload/init
 {:no-reload '#{dev}})

(defonce shadow-watch (atom nil))

(def repo-root
  (-> (io/file "..") .getCanonicalPath))

(defn- asset-path
  [mount-path]
  (str (or mount-path "/map") "/assets/js"))

(defn- stop-shadow-server!
  "Stops the persistent shadow-cljs JVM so the next watch picks up config-merge."
  []
  (try
    (process/shell {:dir repo-root :out :string :err :string}
                   "npx" "shadow-cljs" "stop")
    (catch Exception e
      (println "shadow-cljs stop:" (ex-message e)))))

(defn start-shadow-watch!
  "Starts `npx shadow-cljs watch globo` from the repo root with
  :asset-path matching the example mount-path (for hot reload)."
  [& [{:keys [mount-path] :or {mount-path "/map"}}]]
  (when @shadow-watch
    (process/destroy-tree @shadow-watch)
    (reset! shadow-watch nil))
  (stop-shadow-server!)
  (let [merge-edn (pr-str {:asset-path (asset-path mount-path)})
        proc (process/process ["npx" "shadow-cljs" "watch" "globo"
                               "--config-merge" merge-edn]
                              {:dir repo-root
                               :out :inherit
                               :err :inherit})]
    (reset! shadow-watch proc)
    (println "Started shadow-cljs watch"
             {:dir repo-root :asset-path (asset-path mount-path)})))

(defn stop-shadow-watch!
  []
  (when-let [proc @shadow-watch]
    (process/destroy-tree proc)
    (reset! shadow-watch nil))
  (stop-shadow-server!)
  (println "Stopped shadow-cljs watch"))

(defn go!
  "Reloads changed namespaces and restarts the HTTP server."
  []
  (reload/reload))

(defn start!
  "Starts shadow-cljs watch + HTTP server (static example).

  Options:
    :port       HTTP port (default 3000)
    :mount-path Globo mount path (default \"/map\")"
  [& [{:keys [port mount-path] :or {port 3000 mount-path "/map"}}]]
  (let [mount-path (if (str/starts-with? (or mount-path "") "/")
                     mount-path
                     (str "/" mount-path))]
    (start-shadow-watch! {:mount-path mount-path})
    (server.main/start! {:example :static
                         :port port
                         :mount-path mount-path})))

(defn stop!
  "Stops HTTP server and shadow-cljs watch."
  []
  (server.main/stop!)
  (stop-shadow-watch!))

(comment
  (start!)
  (start! {:port 3000 :mount-path "/map"})
  (start! {:mount-path "/globo"})
  (go!)
  (stop!))
