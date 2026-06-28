(ns orcpub.fork.auth
  "Fork-specific auth and session configuration.
   Public/community edition: short sessions, no login tracking."
  (:require [clojure.string :as s]
            [environ.core :refer [env]]
            [orcpub.fork.branding :as branding]))

(defn- env-str [k]
  (some-> (or (env k) (System/getenv (name k))) str s/trim not-empty))

(defn- parse-auth-mode []
  (case (s/lower-case (or (env-str :auth-mode)
                            (if (= "true" (s/lower-case (or (env :dev-mode) "")))
                              "dev"
                              "cloudflare")))
    "cloudflare" :cloudflare
    "dev"        :dev
    "legacy"     :legacy
    :cloudflare))

(def auth-mode
  "Authentication bootstrap mode: :cloudflare, :dev, or :legacy (password login)."
  (parse-auth-mode))

(def cf-access-enabled?
  "Production SSO via Cloudflare Access JWT."
  (= auth-mode :cloudflare))

(def dev-auth-enabled?
  "Local dev bootstrap without Cloudflare (AUTH_MODE=dev only)."
  (= auth-mode :dev))

(def cf-access-team-domain
  "Cloudflare Access team domain, e.g. arcaneinfra.cloudflareaccess.com"
  (env-str :cf-access-team-domain))

(def cf-access-aud
  "Application Audience tag from Zero Trust app settings."
  (env-str :cf-access-aud))

(def dev-auth-email
  "Email for AUTH_MODE=dev auto-login."
  (env-str :dev-auth-email))

(def dev-auth-username
  "Optional username override for AUTH_MODE=dev provisioning."
  (env-str :dev-auth-username))

;; ─── Session ────────────────────────────────────────────────────────

(def token-lifetime-hours
  "JWT token lifetime in hours."
  24)

(def track-last-login?
  "Whether to record last-login timestamp on each login."
  false)

(def record-last-login-at-registration?
  "Whether to set initial last-login when a user registers."
  false)

;; ─── Display ────────────────────────────────────────────────────────

(def verification-display-name
  "Name shown in verification and password-reset emails."
  "User")
