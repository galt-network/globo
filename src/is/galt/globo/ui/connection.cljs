(ns is.galt.globo.ui.connection
  (:require
   [applied-science.js-interop :as j]
   [clojure.walk :as walk]
   [superstructor.re-frame.fetch-fx]))

(defonce event-source (atom nil))

(defn- type->kw
  [m]
  (cond
    (map? m)
    (cond-> m
      (contains? m :type) (update :type keyword)
      (contains? m :op) (update :op keyword))
    :else m))

(defn parse-event
  [data]
  (as-> data v
    (.parse js/JSON v)
    (js->clj v)
    (walk/keywordize-keys v)
    (walk/postwalk type->kw v)))

(defn setup-sse-events
  [{:keys [connection-url on-open on-error on-message]}]
  (let [es (new js/EventSource connection-url)]
    (.addEventListener es "message" #(-> (j/get % :data) parse-event on-message))
    (.addEventListener es "open" on-open)
    (.addEventListener es "error" on-error)
    (when-let [old @event-source]
      (.close old))
    (reset! event-source es)
    (println ">>> !!!! app.connection initialized!")))
