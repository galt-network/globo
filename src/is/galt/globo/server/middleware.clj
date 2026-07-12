(ns is.galt.globo.server.middleware
  "Ring middleware for the shadow-cljs example server.
  Includes static-file serving, error handling, and permanent
  user-identification cookie assignment."
  (:require
   [ring.util.codec :as codec]
   [ring.util.request :as request]
   [ring.util.response :as response]))

(defn wrap-public-files
  "Serve static files from the public/ directory before falling through
  to the wrapped handler. / resolves to public/index.html."
  [handler]
  (fn [req]
    (if-let [resp (and (#{:head :get} (:request-method req))
                       (response/file-response
                        (subs (codec/url-decode (request/path-info req)) 1)
                        {:root "public"}))]
      (assoc-in resp [:headers "Cache-Control"] "no-cache, no-store, must-revalidate")
      (handler req))))

(defn wrap-error-response
  [handler]
  (fn [req]
    (try (handler req)
         (catch Exception ex
           {:status 500
            :headers {"Content-Type" "text/plain"}
            :body (str "Oops, something went wrong\n" (.getMessage ex))}))))

;; ---------------------------------------------------------------------------
;; Permanent user-identification cookie
;; ---------------------------------------------------------------------------

(def ^:const cookie-name "user-id")

(def ^:const permanent-max-age
  "Cookie max-age in seconds (~10 years)."
  315360000)

(def ^:const sse-response-key
  "Marker keyword placed on SSE responses so wrap-user-id skips its
  response-side cookie assoc (the SSE handler sets Set-Cookie manually
  because http-kit sends headers via hk-server/send! inside :init)."
  ::sse-response)

(defn user-id-cookie
  "Returns the cookie map consumed by ring.middleware.cookies/wrap-cookies
  when serializing the outgoing Set-Cookie header."
  [user-id]
  {:value user-id
   :max-age permanent-max-age
   :path "/"
   :http-only true
   :same-site :lax})

(defn set-cookie-header-value
  "Returns a Set-Cookie header string for the given user-id.
  Used by SSE handlers that send the initial response manually via
  http-kit's hk-server/send!, bypassing ring.middleware.cookies."
  [user-id]
  (format "%s=%s; Max-Age=%d; Path=/; HttpOnly; SameSite=Lax"
          cookie-name user-id permanent-max-age))

(defn mark-sse-response
  "Marks a Ring response as an SSE response so wrap-user-id does not
  add a :cookies map (the SSE handler sets Set-Cookie manually)."
  [resp]
  (assoc resp sse-response-key true))

(defn wrap-user-id
  "Middleware that assigns a permanent user-identifying cookie to every
  request.

  Reads an existing user-id from the parsed :cookies map on the request
  (populated by ring.middleware.cookies/wrap-cookies, which must wrap
  this middleware). If none is present, generates a random UUID.

  Associates :user-id onto the request so downstream handlers can use it
  as a server-issued identity.

  On the response, adds a :cookies entry so wrap-cookies serializes a
  Set-Cookie header. Skipped when the response is nil or marked with
  sse-response-key (SSE handlers set the header manually).

  Must be placed INSIDE ring.middleware.cookies/wrap-cookies in the
  middleware stack."
  [handler]
  (fn [req]
    (let [existing (get-in req [:cookies cookie-name :value])
          user-id (or existing (str (java.util.UUID/randomUUID)))
          resp (handler (assoc req :user-id user-id))]
      (cond
        (nil? resp) nil
        (sse-response-key resp) resp
        :else (assoc-in resp
                        [:cookies cookie-name]
                        (user-id-cookie user-id))))))
