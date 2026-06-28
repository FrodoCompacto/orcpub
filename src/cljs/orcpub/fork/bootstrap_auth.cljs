(ns orcpub.fork.bootstrap-auth
  "Cloudflare Access / dev auth bootstrap (re-frame events + subs).
   Loaded via side-effect require after :login-success is registered in events.cljs."
  (:require [cljs-http.client :as http]
            [orcpub.dnd.e5.event-utils :as event-utils]
            [orcpub.route-map :as routes]
            [re-frame.core :refer [reg-event-db reg-event-fx reg-sub dispatch]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def ^:private url-for-route event-utils/url-for-route)
(def ^:private authorization-headers event-utils/auth-headers)

(reg-sub
 :auth-error
 (fn [db _]
   (:auth-error db)))

(reg-event-fx
 :verify-user-session
 (fn [{:keys [db]} _]
   (if (:token (:user-data db))
     (do (go (let [response (<! (http/get (url-for-route routes/user-route)
                                          {:headers (authorization-headers db)}))]
               (case (:status response)
                 200 (dispatch [:set-loading false])
                 401 (do (dispatch [:clear-login])
                         (dispatch [:bootstrap-auth]))
                 (dispatch [:set-loading false]))))
         {})
     {:dispatch [:bootstrap-auth]})))

(reg-event-fx
 :bootstrap-auth
 (fn [{:keys [db]} _]
   (if (:token (:user-data db))
     {:dispatch [:verify-user-session]}
     (do (dispatch [:set-loading true])
         (go (let [response (<! (http/get (url-for-route routes/auth-session-route)
                                          {:with-credentials? true}))]
               (dispatch [:set-loading false])
               (if (= 200 (:status response))
                 (dispatch [:login-success false response])
                 (dispatch [:auth-bootstrap-failed response]))))
         {}))))

(reg-event-fx
 :auth-bootstrap-failed
 (fn [{:keys [db]} [_ response]]
   {:db (assoc db :auth-error (or (-> response :body :error)
                                  (-> response :status (str " http"))))
    :dispatch [:route routes/default-route]}))

(reg-event-db
 :clear-auth-error
 (fn [db _]
   (dissoc db :auth-error)))

(reg-event-fx
 :route-to-login
 (fn [_ _]
   {:dispatch [:bootstrap-auth]}))
