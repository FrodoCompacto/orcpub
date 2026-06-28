(ns orcpub.fork.cloudflare-access
  "Validate Cloudflare Access JWTs from Cf-Access-Jwt-Assertion.
   Never trust CF-Access-Authenticated-User-Email without cryptographic verification."
  (:require [buddy.sign.jwt :as jwt]
            [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [orcpub.fork.auth :as auth])
  (:import (java.io ByteArrayInputStream)
           (java.security.cert CertificateFactory X509Certificate)))

(def ^:private cert-cache (atom {:fetched-at 0 :certs []}))
(def ^:private cert-ttl-ms (* 60 60 1000))

(defn- certs-url []
  (str "https://" auth/cf-access-team-domain "/cdn-cgi/access/certs"))

(defn- pem->public-key [pem]
  (let [cf (CertificateFactory/getInstance "X.509")
        cert ^X509Certificate (.generateCertificate cf (ByteArrayInputStream. (.getBytes pem)))]
    (.getPublicKey cert)))

(defn- parse-public-keys [body]
  (let [data (json/read-str body :key-fn keyword)
        pems (concat (when-let [c (:public-cert data)] [c])
                     (or (:public-certs data) []))]
    (keep pem->public-key pems)))

(defn fetch-public-keys!
  "Fetch Cloudflare Access public keys (cached ~1h)."
  []
  (let [{:keys [fetched-at certs]} @cert-cache
        now (System/currentTimeMillis)]
    (if (and (seq certs) (< (- now fetched-at) cert-ttl-ms))
      certs
      (let [resp (http/get (certs-url) {:as :string
                                         :throw-exceptions false
                                         :socket-timeout 10000
                                         :connection-timeout 10000})]
        (when (not= 200 (:status resp))
          (throw (ex-info "Failed to fetch Cloudflare Access certs"
                          {:error :cf-certs-unavailable
                           :status (:status resp)})))
        (let [keys (parse-public-keys (:body resp))]
          (when (empty? keys)
            (throw (ex-info "No public keys in Cloudflare Access certs response"
                            {:error :cf-certs-empty})))
          (reset! cert-cache {:fetched-at now :certs keys})
          keys)))))

(defn- aud-matches? [aud-claim expected-aud]
  (let [auds (if (coll? aud-claim) aud-claim [aud-claim])]
    (some #(= expected-aud (str %)) auds)))

(defn- iss-valid? [iss]
  (and (string? iss)
       (str/includes? (str iss) auth/cf-access-team-domain)))

(defn email-from-claims
  "Extract email from verified JWT claims."
  [claims]
  (or (:email claims)
      (:common_name claims)
      (:common-name claims)))

(defn verify-jwt
  "Validate Cf-Access-Jwt-Assertion. Returns {:email ... :identity ... :claims ...}
   or throws ex-info with :error keyword."
  [token]
  (when (str/blank? token)
    (throw (ex-info "Missing Cloudflare Access JWT"
                    {:error :cf-access-required})))
  (when (str/blank? auth/cf-access-aud)
    (throw (ex-info "CF_ACCESS_AUD not configured"
                    {:error :cf-access-misconfigured})))
  (let [keys (fetch-public-keys!)
        claims (some (fn [pk]
                       (try
                         (jwt/verify token pk {:alg :rs256})
                         (catch Exception _ nil)))
                     keys)]
    (when-not claims
      (throw (ex-info "Invalid Cloudflare Access JWT signature"
                      {:error :invalid-cf-access})))
    (when-not (aud-matches? (:aud claims) auth/cf-access-aud)
      (throw (ex-info "Cloudflare Access JWT audience mismatch"
                      {:error :invalid-cf-access
                       :expected auth/cf-access-aud})))
    (when-not (iss-valid? (:iss claims))
      (throw (ex-info "Cloudflare Access JWT issuer mismatch"
                      {:error :invalid-cf-access
                       :iss (:iss claims)})))
    (let [exp (:exp claims)
          now (/ (System/currentTimeMillis) 1000)]
      (when (and exp (>= now exp))
        (throw (ex-info "Cloudflare Access JWT expired"
                        {:error :invalid-cf-access}))))
    (let [email (some-> (email-from-claims claims) str/trim str/lower-case)]
      (when (str/blank? email)
        (throw (ex-info "Cloudflare Access JWT missing email claim"
                        {:error :invalid-cf-access})))
      {:email email
       :identity (or (:sub claims) email)
       :claims claims})))

(defn set-public-keys-for-test!
  "Replace cached CF public keys (tests only)."
  [public-keys]
  (reset! cert-cache {:fetched-at (System/currentTimeMillis) :certs public-keys}))

(defn clear-cert-cache!
  []
  (reset! cert-cache {:fetched-at 0 :certs []}))

(defn jwt-from-request
  "Read Cf-Access-Jwt-Assertion header (case-insensitive)."
  [request]
  (let [headers (:headers request)]
    (or (get headers "cf-access-jwt-assertion")
        (get headers "Cf-Access-Jwt-Assertion"))))
