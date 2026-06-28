(ns orcpub.fork.session
  "App JWT session responses (Buddy token + user payload).
   Used by Cloudflare Access bootstrap and legacy password login."
  (:require [buddy.sign.jwt :as jwt]
            [datomic.api :as d]
            [environ.core :refer [env]]
            [orcpub.fork.auth :as auth]
            [orcpub.fork.user-data :as user-data]
            [orcpub.time :refer [hours from-now]]))

(defn create-token [username exp]
  (jwt/sign {:user username
             :exp exp}
            (env :signature)))

(defn following-usernames [db ids]
  (map :orcpub.user/username
       (d/pull-many db '[:orcpub.user/username] ids)))

(defn user-body
  "Build the user API response. Core fields are inline; fork-specific
   fields (e.g. tier data) are added by user-data/enrich-response."
  [db user]
  (cond-> (user-data/enrich-response
           {:username (:orcpub.user/username user)
            :email (:orcpub.user/email user)
            :send-updates? (boolean (:orcpub.user/send-updates? user))
            :following (following-usernames db (map :db/id (:orcpub.user/following user)))}
           user)
    (:orcpub.user/pending-email user)
    (assoc :pending-email (:orcpub.user/pending-email user))))

(defn create-app-session-response
  "Return HTTP 200 with app JWT and user-data map."
  [db conn user id & [headers]]
  (let [token (create-token (:orcpub.user/username user)
                            (-> auth/token-lifetime-hours hours from-now))
        now (java.util.Date.)]
    (when auth/track-last-login?
      (d/transact conn [{:db/id id
                         :orcpub.user/last-login now}]))
    {:status 200
     :headers headers
     :body {:user-data (user-body db user)
            :token token}}))

(def create-login-response create-app-session-response)
