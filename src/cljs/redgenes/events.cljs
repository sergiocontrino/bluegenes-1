(ns redgenes.events
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [re-frame.core :as re-frame :refer [reg-event-db reg-fx reg-event-fx dispatch subscribe]]
            [redgenes.events.boot]
            [day8.re-frame.http-fx]
            [day8.re-frame.forward-events-fx]
            [day8.re-frame.async-flow-fx]
            [redgenes.sections.reportpage.handlers]
            [redgenes.components.search.events]
            [redgenes.components.databrowser.events]
            [redgenes.components.navbar.events]
            [redgenes.components.search.events :as search-full]
            [redgenes.sections.reportpage.handlers]
            [redgenes.effects]
            [redgenes.persistence :as persistence]
            [imcljsold.search :as search]
            [cljs.core.async :refer [put! chan <! >! timeout close!]]
            [imcljs.fetch :as fetch]))

; Change the main panel to a new view
(reg-event-fx
  :do-active-panel
  (fn [{db :db} [_ active-panel panel-params evt]]
    (cond-> {:db (assoc db
                   :active-panel active-panel
                   :panel-params panel-params)}
            evt (assoc :dispatch evt))))

; A buffer between booting and changing the view. We only change the view
; when the assets have been loaded
(reg-event-fx
  :set-active-panel
  (fn [{db :db} [_ active-panel panel-params evt]]
    (cond-> {:db db}
            (:fetching-assets? db) ; If we're fetching assets then save the panel change for later
            (assoc :forward-events {:register    :coordinator1
                                    :events      #{:finished-loading-assets}
                                    :dispatch-to [:do-active-panel active-panel panel-params evt]})
            (not (:fetching-assets? db)) ; Otherwise dispatch it now (and the optional attached event)
            (assoc :dispatch-n
                   (cond-> [[:do-active-panel active-panel panel-params evt]]
                           evt (conj evt))))))

(reg-event-fx
 :save-state
 (fn [{:keys [db]}]
   ;;So this saves assets and current mine to the db. We don't do any complex caching right now - every boot or mine change, these will be loaded afresh and applied on top. It *does* mean that the assets can be used before they are loaded.
   ;;why isn't there caching? because it gets very complex deciding what and when to expire, so it's not really a minimum use case feature.
   (let [saved-keys (select-keys db [:current-mine :mines :assets])]
    (.log js/console "%cSaving state: " "background-color:darkseagreen;font-weight:bold;border-radius:2px" (clj->js saved-keys))
    (persistence/persist! saved-keys)
   {:db db}
   )))

(reg-event-fx
  :set-active-mine
  (fn [{:keys [db]} [_ value keep-existing?]]
    {:db         (cond-> (assoc db :current-mine value)
                         (not keep-existing?) (assoc-in [:assets] {}))
     :dispatch-n (list [:reboot] [:set-active-panel :home-panel] )
     :visual-navbar-minechange []}))

(reg-event-db
  :async-assoc
  (fn [db [_ location-vec val]]
    (assoc-in db location-vec val)))

(reg-event-db
  :log-out
  (fn [db]
    (dissoc db :who-am-i)))

(reg-event-db
  :handle-suggestions
  (fn [db [_ results]]
    (assoc db :suggestion-results results)))



(reg-event-fx
  :bounce-search
  (fn [{db :db} [_ term]]
    (let [connection   (get-in db [:mines (get db :current-mine) :service])
          suggest-chan (search/quicksearch connection term 5)]
      (if-let [c (:search-term-channel db)] (close! c))
      {:db      (-> db
                    (assoc :search-term-channel suggest-chan)
                    (assoc :search-term term))
       :suggest {:c suggest-chan :search-term term :source (get db :current-mine)}})))

(reg-event-fx
  :add-toast
  (fn [db [_ message]]
    (update-in db [:toasts] conj message)))

(reg-event-db
  :test-progress-bar
  (fn [db [_ percent]]
    (assoc db :progress-bar-percent percent)))

(reg-event-db
  :cache/store-organisms
  (fn [db [_ res]]
    (assoc-in db [:cache :organisms] (:results res))))

(reg-event-fx
  :cache/fetch-organisms
  (fn [{db :db}]
    (let [model          (get-in db [:assets :model])
          organism-query {:from   "Organism"
                          :select ["name"
                                   "taxonId"
                                   "species"
                                   "shortName"
                                   "genus"
                                   "commonName"]}]
      {:db           db
       :im-operation {:op         (partial search/raw-query-rows
                                           (get-in db [:mines (:current-mine db) :service])
                                           organism-query
                                           {:format "jsonobjects"})
                      :on-success [:cache/store-organisms]}})))
