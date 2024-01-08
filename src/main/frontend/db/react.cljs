(ns frontend.db.react
  "Transact the tx with some specified relationship so that the components will
  be refreshed when subscribed data changed.
  It'll be great if we can find an automatically resolving and performant
  solution.
  "
  (:require [datascript.core :as d]
            [frontend.date :as date]
            [frontend.db.conn :as conn]
            [frontend.db.utils :as db-utils]
            [frontend.state :as state]
            [frontend.util :as util :refer [react]]
            [clojure.core.async :as async]))

;; Query atom of map of Key ([repo q inputs]) -> atom
;; TODO: replace with LRUCache, only keep the latest 20 or 50 items?

(defonce query-state (atom {}))

;; Current dynamic component
(def ^:dynamic *query-component* nil)

;; Which reactive queries are triggered by the current component
(def ^:dynamic *reactive-queries* nil)

;; component -> query-key
(defonce query-components (atom {}))

(defn set-new-result!
  [k new-result]
  (when-let [result-atom (get-in @query-state [k :result])]
    (reset! result-atom new-result)))

(def kv conn/kv)

(defn remove-key!
  [repo-url key]
  (db-utils/transact! repo-url [[:db.fn/retractEntity [:db/ident key]]])
  (set-new-result! [repo-url :kv key] nil))

(defn clear-query-state!
  []
  (reset! query-state {}))

