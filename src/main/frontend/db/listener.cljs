(ns frontend.db.listener
  "DB listeners"
  (:require [frontend.db.conn :as conn]
            [frontend.db.utils :as db-utils]
            [frontend.db.persist :as db-persist]
            [frontend.state :as state]
            [frontend.util :as util]
            [promesa.core :as p]
            [electron.ipc :as ipc]
            [datascript.core :as d]))

;; persisting DBs between page reloads
(defn persist! [repo]
  (let [key (conn/datascript-db repo)
        db (conn/get-db repo)]
    (when db
      (let [db-str (if db (db-utils/db->string db) "")]
        (p/let [_ (db-persist/save-graph! key db-str)])))))

(defonce persistent-jobs (atom {}))

(defn clear-repo-persistent-job!
  [repo]
  (when-let [old-job (get @persistent-jobs repo)]
    (js/clearTimeout old-job)))

(defn persist-if-idle!
  [repo]
  (clear-repo-persistent-job! repo)
  (let [job (js/setTimeout
             (fn []
               (if (and (state/input-idle? repo)
                        (state/db-idle? repo)
                        ;; It's ok to not persist here since new changes
                        ;; will be notified when restarting the app.
                        (not (state/whiteboard-route?)))
                 (persist! repo)
                 ;; (state/set-db-persisted! repo true)

                 (persist-if-idle! repo)))
             3000)]
    (swap! persistent-jobs assoc repo job)))

;; only save when user's idle

(defonce *db-listener (atom nil))

(defn repo-listen-to-tx!
  [repo conn]
  (d/listen! conn :persistence
             (fn [tx-report]
               (when (not (:new-graph? (:tx-meta tx-report))) ; skip initial txs
                 (if (util/electron?)
                   (when-not (:dbsync? (:tx-meta tx-report))
                     ;; sync with other windows if needed
                     (p/let [graph-has-other-window? (ipc/ipc "graphHasOtherWindow" repo)]
                       (when graph-has-other-window?
                         (ipc/ipc "dbsync" repo {:data (db-utils/db->string (:tx-data tx-report))}))))
                   (do
                     (state/set-last-transact-time! repo (util/time-ms))
                     (persist-if-idle! repo)))

                 (when-let [db-listener @*db-listener]
                   (db-listener repo tx-report))))))

(defn listen-and-persist!
  [repo]
  (when-let [conn (conn/get-db repo false)]
    (d/unlisten! conn :persistence)
    (repo-listen-to-tx! repo conn)))