(ns orcpub.fork.user-homebrew-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [datomock.core :as dm]
            [orcpub.db.schema :as schema]
            [orcpub.fork.user-homebrew :as homebrew]
            [orcpub.routes :as routes]
            [orcpub.dnd.e5 :as e5])
  (:import [java.util UUID]))

(defmacro with-conn [conn-binding & body]
  `(let [uri# (str "datomic:mem:orcpub-homebrew-" (UUID/randomUUID))
         ~conn-binding (do
                         (d/create-database uri#)
                         (d/connect uri#))]
     (try ~@body
          (finally (d/delete-database uri#)))))

(defn sample-plugins []
  {"Test Source" {::e5/spells {:zap {:name "Zap"
                                     :key :zap
                                     :school "evocation"
                                     :level 1
                                     :option-pack "Test Source"
                                     :spell-lists {:wizard true}}}}})

(deftest plugins-have-content-predicate
  (testing "empty shells are not content"
    (is (false? (homebrew/plugins-have-content? {})))
    (is (false? (homebrew/plugins-have-content? {"Default Option Source" {}}))))
  (testing "plugin with items is content"
    (is (true? (homebrew/plugins-have-content? (sample-plugins))))))

(deftest get-homebrew-empty
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      @(d/transact mocked-conn [{:orcpub.user/username "brewer"
                                 :orcpub.user/email "brewer@test.com"}])
      (let [db (d/db mocked-conn)
            resp (homebrew/get-homebrew {:db db
                                         :identity {:user "brewer"}}
                                        routes/find-user-by-username)]
        (is (= 200 (:status resp)))
        (is (nil? (get-in resp [:body :plugins])))))))

(deftest put-get-roundtrip
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      @(d/transact mocked-conn [{:orcpub.user/username "brewer"
                                 :orcpub.user/email "brewer@test.com"}])
      (let [plugins (sample-plugins)
            put-resp (homebrew/put-homebrew {:conn mocked-conn
                                             :identity {:user "brewer"}
                                             :transit-params {:plugins plugins}}
                                            routes/find-user-by-username)
            get-resp (homebrew/get-homebrew {:db (d/db mocked-conn)
                                             :identity {:user "brewer"}}
                                            routes/find-user-by-username)]
        (is (= 200 (:status put-resp)))
        (is (= 200 (:status get-resp)))
        (is (= plugins (get-in get-resp [:body :plugins])))
        (is (some? (get-in get-resp [:body :updated])))))))

(deftest put-empty-rejected
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      @(d/transact mocked-conn [{:orcpub.user/username "brewer"
                                 :orcpub.user/email "brewer@test.com"}])
      (let [resp (homebrew/put-homebrew {:conn mocked-conn
                                         :identity {:user "brewer"}
                                         :transit-params {:plugins {"Default Option Source" {}}}}
                                        routes/find-user-by-username)]
        (is (= 400 (:status resp)))
        (is (= :empty-plugins (get-in resp [:body :error])))))))

(deftest put-invalid-rejected
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      @(d/transact mocked-conn [{:orcpub.user/username "brewer"
                                 :orcpub.user/email "brewer@test.com"}])
      (let [resp (homebrew/put-homebrew {:conn mocked-conn
                                         :identity {:user "brewer"}
                                         :transit-params {:plugins {"Bad" {:not-a-valid-key "x"}}}}
                                        routes/find-user-by-username)]
        (is (= 400 (:status resp)))
        (is (= :invalid-plugins (get-in resp [:body :error]))))))