(defn add-q!
  [k query time inputs result-atom transform-fn query-fn inputs-fn]
  (let [time' (int (util/safe-parse-float time))] ;; for robustness. `time` should already be float
    (swap! query-state assoc k {:query query
                                :query-time time'
                                :inputs inputs
                                :result result-atom
                                :transform-fn transform-fn
                                :query-fn query-fn
                                :inputs-fn inputs-fn}))
  result-atom)

(defn remove-q!
  [k]
  (swap! query-state dissoc k))

(defn add-query-component!
  [key component]
  (when (and key component)
    (swap! query-components update component (fn [col] (set (conj col key))))))

(defn remove-query-component!
  [component]
  (when-let [queries (get @query-components component)]
    (let [all-queries (apply concat (vals @query-components))]
      (doseq [query queries]
        (let [matched-queries (filter #(= query %) all-queries)]
          (when (= 1 (count matched-queries))
            (remove-q! query))))))
  (swap! query-components dissoc component))

;; TODO: rename :custom to :query/custom
(defn remove-custom-query!
  [repo query]
  (remove-q! [repo :custom query]))

;; Reactive query

(defn get-query-cached-result
  [k]
  (when-let [result (get @query-state k)]
    (when (satisfies? IWithMeta @(:result result))
      (set! (.-state (:result result))
           (with-meta @(:result result) {:query-time (:query-time result)})))
    (:result result)))

(defn q
  [repo k {:keys [use-cache? transform-fn query-fn inputs-fn disable-reactive?]
           :or {use-cache? true
                transform-fn identity}} query & inputs]
  ;; {:pre [(s/valid? :frontend.worker.react/block k)]}
  (let [kv? (and (vector? k) (= :kv (first k)))
        origin-key k
        k (vec (cons repo k))]
    (when-let [db (conn/get-db repo)]
      (let [result-atom (get-query-cached-result k)]
        (when-let [component *query-component*]
          (add-query-component! k component))
        (when-let [queries *reactive-queries*]
          (swap! queries conj origin-key))
        (if (and use-cache? result-atom)
          result-atom
          (let [{:keys [result time]} (util/with-time
                                        (-> (cond
                                              query-fn
                                              (query-fn db nil)

                                              inputs-fn
                                              (let [inputs (inputs-fn)]
                                                (apply d/q query db inputs))

                                              kv?
                                              (db-utils/entity db (last k))

                                              (seq inputs)
                                              (apply d/q query db inputs)

                                              :else
                                              (d/q query db))
                                            transform-fn))
                result-atom (or result-atom (atom nil))]
            ;; Don't notify watches now
            (set! (.-state result-atom) result)
            (if disable-reactive?
              result-atom
              (add-q! k query time inputs result-atom transform-fn query-fn inputs-fn))))))))

(defn get-current-page
  []
  (let [match (:route-match @state/state)
        route-name (get-in match [:data :name])
        page (case route-name
               :page
               (get-in match [:path-params :name])

               :file
               (get-in match [:path-params :path])

               (date/journal-name))]
    (when page
      (let [page-name (util/page-name-sanity-lc page)]
        (db-utils/entity [:block/name page-name])))))

(defn- execute-query!
  [graph db k {:keys [query _query-time inputs transform-fn query-fn inputs-fn result]}
   {:keys [_skip-query-time-check?]}]
  ;; FIXME:
  (when true
      ;; (or skip-query-time-check?
      ;;       (<= (or query-time 0) 80))
    (let [new-result (->
                      (cond
                        query-fn
                        (let [result (query-fn db result)]
                          (if (coll? result)
                            (doall result)
                            result))

                        inputs-fn
                        (let [inputs (inputs-fn)]
                          (apply d/q query db inputs))

                        (keyword? query)
                        (db-utils/get-key-value graph query)

                        (seq inputs)
                        (apply d/q query db inputs)

                        :else
                        (d/q query db))
                      transform-fn)]
      (when-not (= new-result result)
       (set-new-result! k new-result)))))

(defn- refresh-affected-queries!
  [repo-url affected-keys]
  (util/profile
   "refresh!"
   (let [db (conn/get-db repo-url)
         affected-keys-set (set affected-keys)
         state (->> (keep (fn [[k cache]]
                            (let [k' (vec (rest k))]
                              (when (and (= (first k) repo-url)
                                         (or (contains? affected-keys-set k')
                                             (contains? #{:custom :kv} (first k'))))
                                [k' cache]))) @query-state)
                    (into {}))
         all-keys (concat (distinct affected-keys)
                          (filter #(contains? #{:custom :kv} (first %)) (keys state)))]
     (doseq [k all-keys]
       (when-let [cache (get state k)]
         (let [{:keys [query query-fn]} cache
               custom? (= :custom (first k))
               {:keys [custom-query?]} (state/edit-in-query-or-refs-component)]
           (when (or query query-fn)
             (try
               (let [f #(execute-query! repo-url db (vec (cons repo-url k)) cache {:skip-query-time-check? custom-query?})]
                       ;; Detects whether user is editing in a custom query, if so, execute the query immediately
                 (if (and custom? (not custom-query?))
                   (async/put! (state/get-reactive-custom-queries-chan) [f query])
                   (f)))
               (catch :default e
                 (js/console.error e)
                 nil)))))))))

(defn refresh!
  "Re-compute corresponding queries (from tx) and refresh the related react components."
  [repo-url {:keys [tx-data tx-meta] :as tx} affected-keys]
  (when repo-url
    (if (get-in @state/state [:rtc/remote-batch-tx-state repo-url :in-transaction?])
      (state/update-state! [:rtc/remote-batch-tx-state repo-url :txs]
                           (fn [txs]
                             (conj txs tx)))
      (when (and (not (:skip-refresh? tx-meta)) (seq tx-data))
        (refresh-affected-queries! repo-url affected-keys)))))

(defn batch-refresh!
  [repo-url _txs]
  ;; (when (and repo-url (seq txs))
  ;;   (let [affected-keys (apply set/union (map get-affected-queries-keys txs))]
  ;;     (refresh-affected-queries! repo-url affected-keys)))
  (state/set-state! [:rtc/remote-batch-tx-state repo-url]
                    {:in-transaction? false
                     :txs []}))

(defn set-key-value
  [repo-url key value]
  (if value
    (db-utils/transact! repo-url [(kv key value)])
    (remove-key! repo-url key)))

(defn sub-key-value
  ([key]
   (sub-key-value (state/get-current-repo) key))
  ([repo-url key]
   (when (conn/get-db repo-url)
     (let [m (some-> (q repo-url [:kv key] {} key key) react)]
       (if-let [result (get m key)]
         result
         m)))))

(defn run-custom-queries-when-idle!
  []
  (let [chan (state/get-reactive-custom-queries-chan)]
    (async/go-loop []
      (let [[f query] (async/<! chan)]
        (try
          (if (state/input-idle? (state/get-current-repo))
            (f)
            (do
              (async/<! (async/timeout 2000))
              (async/put! chan [f query])))
          (catch :default error
            (let [type :custom-query/failed]
              (js/console.error (str type "\n" query))
              (js/console.error error)))))
      (recur))
    chan))
