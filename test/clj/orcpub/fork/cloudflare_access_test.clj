(ns orcpub.fork.cloudflare-access-test
  (:require [buddy.core.keys :as keys]
            [buddy.sign.jwt :as jwt]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [orcpub.fork.auth :as auth]
            [orcpub.fork.cloudflare-access :as cf-access])
  (:import (java.util UUID)))

(def test-team "testteam.cloudflareaccess.com")
(def test-aud "test-application-audience-tag")

(def ^:private test-keypair (keys/generate-keypair :rsa 2048))
(def ^:private test-private-key (.getPrivate test-keypair))
(def ^:private test-public-key (.getPublic test-keypair))

(defn- sign-test-token
  [claims]
  (jwt/sign claims test-private-key {:alg :rs256}))

(use-fixtures :each
  (fn [f]
    (cf-access/clear-cert-cache!)
    (cf-access/set-public-keys-for-test! [test-public-key])
    (with-redefs [auth/cf-access-aud test-aud
                  auth/cf-access-team-domain test-team]
      (f))))

(deftest verify-jwt-valid-claims
  (testing "accepts valid RS256 token with matching aud and iss"
    (let [token (sign-test-token {:email "player@example.com"
                                  :sub "player@example.com"
                                  :aud test-aud
                                  :iss (str "https://" test-team)
                                  :exp (+ (quot (System/currentTimeMillis) 1000) 3600)})
          result (cf-access/verify-jwt token)]
      (is (= "player@example.com" (:email result)))
      (is (= "player@example.com" (:identity result))))))

(deftest verify-jwt-rejects-bad-aud
  (testing "rejects audience mismatch"
    (let [token (sign-test-token {:email "player@example.com"
                                  :aud "wrong-aud"
                                  :iss (str "https://" test-team)
                                  :exp (+ (quot (System/currentTimeMillis) 1000) 3600)})]
      (is (thrown-with-msg? ExceptionInfo #"audience"
                            (cf-access/verify-jwt token))))))

(deftest verify-jwt-rejects-expired
  (testing "rejects expired token"
    (let [token (sign-test-token {:email "player@example.com"
                                  :aud test-aud
                                  :iss (str "https://" test-team)
                                  :exp (- (quot (System/currentTimeMillis) 1000) 60)})]
      (is (thrown-with-msg? ExceptionInfo #"expired"
                            (cf-access/verify-jwt token))))))

(deftest email-from-claims-fallback
  (testing "falls back to common_name when email absent"
    (is (= "legacy@example.com"
           (cf-access/email-from-claims {:common_name "legacy@example.com"})))))

(deftest jwt-from-request-header
  (testing "reads Cf-Access-Jwt-Assertion case-insensitively"
    (is (= "token-value"
           (cf-access/jwt-from-request {:headers {"cf-access-jwt-assertion" "token-value"}})))
    (is (= "token-value"
           (cf-access/jwt-from-request {:headers {"Cf-Access-Jwt-Assertion" "token-value"}})))))
