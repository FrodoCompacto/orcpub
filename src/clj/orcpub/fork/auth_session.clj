(ns orcpub.fork.auth-session
  "Cloudflare Access / dev auth session bootstrap handlers."
  (:require [clojure.string :as s]
            [orcpub.fork.auth :as auth]
            [orcpub.fork.cloudflare-access :as cf-access]
            [orcpub.fork.session :as session]
            [orcpub.fork.user-provision :as user-provision]))

(defn legacy-auth-disabled [_]
  {:status 403
   :body {:message "Password login and registration are disabled. Sign in via Cloudflare Access."
          :error :legacy-auth-disabled}})

(defn- auth-session-error-response [e]
  (let [data (ex-data e)
        err (or (:error data) :invalid-cf-access)]
    {:status 401 :body {:error err}}))

(defn handle-auth-session
  "Bootstrap app session from Cloudflare Access JWT or dev auth email."
  [{:keys [db conn] :as request}]
  (try
    (case auth/auth-mode
      :cloudflare
      (let [token (cf-access/jwt-from-request request)
            {:keys [email]} (cf-access/verify-jwt token)
            user (user-provision/find-or-create-user-by-email! conn db email)]
        (session/create-app-session-response db conn user (:db/id user)))

      :dev
      (if (s/blank? auth/dev-auth-email)
        {:status 500
         :body {:error :dev-auth-misconfigured
                :message "DEV_AUTH_EMAIL must be set when AUTH_MODE=dev"}}
        (let [user (user-provision/find-or-create-user-by-email! conn db auth/dev-auth-email)]
          (session/create-app-session-response db conn user (:db/id user))))

      :legacy
      {:status 404
       :body {:error :auth-session-unavailable
              :message "Use POST /login when AUTH_MODE=legacy"}})
    (catch Exception e
      (auth-session-error-response e))))
