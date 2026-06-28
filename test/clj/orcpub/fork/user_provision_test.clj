(ns orcpub.fork.user-provision-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [datomock.core :as dm]
            [environ.core :as environ]
            [orcpub.db.schema :as schema]
            [orcpub.fork.auth :as auth]
            [orcpub.fork.auth-session :as auth-session]
            [orcpub.fork.user-provision :as provision])
  (:import [java.util UUID]))

(defmacro with-conn [conn-binding & body]
  `(let [uri# (str "datomic:mem:orcpub-provision-" (UUID/randomUUID))
         ~conn-binding (do
                         (d/create-database uri#)
                         (d/connect uri#))]
     (try ~@body
          (finally (d/delete-database uri#)))))

(deftest find-or-create-user-by-email
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      (with-redefs [auth/dev-auth-username nil]
        (testing "creates verified user with sanitized username"
          (let [db (d/db mocked-conn)
                user (provision/find-or-create-user-by-email! mocked-conn db "New.User@example.com")]
            (is (= "newuser" (:orcpub.user/username user)))
            (is (= "new.user@example.com" (:orcpub.user/email user)))
            (is (:orcpub.user/verified? user))))
        (testing "returns existing user on second call"
          (let [db (d/db mocked-conn)
                first-user (provision/find-or-create-user-by-email! mocked-conn db "new.user@example.com")
                second-user (provision/find-or-create-user-by-email! mocked-conn db "new.user@example.com")]
            (is (= (:db/id first-user) (:db/id second-user)))))
        (testing "uses preferred username when available"
          (with-redefs [auth/dev-auth-username "custom"]
            (let [db (d/db mocked-conn)
                  user (provision/find-or-create-user-by-email! mocked-conn db "other@example.com")]
              (is (= "custom" (:orcpub.user/username user)))))))))

(deftest username-collision-suffix
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      @(d/transact mocked-conn [{:orcpub.user/username "player"
                                 :orcpub.user/email "existing@example.com"
                                 :orcpub.user/verified? true}])
      (with-redefs [auth/dev-auth-username nil]
        (let [db (d/db mocked-conn)
              user (provision/find-or-create-user-by-email! mocked-conn db "player@example.com")]
          (is (= "player1" (:orcpub.user/username user))))))))

(deftest auth-session-dev-mode
  (with-conn conn
    (let [mocked-conn (dm/fork-conn conn)]
      @(d/transact mocked-conn schema/all-schemas)
      (with-redefs [auth/auth-mode :dev
                    auth/dev-auth-email "dev@test.com"
                    auth/dev-auth-username "devuser"
                    auth/track-last-login? false
                    environ/env (fn [k]
                                  (when (= k :signature)
                                    "test-secret-key-long-enough-for-jwt"))]
        (let [db (d/db mocked-conn)
              response (auth-session/handle-auth-session {:db db :conn mocked-conn :headers {}})]
          (is (= 200 (:status response)))
          (is (string? (get-in response [:body :token])))
          (is (= "devuser" (get-in response [:body :user-data :username]))))))))
