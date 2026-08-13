(ns is.galt.globo.server.validation
  "Malli schemas for the globo message protocol, plus validation helpers.

  The schemas here serve two purposes: they are the single source of
  truth for the message protocol (documentation), and every message
  entering or leaving the server is validated against them so errors are
  caught server-side before they reach the browser."
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;; Shared sub-schemas

(def Location
  [:map [:lat number?] [:lng number?]])

(def Viewport
  [:map [:lat number?] [:lng number?] [:altitude number?]])

(def Favorite
  [:map
   [:id string?]
   [:label string?]
   [:lat [:maybe number?]]
   [:lng [:maybe number?]]])

(def MapObject
  [:map
   [:id string?]
   [:lat number?]
   [:lng number?]
   [:model-id string?]
   [:scale number?]])

(def PlaceableObject
  [:map
   [:model-id string?]
   [:path string?]
   [:scale number?]
   [:name {:optional true} [:maybe string?]]
   [:icon {:optional true} [:maybe string?]]
   [:show-in-summary? {:optional true} [:maybe boolean?]]])

(def User
  [:map
   [:id string?]
   [:name {:optional true} [:maybe string?]]
   [:location {:optional true} [:maybe Location]]
   [:favorites {:optional true} [:maybe [:vector Favorite]]]])

(def Message
  [:map
   [:id string?]
   [:author [:map
             [:id string?]
             [:name {:optional true} [:maybe string?]]]]
   [:type [:enum :world :direct :entity]]
   [:target {:optional true} [:maybe [:set string?]]]
   [:content string?]
   [:viewport {:optional true} [:maybe Viewport]]
   [:sent-at string?]
   [:received-at {:optional true} [:maybe string?]]
   [:seen-at {:optional true} [:maybe string?]]])

(def Severity
  [:enum :info :warning :error])

(def SystemNotificationContent
  [:map
   [:message string?]
   [:severity Severity]
   [:sent-at string?]
   [:event :map]
   [:target {:optional true} [:maybe [:or :keyword [:sequential string?]]]]])

;; Inbound messages (browser or host -> server)

(def InboundMessage
  [:multi {:dispatch :type}
   [:update-object
    [:map
     [:type [:= :update-object]]
     [:content [:map
                [:op [:enum :add :remove]]
                [:objects [:vector MapObject]]]]]]
   [:update-user
    [:map
     [:type [:= :update-user]]
     [:content [:map
                [:id string?]
                [:name {:optional true} [:maybe string?]]
                [:location {:optional true} [:maybe Location]]]]]]
   [:update-favorite
    [:map
     [:type [:= :update-favorite]]
     [:content [:map
                [:index int?]
                [:partial [:map
                           [:label {:optional true} [:maybe string?]]
                           [:lat {:optional true} [:maybe number?]]
                           [:lng {:optional true} [:maybe number?]]]]]]]]
   [:add-favorite
    [:map
     [:type [:= :add-favorite]]
     [:content {:optional true} [:map]]]]
   [:new-message
    [:map
     [:type [:= :new-message]]
     [:content [:map
                [:text string?]
                [:viewport {:optional true} [:maybe Viewport]]]]]]
   [:broadcast
    [:map
     [:type [:= :broadcast]]
     [:content :map]]]
   [:paint-hexhold
    [:map
     [:type [:= :paint-hexhold]]
     [:content [:map
                [:hex-id string?]
                [:color [:maybe [:enum "red" "blue" "green" "yellow" "purple"]]]]]]]
   [:hexhold-message
    [:map
     [:type [:= :hexhold-message]]
     [:content [:map
                [:hex-id string?]
                [:text string?]]]]]
   [:system-notification
    [:map
     [:type [:= :system-notification]]
     [:content SystemNotificationContent]]]])

;; Outbound events (server -> browser SSE)

(def OutboundEvent
  [:multi {:dispatch :type}
   [:connected
    [:map
     [:type [:= :connected]]
     [:content [:map
                [:connection-id string?]
                [:user-id string?]]]]]
   [:map-objects
    [:map
     [:type [:= :map-objects]]
     [:content [:map
                [:objects [:set MapObject]]]]]]
   [:users-online
    [:map
     [:type [:= :users-online]]
     [:content [:map
                [:users [:vector User]]]]]]
   [:messages
    [:map
     [:type [:= :messages]]
     [:content [:map
                [:messages [:vector Message]]]]]]
   [:update-object
    [:map
     [:type [:= :update-object]]
     [:content [:map
                [:op [:enum :add :remove]]
                [:objects [:vector MapObject]]]]]]
   [:update-user
    [:map
     [:type [:= :update-user]]
     [:user-id string?]
     [:content User]]]
   [:user-online
    [:map
     [:type [:= :user-online]]
     [:content User]]]
   [:user-offline
    [:map
     [:type [:= :user-offline]]
     [:content [:map
                [:id string?]]]]]
   [:new-message
    [:map
     [:type [:= :new-message]]
     [:content Message]]]
   [:favorite-added
    [:map
     [:type [:= :favorite-added]]
     [:user-id string?]
     [:content [:map
                [:index int?]
                [:favorite Favorite]]]]]
   [:favorite-updated
    [:map
     [:type [:= :favorite-updated]]
     [:user-id string?]
     [:content [:map
                [:index int?]
                [:favorite Favorite]]]]]
   [:placeable-map-objects
    [:map
     [:type [:= :placeable-map-objects]]
     [:content [:map
                [:objects [:vector PlaceableObject]]]]]]
   [:hexholds
    [:map
     [:type [:= :hexholds]]
     [:content [:map
                [:colors [:map-of string? [:enum :red :blue :green :yellow :purple]]]]]]]
   [:hexholds-updated
    [:map
     [:type [:= :hexholds-updated]]
     [:content [:map
                [:id string?]
                [:color [:maybe [:enum :red :blue :green :yellow :purple]]]
                [:owner-id [:maybe string?]]]]]]
   [:hexhold-message
    [:map
     [:type [:= :hexhold-message]]
     [:content [:map
                [:hex-id string?]
                [:message [:map
                           [:id string?]
                           [:author [:map
                                     [:id string?]
                                     [:name [:maybe string?]]]]
                           [:content string?]
                           [:sent-at string?]]]]]]]
   [:system-notification
    [:map
     [:type [:= :system-notification]]
     [:content SystemNotificationContent]]]])

;; Validation helpers

(defn inbound-errors
  "Humanized validation errors for an inbound message, or nil."
  [message]
  (when-let [errors (m/explain InboundMessage message)]
    (me/humanize errors)))

(defn outbound-errors
  "Humanized validation errors for an outbound event, or nil."
  [event]
  (when-let [errors (m/explain OutboundEvent event)]
    (me/humanize errors)))

(def severity-messages
  "Fixed generic user-facing messages per severity."
  {:error "Something went wrong. Please try again."
   :warning "Something unexpected happened."
   :info "Update received."})

(defn system-notification
  "Build a :system-notification event reporting `event`, with a fixed
  generic message for `severity`, or `message` when provided."
  ([severity event]
   (system-notification severity event nil))
  ([severity event message]
   {:type :system-notification
    :content {:message (or message (severity-messages severity))
              :severity severity
              :sent-at (str (java.time.Instant/now))
              :event event}}))
