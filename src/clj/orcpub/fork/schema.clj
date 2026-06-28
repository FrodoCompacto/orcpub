(ns orcpub.fork.schema
  "Fork-only Datomic schema attributes. Concatenated into all-schemas via db/schema.clj.")

(def user-schema
  [{:db/ident :orcpub.user/homebrew-plugins
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :orcpub.user/homebrew-plugins-updated
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])
