(ns orcpub.fork.user-homebrew
  "Persist browser homebrew plugins (Option Sources) per user account."
  (:require [clojure.edn :as edn]
            [clojure.spec.alpha :as spec]
            [datomic.api :as d]
            [orcpub.dnd.e5 :as e5]))

(defn plugins-have-content?
  "True when plugins contains at least one homebrew item (not only empty shells)."
  [plugins]
  (boolean
   (some (fn [plugin-map]
           (some (fn [[k v]]
                   (and (keyword? k)
                        (not= k :disabled?)
                        (map? v)
                        (seq v)))
                 plugin-map))
         (vals plugins))))

(defn- parse-stored-plugins [s]
  (when (seq s)
    (edn/read-string s)))

(defn- serialize-plugins [plugins]
  (pr-str plugins))

(defn- homebrew-response [user]
  (let [stored (:orcpub.user/homebrew-plugins user)
        plugins (parse-stored-plugins stored)]
    {:plugins (when (plugins-have-content? plugins) plugins)
     :updated (:orcpub.user/homebrew-plugins-updated user)}))

(defn get-homebrew
  [{:keys [db identity]} find-user]
  (let [username (:user identity)
        user (find-user db username)]
    (if (:db/id user)
      {:status 200 :body (homebrew-response user)}
      {:status 400 :body {:error :user-not-found}})))

(defn put-homebrew
  [{:keys [conn identity transit-params]} find-user]
  (let [username (:user identity)
        user (find-user (d/db conn) username)
        plugins (:plugins transit-params)]
    (cond
      (nil? (:db/id user))
      {:status 400 :body {:error :user-not-found}}

      (not (plugins-have-content? plugins))
      {:status 400 :body {:error :empty-plugins}}

      (some? (spec/explain-data ::e5/plugins plugins))
      {:status 400 :body {:error :invalid-plugins
                          :details (spec/explain-data ::e5/plugins plugins)}}

      :else
      (do
        @(d/transact conn [{:db/id (:db/id user)
                            :orcpub.user/homebrew-plugins (serialize-plugins plugins)
                            :orcpub.user/homebrew-plugins-updated (java.util.Date.)}])
        {:status 200 :body (homebrew-response (d/entity (d/db conn) (:db/id user)))}))))
