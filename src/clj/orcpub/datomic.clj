(ns orcpub.datomic
  "Datomic database component with connection management and error handling.

  Provides a component that manages the database connection lifecycle,
  including database creation, connection establishment, and schema initialization.
  All operations include error handling with clear error messages."
  (:require [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [clojure.string :as str]
            [orcpub.db.schema :as schema]))

(defn- redact-uri-query [uri]
  (when uri
    (str/replace uri #"(?i)\?.*$" "?[REDACTED]")))

(defrecord DatomicComponent [uri conn]
  component/Lifecycle
  (start [this]
    (if (:conn this)
      this
      (do
        (when (nil? uri)
          (throw (ex-info "Database URI is required but not configured"
                          {:error :missing-db-uri})))

        (let [safe-uri (redact-uri-query uri)]
          (try
            (println "Creating/connecting to Datomic database:" safe-uri)
            (d/create-database uri)

            (let [connection (d/connect uri)]
              @(d/transact connection schema/all-schemas)
              (println "Successfully initialized database schema")
              (assoc this :conn connection))
            (catch Exception e
              (throw (ex-info "Datomic initialization failed. Check the transactor and database configuration."
                              {:error :db-init-failed
                               :uri safe-uri
                               :cause-class (.getName (class e))}))))))))
  (stop [this]
    (assoc this :conn nil)))

(defn new-datomic [uri]
  (map->DatomicComponent {:uri uri}))
