(ns is.galt.globo.server.messages
  (:require
   [clojure.set :as set]
   [clojure.string :as str]))

(defn update-user
  [{:keys [send! storage user-id connections-for]} message]
  (swap! storage update-in [:users user-id] merge (:content message))
  (send! (connections-for :all-but-sender)
         {:type :update-user
          :user-id user-id
          :content (get-in @storage [:users user-id])}))

(defn user-offline
  [{:keys [send! storage user-id connections-for]} message]
  (when (empty? (get-in @storage [:user-connections user-id]))
    (send! (connections-for :all-but-sender) (assoc-in message [:content :id] user-id))))

(defn update-map-objects
  [{:keys [send! storage user-id connections-for]} message]
  (println ">>> server/messages update-map-objects" message)
  (let [content (:content message)
        op (case (keyword (:op content))
             :add set/union
             :remove set/difference)
        updated-objects (op (get-in @storage [:map-objects]) (into #{} (:objects content)))
        message {:type :update-object :content content}]
    (swap! storage assoc-in [:map-objects] updated-objects)
    (send! (connections-for :all-but-sender) message)))

(defn connections-for
  [{:keys [storage sse-clients user-id]} target]
  (let [everybody (reduce into #{} (vals (get-in @storage [:user-connections])))
        sender (into #{} (get-in @storage [:user-connections user-id]))
        target-ids (case target
                     :everybody everybody
                     :sender sender
                     :all-but-sender (set/difference everybody sender))]
    (vals (select-keys @sse-clients target-ids))))

(defn latest-messages
  "Return up to `limit` most recent messages from storage."
  [storage & [limit]]
  (let [msgs (:messages storage)
        n (min (or limit 20) (count msgs))]
    (subvec msgs (- (count msgs) n))))

(defn- resolve-recipient-ids
  "Find user-id whose :name matches the @username prefix (case-insensitive).
   Returns user-id or nil."
  [storage username]
  (some (fn [[uid {:keys [name]}]]
          (when (and name (= (str/lower-case name) (str/lower-case username)))
            uid))
        (:users storage)))

(defn- parse-message-type
  "Check if `text` starts with @username.
   If it does and the username matches a known user, classify as :direct.
   Otherwise classify as :world."
  [storage text]
  (if-let [[_ username] (re-find #"^@(\S+)" text)]
    (if-let [user-id (resolve-recipient-ids storage username)]
      {:type :direct :target #{user-id}}
      {:type :world :target nil})
    {:type :world :target nil}))

(defn handle-new-message
  "Store a new chat message and route it to the appropriate recipients.
   :world  -> broadcast to everybody
   :direct -> send only to sender + targeted user(s)
   :entity -> broadcast to everybody (entity UI not built yet)"
  [{:keys [send! storage user-id connections-for sse-clients]} message]
  (let [content (:content message)
        text (:text content)
        viewport (:viewport content)
        user-name (get-in @storage [:users user-id :name])
        {:keys [type target]} (parse-message-type @storage text)
        msg {:id (str (java.util.UUID/randomUUID))
             :author {:id user-id :name (or user-name "Anonymous")}
             :type type
             :target target
             :content text
             :viewport viewport
             :sent-at (str (java.time.Instant/now))
             :received-at nil
             :seen-at nil}]
    (swap! storage update :messages conj msg)
    (case type
      :direct (let [sender-ids (get-in @storage [:user-connections user-id])
                    recipient-ids (get-in @storage [:user-connections (first target)])
                    target-ids (set/union (or sender-ids #{}) (or recipient-ids #{}))
                    channels (vals (select-keys @sse-clients target-ids))]
                (send! channels {:type :new-message :content msg}))
      (send! (connections-for :everybody) {:type :new-message :content msg}))))

(defn process
  [{:keys [send!] :as params} message]
  (let [connections-for (partial connections-for params)
        deps (assoc params :connections-for connections-for)]
    (println ">>> messages/process" message)
    (case (:type message)
      :update-object (update-map-objects deps message)
      :update-user (update-user deps message)
      :user-offline (user-offline deps message)
      :user-online (send! (connections-for :everybody) message)
      :broadcast (send! (connections-for :everybody) message)
      :new-message (handle-new-message deps message)
      (println ">>> SKIPPED message with unrecognized :type" message))))
