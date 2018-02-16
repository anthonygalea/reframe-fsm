(ns reframe-fsm.core
  (:require
    [ajax.core :as ajax]
    [clojure.string :as string]
    [day8.re-frame.http-fx]
    [reagent.core :as reagent]
    [re-frame.core :as rf]))

(def login-state-machine
  {nil                {:init                 :ready}
   :ready             {:login-missing-fields :fields-missing
                       :try-login            :logging-in}
   :logging-in        {:login-bad-password   :invalid-password
                       :login-no-user        :user-not-exist
                       :login-success        :logged-in}
   :fields-missing    {:no-missing-fields    :ready
                       :still-missing-fields :fields-missing}
   :user-not-exist    {:change-email         :ready}
   :invalid-password  {:change-password      :ready}})

;; -- Subscriptions -----------------------------------------------------------

(rf/reg-sub
  :state
  (fn [db _] (get db :state)))

(rf/reg-sub
  :login-failure
  (fn [db _] (rf/subscribe [:state]))
  (fn [state _] (case state
                  :user-not-exist "user not found"
                  :invalid-password "invalid password"
                  nil)))

(rf/reg-sub
  :password
  (fn [db _] (get db :password)))

(rf/reg-sub
  :email
  (fn [db _] (get db :email)))

(rf/reg-sub
  :login-disabled?
  (fn [db _] (rf/subscribe [:state]))
  (fn [state _] (not= state :ready)))

(rf/reg-sub
 :email-missing
 (fn [db _]
   [(rf/subscribe [:email])
    (rf/subscribe [:state])])
 (fn [[email state] _]
   (and (string/blank? email)
        (= state :fields-missing))))

(rf/reg-sub
 :password-missing
 (fn [db _]
   [(rf/subscribe [:password])
    (rf/subscribe [:state])])
 (fn [[password state] _]
   (and (string/blank? password)
        (= state :fields-missing))))

;; -- Events ------------------------------------------------------------------

(defn next-state
  [fsm current-state transition]
  (get-in fsm [current-state transition]))

(defn update-next-state
  [db event]
  (if-let [new-state (next-state login-state-machine (:state db) event)]
    (assoc db :state new-state)
    db))

(defn handle-next-state
  [db [event _]]
  (update-next-state db event))

(defn handle-change-email
  [{:keys [db]} [event email]]
  {:db (-> db
         (assoc :email email)
         (update-next-state event))
   :dispatch [:validate-fields]})

(defn handle-change-password
  [{:keys [db]} [event password]]
  {:db (-> db
         (assoc :password password)
         (update-next-state event))
   :dispatch [:validate-fields]})

(defn handle-login-clicked
  [{:keys [db]} _]
  (let [{:keys [email password]} db]
    {:db db
     :dispatch (if (some string/blank? [email password])
                 [:login-missing-fields]
                 [:try-login])}))

(defn handle-try-login
  [{:keys [db]} [event _]]
  (let [{:keys [email password]} db]
    {:db (update-next-state db event)
     :http-xhrio {:uri (str "/login?email=" email
                            "&password=" password)
                  :response-format (ajax/text-response-format)
                  :method :get
                  :on-success [:login-success]
                  :on-failure [:login-failure]}}))

(defn handle-login-failure
  [{:keys [db]} [_ {:keys [response]}]]
  {:db db
   :dispatch (case response
               "user not found"
               [:login-no-user]
               "invalid password"
               [:login-bad-password])})

(defn handle-validate-fields
  [{:keys [db]} _]
  (let [{:keys [email password]} db]
    {:dispatch (if (not-any? string/blank? [email password])
                [:no-missing-fields]
                [:still-missing-fields])}))

(def debug (rf/after (fn [db event]
                       (.log js/console "=======")
                       (.log js/console "state: " (str (:state db)))
                       (.log js/console "event: " (str event)))))

(def interceptors [debug])

(rf/reg-event-db :init interceptors handle-next-state)
(rf/reg-event-fx :change-email interceptors handle-change-email)
(rf/reg-event-fx :change-password interceptors handle-change-password)
(rf/reg-event-fx :login-clicked interceptors handle-login-clicked)
(rf/reg-event-fx :try-login interceptors handle-try-login)
(rf/reg-event-fx :login-failure interceptors handle-login-failure)
(rf/reg-event-db :login-no-user interceptors handle-next-state)
(rf/reg-event-db :login-bad-password interceptors handle-next-state)
(rf/reg-event-db :login-success interceptors handle-next-state)
(rf/reg-event-fx :validate-fields handle-validate-fields)
(rf/reg-event-db :login-missing-fields handle-next-state)
(rf/reg-event-db :no-missing-fields handle-next-state)
(rf/reg-event-db :still-missing-fields handle-next-state)

;; -- Rendering ---------------------------------------------------------------

(defn ui
  []
  [:div
   (when-let [failure @(rf/subscribe [:login-failure])]
     [:div {:style {:color "red"}} failure])
   [:form
    "Email" [:br]
    [:input
     {:value @(rf/subscribe [:email])
      :on-change #(rf/dispatch [:change-email (-> % .-target .-value)])}] [:br]
    (when-let [_ @(rf/subscribe [:email-missing])]
      [:div {:style {:color "red"}} "email missing"])
    "Password" [:br]
    [:input
     {:value @(rf/subscribe [:password])
      :on-change #(rf/dispatch [:change-password (-> % .-target .-value)])
      :type "password"}] [:br]
    (when-let [_ @(rf/subscribe [:password-missing])]
      [:div {:style {:color "red"}} "password missing"])
    [:input {:type "button"
             :value "Login"
             :disabled @(rf/subscribe [:login-disabled?])
             :on-click (fn [e] (rf/dispatch [:login-clicked]))}]]])

;; -- Entry Point -------------------------------------------------------------

(enable-console-print!)
(reagent/render [ui] (js/document.getElementById "app"))
(rf/dispatch [:init])

(comment
  (require '[fsmviz.core])
  (fsmviz.core/generate-image login-state-machine "fsm"))
