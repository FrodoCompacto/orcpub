(ns orcpub.fork.homebrew-sync
  "Sync browser homebrew plugins (Option Sources) with per-user account storage."
  (:require [orcpub.dnd.e5 :as e5]
            [orcpub.dnd.e5.event-utils :as event-utils]
            [orcpub.route-map :as routes]
            [re-frame.core :refer [reg-event-db reg-event-fx reg-sub dispatch subscribe]]))

(def ^:private url-for-route event-utils/url-for-route)
(def ^:private authorization-headers event-utils/auth-headers)

(defn plugins-have-content?
  [plugins]
  (boolean
   (some (fn [plugin-map]
           (some (fn [[k v]]
                   (and (keyword? k)
                        (not= k :disabled?)
                        (map? v)
                        (seq v)))
                 plugin-map))
         (vals plugins))))

(defn- remote-has-content? [body]
  (plugins-have-content? (:plugins body)))

(defn- hide-modal-db [db]
  (assoc db :homebrew-sync-modal {:active? false}))

(defn- show-modal-db [db mode remote-plugins]
  (assoc db :homebrew-sync-modal
         {:active? true
          :mode mode
          :remote-plugins remote-plugins}))

(reg-sub
 ::modal-active?
 (fn [db _]
   (boolean (get-in db [:homebrew-sync-modal :active?]))))

(reg-sub
 ::modal
 (fn [db _]
   (:homebrew-sync-modal {:active? false})))

(reg-event-db
 ::cancel-modal
 (fn [db _]
   (hide-modal-db db)))

(reg-event-fx
 ::click-save-to-account
 (fn [{:keys [db]} _]
   (if-not (plugins-have-content? (:plugins db))
     {:dispatch [:show-error-message
                "No Option Source content in your browser to save."]}
     {:http {:method :get
             :headers (authorization-headers db)
             :url (url-for-route routes/user-homebrew-route)
             :on-success [::save-checked-remote]
             :on-failure [::sync-failure :save]}})))

(reg-event-fx
 ::save-checked-remote
 (fn [{:keys [db]} [_ response]]
   (let [body (:body response)]
     (if (remote-has-content? body)
       {:db (show-modal-db db :save (:plugins body))}
       {:dispatch [::confirm-save-to-account]}))))

(reg-event-fx
 ::confirm-save-to-account
 (fn [{:keys [db]} _]
   {:db (hide-modal-db db)
    :http {:method :put
           :headers (authorization-headers db)
           :url (url-for-route routes/user-homebrew-route)
           :transit-params {:plugins (:plugins db)}
           :on-success [::save-to-account-success]
           :on-failure [::sync-failure :save]}}))

(reg-event-fx
 ::save-to-account-success
 (fn [_ _]
   {:dispatch [:show-warning-message
               "Option Sources saved to your account."
               5000]}))

(reg-event-fx
 ::click-load-from-account
 (fn [{:keys [db]} _]
   {:http {:method :get
           :headers (authorization-headers db)
           :url (url-for-route routes/user-homebrew-route)
           :on-success [::load-checked-remote]
           :on-failure [::sync-failure :load]}}))

(reg-event-fx
 ::load-checked-remote
 (fn [{:keys [db]} [_ response]]
   (let [remote (:plugins (:body response))]
     (cond
       (not (plugins-have-content? remote))
       {:dispatch [:show-error-message
                   "No content saved in your account yet."]}

       (plugins-have-content? (:plugins db))
       {:db (show-modal-db db :load remote)}

       :else
       {:dispatch-n [[:orcpub.dnd.e5/set-plugins remote]
                     [:show-warning-message
                      "Option Sources loaded from your account."
                      5000]]}))))

(reg-event-fx
 ::load-replace
 (fn [{:keys [db]} _]
   (let [remote (get-in db [:homebrew-sync-modal :remote-plugins])]
     {:db (hide-modal-db db)
      :dispatch-n [[:orcpub.dnd.e5/set-plugins remote]
                   [:show-warning-message
                    "Option Sources loaded from your account (replaced local content)."
                    5000]]})))

(reg-event-fx
 ::load-merge
 (fn [{:keys [db]} _]
   (let [local (:plugins db)
         remote (get-in db [:homebrew-sync-modal :remote-plugins])
         merged (e5/merge-all-plugins local remote)]
     {:db (hide-modal-db db)
      :dispatch-n [[:orcpub.dnd.e5/set-plugins merged]
                   [:show-warning-message
                    "Option Sources merged from your account."
                    5000]]})))

(reg-event-fx
 ::sync-failure
 (fn [_ [_ op response]]
   (let [error (-> response :body :error)]
     {:dispatch [:show-error-message
                 (case error
                   :empty-plugins "No Option Source content to save."
                   :invalid-plugins "Content failed validation and could not be saved."
                   (str "Could not "
                        (name op)
                        " account content. Please try again."))]})))

(def ^:private community-content-url
  "https://docs.google.com/spreadsheets/d/129gwuo7c2STrgnNs82KDIN3FcxKccRafOk4ykO_imak/edit?usp=sharing")

(def ^:private account-action-classes "account-action-button m-r-10 m-b-10")

(defn buttons []
  [:<>
   [:a {:class account-action-classes
        :href community-content-url
        :target "_blank"
        :rel "noopener noreferrer"}
    "Community Content"]
   (when @(subscribe [:username])
     [:<>
      [:button {:class account-action-classes
                :on-click #(dispatch [::click-save-to-account])}
       "Save to Account"]
      [:button {:class account-action-classes
                :on-click #(dispatch [::click-load-from-account])}
       "Load from Account"]])])

(defn modal []
  (when @(subscribe [::modal-active?])
    (let [{:keys [mode]} @(subscribe [::modal])]
      [:div.p-20.flex.justify-cont-end
       [:div
        (case mode
          :save
          [:div.m-b-10
           "This will overwrite Option Sources saved in your account. Continue?"]
          :load
          [:div.m-b-10
           "You have Option Sources in your browser. "
           "Load from account will change your local content."])
        [:div.flex
         [:button.form-button
          {:on-click #(dispatch [::cancel-modal])}
          "Cancel"]
         (case mode
           :save
           [:button.link-button.m-l-10
            {:on-click #(dispatch [::confirm-save-to-account])}
            "Save"]
           :load
           [:<>
            [:button.link-button.m-l-10
             {:on-click #(dispatch [::load-replace])}
             "Replace"]
            [:button.link-button.m-l-10
             {:on-click #(dispatch [::load-merge])}
             "Merge"]])]]])))
