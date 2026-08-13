(ns is.galt.globo.ui.user-name
  "Pure helpers for the settings-panel username save flow.")

(defn clamp-name
  "Truncate `name` to at most `max` characters (nil-safe)."
  [max name]
  (when (string? name)
    (subs name 0 (min max (count name)))))

(defn name-unchanged?
  "True when the draft name equals the current server-side name."
  [current draft]
  (= (or current "") (or draft "")))

(defn save-error-from-response
  "Extract {:error string :details map} from a fetch-fx failure response
  (the 409 body of a rejected :update-user POST), or nil when the
  response carries no server error (success, or a network problem)."
  [response]
  (let [body (or (:body response) response)]
    (when (and (map? body) (string? (:error body)))
      {:error (:error body)
       :details (:details body)})))
