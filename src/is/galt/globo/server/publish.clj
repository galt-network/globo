(ns is.galt.globo.server.publish
  "Outbound SSE publishing: target resolution, validation and delivery.

  publish! is the single choke point every SSE event passes through, so
  every outbound event is validated and any invalid event is logged and
  replaced by a :system-notification to the same recipients."
  (:require
   [clojure.set :as set]
   [is.galt.globo.protocols :as protocols]
   [is.galt.globo.server.sse :as sse]
   [is.galt.globo.server.validation :as validation]))

(defn resolve-target-ids
  "Resolve a publish target to a set of connection-ids.

  `deps` is {:globo Globo :user-id string?} where user-id is only needed
  for the :sender and :all-but-sender forms.

  Accepts:
    :everybody        - all open connections
    :sender           - the connections of user-id
    :all-but-sender   - all open connections except those of user-id
    a collection of connection-ids
    (fn [registry] coll) - a host-defined filter over
                           {connection-id channel} returning connection-ids"
  [{:keys [globo user-id]} target]
  (let [registry (protocols/registry (:connections globo))
        everybody (into #{} (keys registry))
        sender-ids (protocols/connection-ids-for-user (:storage globo) user-id)]
    (cond
      (= target :everybody) everybody
      (= target :sender) sender-ids
      (= target :all-but-sender) (set/difference everybody sender-ids)
      (fn? target) (into #{} (target registry))
      :else (into #{} target))))

(defn publish!
  "Validate `event` and send it to the connections selected by `target`
  (see resolve-target-ids; without a user-id the :sender/:all-but-sender
  forms resolve to the empty set / everybody, so hosts should pass
  explicit connection-ids, :everybody, or a filter fn).

  Invalid events are logged and replaced by a :system-notification to the
  same recipients. Returns true when an event reached at least one
  client."
  [{:keys [connections log-fn] :as globo} target event]
  (let [channels (protocols/channels-for
                  connections
                  (resolve-target-ids {:globo globo} target))]
    (if-let [errors (validation/outbound-errors event)]
      (do
        (log-fn "[globo] invalid SSE event dropped:" {:event event :errors errors})
        (if (= :system-notification (:type event))
          false
          (sse/send! channels (validation/system-notification :error event))))
      (sse/send! channels event))))
