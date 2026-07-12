(ns is.galt.globo.server.sse
  (:require
   [cheshire.core :as json]
   [org.httpkit.server :as hk-server]))

(defn sse-event
  "Format an SSE message. If event-name is given, emits `event:` line too.
   Data is JSON-encoded."
  ([data]
   (str "data: " (json/generate-string data) "\n\n"))
  ([event-name data]
   (str "event: " event-name "\n"
        "data: " (json/generate-string data) "\n\n")))

(defn send!
  [target-clients data]
  (doseq [ch target-clients]
    (hk-server/send! ch (sse-event data) false))
  true)
