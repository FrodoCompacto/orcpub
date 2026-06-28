(ns orcpub.fork.user-provision
  "Find-or-create users for Cloudflare Access / dev auth bootstrap."
  (:require [buddy.hashers :as hashers]
            [clojure.string :as s]
            [datomic.api :as d]
            [orcpub.fork.auth :as auth]
            [orcpub.fork.user-data :as user-data]
            [orcpub.registration :as registration]))

(defn- sanitize-username-part [s]
  (when s
    (-> s
        s/lower-case
        (s/replace #"[^a-z0-9]" ""))))

(defn- username-from-email [email]
  (let [local (first (s/split email #"@"))
        base (or (sanitize-username-part local) "user")]
    (if (and (>= (count base) 3)
             (not (registration/bad-username? base)))
      base
      (str "user" (subs (str (hash email)) 1 9))))

(defn- username-taken? [db username]
  (boolean
   (d/q '[:find ?e .
          :in $ ?username
          :where [?e :orcpub.user/username ?username]]
        db username)))

(defn- unique-username [db email preferred]
  (let [candidates (concat
                    (when preferred [preferred])
                    [(username-from-email email)]
                    (map #(str (username-from-email email) %)
                         (range 1 100)))]
    (some (fn [u]
            (when (and (>= (count u) 3)
                       (not (registration/bad-username? u))
                       (not (username-taken? db u)))
              u))
          candidates)))

(defn find-user-by-email [db email]
  (let [normalized (s/lower-case (s/trim email))]
    (when normalized
      (ffirst (d/q '[:find (pull ?e [*])
                     :in $ ?email
                     :where [?e :orcpub.user/email ?stored]
                            [(clojure.string/lower-case ?stored) ?email]]
                   db normalized)))))

(defn find-or-create-user-by-email!
  "Returns Datomic user entity map (with :db/id). Creates verified user if missing."
  [conn db email]
  (let [normalized (s/lower-case (s/trim email))
        existing (find-user-by-email db normalized)]
    (if existing
      existing
      (let [username (or (unique-username db normalized auth/dev-auth-username)
                         (throw (ex-info "Could not allocate username"
                                         {:error :username-allocation-failed
                                          :email normalized})))
            random-pw (str (random-uuid) (random-uuid))
            now (java.util.Date.)
            tx [(merge {:orcpub.user/email normalized
                        :orcpub.user/username username
                        :orcpub.user/password (hashers/derive random-pw)
                        :orcpub.user/verified? true
                        :orcpub.user/send-updates? false
                        :orcpub.user/created now}
                       (user-data/registration-defaults))]]
        (let [result @(d/transact conn tx)
              id (get-in result [:tempids username])]
          (d/pull (d/db conn) '[*] id))))))
