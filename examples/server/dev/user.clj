(ns user
  (:require
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
    (server.main/start! {:example :static
                         :port port
                         :mount-path mount-path})))

(defn stop!
  "Stops HTTP server and shadow-cljs watch."
  []
  (server.main/stop!))

(comment
  (start!)
  (start! {:port 3000 :mount-path "/map"})
  (start! {:mount-path "/globo"})
  (go!)
  (stop!))
