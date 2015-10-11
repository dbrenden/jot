(ns jot.core
  (:require-macros [cljs.core.async.macros :refer [go]]
                   [reagent.ratom :refer [run!]]
                   [jot.macros :refer [<? dochan go-catch]])
  (:require [clojure.data :refer [diff]]
            [cljs.core.async :refer [<! chan put!]]
            [reagent.core :as reagent]
            [re-frame.core :refer [dispatch dispatch-sync subscribe]]
            [re-frame.db :refer [app-db]]
            [jot.components :as components]
            [jot.data :as data]
            [jot.dropbox :as dropbox]
            [jot.routing :as routing]
            [jot.storage :as storage]
            [jot.sync :as sync]))

(enable-console-print!)

(dispatch-sync [:initialize {:notes (or (storage/load :notes) {})}])
(routing/start-history!)
(reagent/render-component [components/app]
                          (js/document.getElementById "app"))

;; pull

(def listener (atom nil))

(defn on-change [{:keys [id deleted? text timestamp] :as note}]
  (println "(on-change)" note)
  (if deleted?
    (dispatch [:dissoc-note note])
    (dispatch [:assoc-note {:id id
                            :text text
                            :timestamp timestamp}])))

(defn cursor-changed? [prev-listener listener]
  (not= (sync/cursor prev-listener)
        (sync/cursor listener)))

(defn cursor-watcher [_ _ prev-listener listener]
  (if (cursor-changed? prev-listener listener)
    (let [cursor (sync/cursor listener)]
      (println "(cursor)" cursor)
      (storage/save! :cursor cursor))))

(defn start-syncing []
  (reset! listener (sync/listen (storage/load :cursor) on-change))
  (add-watch (:state @listener) :cursor-watcher cursor-watcher))

(defn stop-syncing []
  (if @listener
    (remove-watch (:state @listener) :cursor-watcher)
    (sync/stop-listening @listener)))

(go
  (when (<? (sync/restore!))
    (dispatch-sync [:update-db {:syncing? true}])
    (start-syncing)))

;; push

(def push-ch (chan))

(defn notes-changed? [prev-db db]
  (not= (data/all-notes prev-db)
        (data/all-notes db)))

(defn notes-watcher [_ _ prev-db db]
  (when (notes-changed? prev-db db)
    (storage/save! :notes (data/all-notes db))
    (doseq [note (data/dirty-notes db)]
      (println "(dirty)" note)
      (put! push-ch note))))

(defn push! [{:keys [id deleted? volatile?] :as note}]
  (go-catch
   (if deleted?
     (if volatile?
       (dispatch [:dissoc-note id])
       (<? (sync/delete! note)))
     (<? (sync/write! note)))))

(add-watch app-db :notes-watcher notes-watcher)

(go
  (dochan [note push-ch]
    (<? (push! note))))
