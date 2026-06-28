(ns orcpub.fork.auth-views
  "Fork-only auth UI (Cloudflare Access bootstrap error page)."
  (:require [re-frame.core :refer [subscribe dispatch]]))

(def ^:private orange "#f0a100")

(defn auth-error-page []
  (let [error @(subscribe [:auth-error])]
    [:div {:style {:text-align :center :padding "120px 24px"}}
     [:div {:style {:color orange
                    :font-weight :bold
                    :font-size "32px"
                    :text-transform :uppercase
                    :text-shadow "1px 2px 1px rgba(0,0,0,0.37)"}}
      "Sign-in failed"]
     [:div.m-t-20.main-text-color
      "We could not start your session. If you use Cloudflare Access, confirm you are signed in and allowed for this site."]
     (when error
       [:div.m-t-10.opacity-6 (str error)])
     [:button.form-button.m-t-30
      {:style {:height "40px" :width "174px" :font-size "16px" :font-weight "600"}
       :on-click #(do (dispatch [:clear-auth-error])
                      (dispatch [:bootstrap-auth]))}
      "Retry"]]))
