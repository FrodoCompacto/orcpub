(ns orcpub.core
  (:require [orcpub.character-builder :as ch]
            [orcpub.dnd.e5.subs]
            [orcpub.dnd.e5.equipment-subs]
            [orcpub.dnd.e5.events :as events]
            [orcpub.dnd.e5.autosave-fx :as autosave-fx]
            [orcpub.dnd.e5.views :as views]
            [orcpub.dnd.e5.views-2 :as views-2]
            [orcpub.dnd.e5.views.conflict-resolution :as conflict-views]
            [orcpub.route-map :as routes]
            [cljs-http.client :as http]
            [clojure.string :as s]
            [re-frame.core :refer [dispatch dispatch-sync subscribe]]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [goog.events])
  (:import
   [goog.history Html5History EventType]))

(enable-console-print!)

(when (and js/window.location
           (not (or (s/starts-with? js/window.location.href "https")
                    (s/starts-with? js/window.location.href "http://localhost"))))
  (set! js/window.location.protocol "https"))

(dispatch-sync [:initialize-db])

;; Init template cache after all subscription handlers are registered.
;; Must be called here (not self-initializing) so equipment-subs has loaded.
(autosave-fx/init-template-cache!)

(def legacy-auth-page-routes
  #{routes/login-page-route routes/register-page-route})

(def pages
  {nil views-2/splash-page
   routes/default-route views-2/splash-page
   routes/dnd-e5-orcacle-page-route views/orcacle-page
   routes/dnd-e5-char-builder-route ch/character-builder
   routes/dnd-e5-newb-char-builder-route views/newb-character-builder-page
   routes/dnd-e5-char-list-page-route views/character-list
   routes/dnd-e5-monster-list-page-route views/monster-list
   routes/dnd-e5-spell-list-page-route views/spell-list
   routes/dnd-e5-spell-builder-page-route views/spell-builder-page
   routes/dnd-e5-monster-builder-page-route views/monster-builder-page
   routes/dnd-e5-encounter-builder-page-route views/encounter-builder-page
   routes/dnd-e5-combat-tracker-page-route views/combat-tracker-page
   routes/dnd-e5-background-builder-page-route views/background-builder-page
   routes/dnd-e5-race-builder-page-route views/race-builder-page
   routes/dnd-e5-subrace-builder-page-route views/subrace-builder-page
   routes/dnd-e5-subclass-builder-page-route views/subclass-builder-page
   routes/dnd-e5-class-builder-page-route views/class-builder-page
   routes/dnd-e5-feat-builder-page-route views/feat-builder-page
   routes/dnd-e5-language-builder-page-route views/language-builder-page
   routes/dnd-e5-invocation-builder-page-route views/invocation-builder-page
   routes/dnd-e5-boon-builder-page-route views/boon-builder-page
   routes/dnd-e5-selection-builder-page-route views/selection-builder-page
   routes/dnd-e5-item-list-page-route views/item-list
   routes/dnd-e5-char-page-route views/character-page
   routes/dnd-e5-monster-page-route views/monster-page
   routes/dnd-e5-spell-page-route views/spell-page
   routes/dnd-e5-item-page-route views/item-page
   routes/dnd-e5-item-builder-page-route views/item-builder-page
   routes/dnd-e5-char-parties-page-route views/parties
   routes/dnd-e5-my-content-route views/my-content-page
   routes/my-account-page-route views/my-account-page
   routes/verify-failed-route views/verify-failed
   routes/verify-success-route views/verify-success
   routes/verify-sent-route views/verify-sent
   routes/send-password-reset-page-route views/send-password-reset-page
   routes/password-reset-sent-route views/password-reset-sent
   routes/reset-password-page-route views/password-reset-page
   routes/password-reset-success-route views/password-reset-success
   routes/password-reset-expired-route views/password-reset-expired-page
   routes/password-reset-used-route views/password-reset-used-page
   routes/unsubscribe-success-route views/unsubscribe-success})

(defn handle-url-change [_]
  (let [path (when js/window.location js/window.location.pathname)
        route (when path (routes/match-route path))
        handler (:handler route)
        route (if (legacy-auth-page-routes handler)
                (do (when js/window.history
                      (.replaceState js/window.history {} nil (routes/path-for routes/default-route)))
                    (routes/match-route (routes/path-for routes/default-route)))
                route)
        config {:skip-path? true}]
    (dispatch [:route route (if (events/login-routes (:handler route))
                              (merge
                               config
                               {:no-return? true})
                              config)])))

(defn make-history []
  (doto (Html5History.)
    (.setPathPrefix (str js/window.location.protocol
                         "//"
                         js/window.location.host))
    (.setUseFragment false)))

(defonce history (doto (make-history)
                   (goog.events/listen EventType.NAVIGATE
                                       handle-url-change)
                   (.setEnabled true)))

(defn query-map [query-str]
  (into
   {}
   (map
    (fn [[_ _ k v]]
      [k v])
    (re-seq #"((\w+)=(\w+))+" query-str))))

(defn main-view []
  (let [{:keys [handler route-params] :as route} @(subscribe [:route])
        auth-error? @(subscribe [:auth-error])
        view (if auth-error?
               views/auth-error-page
               (pages (or handler route)))
        query-string js/window.location.search
        query-map (query-map query-string)]
    [:div
     [view (assoc route-params :query query-map)]
     [conflict-views/import-log-overlay]]))

;; Bootstrap auth on startup (Cloudflare Access JWT or dev auth)
(dispatch-sync [:bootstrap-auth])

;; React 18 createRoot API (Reagent 2.0)
(defonce root (rdc/create-root (js/document.getElementById "app")))

(rdc/render root
            (if (let [doc-style js/document.documentElement.style]
                  (and js/window.localStorage
                       (or (aget doc-style "flexWrap")
                           (aget doc-style "WebkitFlexWrap")
                           (aget doc-style "msFlexWrap"))))
              [main-view]
              [:div
               [views/app-header]
               [:div.f-s-24.main-text-color.sans
                {:style {:padding "200px"}}
                "Sorry, we are unable to support your browser since it does not support important HTML5 features. Please try a modern browser such as " [:a {:href "https://www.google.com/chrome/browser/desktop/index.html"} "Google Chrome"] " or " [:a {:href "https://www.mozilla.org/en-US/firefox/products/?v=a"} "Mozilla Firefox"]]]))

