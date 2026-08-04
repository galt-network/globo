(ns is.galt.globo.server.sse
  "SSE event formatting and sending helpers used by the globo handlers."
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
  "Send `data` (as an SSE event) to each channel in target-clients.
  Returns true when the event reached at least one client."
  [target-clients data]
  (doseq [ch target-clients]
    (hk-server/send! ch (sse-event data) false))
  (boolean (seq target-clients)))
