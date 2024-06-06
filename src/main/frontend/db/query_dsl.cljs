(ns frontend.db.query-dsl
  "Handles executing dsl queries a.k.a. simple queries"
  (:require [cljs-time.coerce :as tc]
            [cljs-time.core :as t]
            [cljs.reader :as reader]
            [clojure.set :as set]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [frontend.date :as date]
            [frontend.db.model :as model]
            [frontend.db.query-react :as query-react]
            [frontend.db.utils :as db-utils]
            [logseq.db.frontend.rules :as rules]
            [frontend.template :as template]
            [logseq.graph-parser.text :as text]
            [logseq.common.util.date-time :as date-time-util]
            [logseq.common.util.page-ref :as page-ref]
            [logseq.common.util :as common-util]
            [frontend.util.text :as text-util]
            [frontend.util :as util]
            [frontend.config :as config]
            [logseq.db.frontend.property :as db-property]
            [frontend.state :as state]))


;; Query fields:

;; Operators:
;; and
;; or
;; not

;; Filters:
;; between
;;   Example: (between -7d +7d)
;;            (between created-at -1d today)
;;            (between last-modified-at -1d today)
;; [[page-ref]]
;; property (block)
;; task (block)
;; priority (block)
;; page
;; sample
;; full-text-search ""

;; namespace
;; page-property (page)
;; page-tags (page)
;; all-page-tags

;; Sort by (field, asc/desc):
;; (sort-by created-at asc)

;; Time helpers
;; ============
(defn- ->journal-day-int [input]
  (let [input (string/lower-case (name input))]
    (cond
      (= "today" input)
      (date-time-util/date->int (t/today))

      (= "yesterday" input)
      (date-time-util/date->int (t/yesterday))

      (= "tomorrow" input)
      (date-time-util/date->int (t/plus (t/today) (t/days 1)))

      (page-ref/page-ref? input)
      (let [input (-> (page-ref/get-page-name input)
                      (string/replace ":" "")
                      (string/capitalize))]
        (when (date/valid-journal-title? input)
          (date/journal-title->int input)))

      :else
      (let [duration (parse-long (subs input 0 (dec (count input))))
            kind (last input)
            tf (case kind
                 "y" t/years
                 "m" t/months
                 "w" t/weeks
                 t/days)]
        (date-time-util/date->int (t/plus (t/today) (tf duration)))))))

(defn- ->timestamp [input]
  (let [input (string/lower-case (name input))]
    (cond
      (= "now" input)
      (util/time-ms)

      (= "today" input)
      (tc/to-long (t/today))

      (= "yesterday" input)
      (tc/to-long (t/yesterday))

      (= "tomorrow" input)
      (tc/to-long (t/plus (t/today) (t/days 1)))

      (page-ref/page-ref? input)
      (let [input (-> (page-ref/get-page-name input)
                      (string/replace ":" "")
                      (string/capitalize))]
        (when (date/valid-journal-title? input)
          (date/journal-title->long input)))

      :else
      (let [duration (parse-long (subs input 0 (dec (count input))))
            kind (last input)
            tf (case kind
                 "y" t/years
                 "m" t/months
                 "w" t/weeks
                 "h" t/hours
                 "n" t/minutes          ; min
                 t/days)]
        (tc/to-long (t/plus (t/today) (tf duration)))))))

;; Boolean operator utils: and, or, not
;; ======================
(defn- collect-vars
  [l]
  (let [vars (atom #{})]
    (walk/postwalk
     (fn [f]
       (when (and (symbol? f) (= \? (first (name f))))
         (swap! vars conj f))
       f)
     l)
    @vars))

(defn- build-and-or-not-result
  [fe clauses current-filter nested-and?]
  (cond
    (= fe 'not)
    (if (every? list? clauses)
      (cons fe (seq clauses))
      (let [clauses (if (coll? (first clauses))
                      (apply concat clauses)
                      clauses)
            clauses (if (and (= 1 (count clauses))
                             (= 'and (ffirst clauses)))
                      ;; unflatten
                      (rest (first clauses))
                      clauses)]
        (cons fe (seq clauses))))

    (coll? (first clauses))
    (cond
      (= current-filter 'not)
      (cons 'and clauses)

      (or (= current-filter 'or)
          nested-and?)
      (cons 'and clauses)

      :else
      (->> clauses
           (mapcat (fn [result]
                     (cond
                       ;; rule like (task ?b #{"NOW"})
                       (list? result)
                       [result]
                       ;; datalog clause like [[?b :block/uuid]]
                       (vector? result)
                       result
                       :else
                       [(cons 'and (seq result))])))
           (apply list fe)))

    :else
    (apply list fe clauses)))

(declare build-query)

(defonce remove-nil? (partial remove nil?))

(defn- build-and-or-not
  [e {:keys [current-filter vars] :as env} level fe]
  (let [raw-clauses (map (fn [form]
                           (build-query form (assoc env :current-filter fe) (inc level)))
                         (rest e))
        clauses (->> raw-clauses
                     (map :query)
                     remove-nil?
                     (distinct))
        nested-and? (and (= fe 'and) (= current-filter 'and))]
    (when (seq clauses)
      (let [result (build-and-or-not-result
                    fe clauses current-filter nested-and?)
            vars' (set/union (set @vars) (collect-vars result))
            query (cond
                    nested-and?
                    result

                    (and (zero? level) (contains? #{'and 'or} fe))
                    result

                    (and (= 'not fe) (some? current-filter))
                    result

                    :else
                    [result])]
        (reset! vars vars')
        {:query query
         :rules (distinct (mapcat :rules raw-clauses))}))))

;; build-query fns
;; ===============
(defn- build-between-two-arg
  [e]
  (let [start (->journal-day-int (nth e 1))
        end (->journal-day-int (nth e 2))
        [start end] (sort [start end])]
    {:query (list 'between '?b start end)
     :rules [:between]}))

(defn- build-between-three-arg
  [e]
  (let [k (-> (second e)
              (name)
              (string/lower-case)
              (string/replace "-" "_"))]
    (when (contains? #{"created_at" "last_modified_at"} k)
      (let [start (->timestamp (nth e 2))
            end (->timestamp (nth e 3))]
        (when (and start end)
          (let [[start end] (sort [start end])
                sym '?v]
            {:query [['?b :block/properties '?prop]
                     [(list 'get '?prop k) sym]
                     [(list '>= sym start)]
                     [(list '< sym end)]]}))))))

(defn- build-between
  [e]
  (cond
    (= 3 (count e))
    (build-between-two-arg e)

    ;; (between created_at -1d today)
    (= 4 (count e))
    (build-between-three-arg e)))


(defn ->file-property-value
  "Parses property values for file graphs and handles non-string values or any page-ref like values"
  [v*]
  (if (some? v*)
   (let [v (str v*)
         result (if-some [res (text/parse-non-string-property-value v)]
                  res
                  (if (string/starts-with? v "#")
                    (subs v 1)
                    (or (page-ref/get-page-name v) v)))]
     (if (string? result)
       (or (parse-double result) (string/trim result))
       result))
    v*))

(defn ->db-property-value
  "Parses property values for DB graphs"
  [v]
  (if (string? v)
    (if (string/starts-with? v "#")
      (subs v 1)
      (or (page-ref/get-page-name v) v))
    v))

(defn- ->file-keyword-property
  "Case-insensitive property names for file graphs. Users manually type queries to enter them as they appear"
  [property-name]
  (-> (string/replace (name property-name) "_" "-")
      string/lower-case
      keyword))

(defn- ->db-keyword-property
  "Case sensitive property names for db graphs"
  [property-name]
  (if (qualified-keyword? property-name)
    property-name
    (keyword db-property/default-user-namespace (name property-name))))

(defn- build-property-two-arg
  [e {:keys [db-graph?]}]
  (let [k (if db-graph? (->db-keyword-property (nth e 1)) (->file-keyword-property (nth e 1)))
        v (nth e 2)
        v' (if db-graph? (->db-property-value v) (->file-property-value v))]
    {:query (list 'property '?b k v')
     :rules [:property]}))

(defn- build-property-one-arg
  [e {:keys [db-graph?]}]
  (let [k (if db-graph? (->db-keyword-property (nth e 1)) (->file-keyword-property (nth e 1)))]
    {:query (list 'has-property '?b k)
     :rules [:has-property]}))

(defn- build-property [e env]
  (cond
    (= 3 (count e))
    (build-property-two-arg e env)

    (= 2 (count e))
    (build-property-one-arg e env)))

(defn- build-task
  [e {:keys [db-graph?]}]
  (let [markers (if (coll? (first (rest e)))
                  (first (rest e))
                  (rest e))]
    (when (seq markers)
      (if db-graph?
        (let [markers' (set (map (comp string/capitalize name) markers))]
          {:query (list 'task '?b (set markers'))
          :rules [:task]})
        (let [markers (set (map (comp string/upper-case name) markers))]
          {:query (list 'task '?b markers)
           :rules [:task]})))))

(defn- build-priority
  [e]
  (let [priorities (if (coll? (first (rest e)))
                     (first (rest e))
                     (rest e))]
    (when (seq priorities)
      (let [priorities (set (map (comp string/upper-case name) priorities))]
        {:query (list 'priority '?b priorities)
         :rules [:priority]}))))

(defn- build-page-property
  [e {:keys [db-graph?]}]
  (let [[k v] (rest e)
        k' (if db-graph? (->db-keyword-property k) (->file-keyword-property k))]
    (if (some? v)
      (let [v' (if db-graph? (->db-property-value v) (->file-property-value v))]
        {:query (list 'page-property '?p k' v')
         :rules [:page-property]})
      {:query (list 'has-page-property '?p k')
       :rules [:has-page-property]})))

(defn- build-page-tags
  [e]
  (let [tags (if (coll? (first (rest e)))
               (first (rest e))
               (rest e))
        tags (map (comp string/lower-case name) tags)]
    (when (seq tags)
      (let [tags (set (map (comp page-ref/get-page-name! string/lower-case name) tags))]
        {:query (list 'page-tags '?p tags)
         :rules [:page-tags]}))))

(defn- build-all-page-tags
  []
  {:query (list 'all-page-tags '?p)
   :rules [:all-page-tags]})

(defn- build-sample
  [e sample]
  (when-let [num (second e)]
    (when (integer? num)
      (reset! sample num)
      ;; blank b/c this post-process filter doesn't effect query
      {})))

(defn- build-sort-by
  [e sort-by_]
  (let [[k order*] (map keyword (rest e))
        order (if (contains? #{:asc :desc} order*)
                order*
                :desc)
        comp (if (= order :desc)
               ;; Handle nil so that is always less than a value e.g. treated as a "" for a string.
               ;; Otherwise sort bugs occur that prevent non-nil values from being sorted
               #(if (nil? %2) true (>= %1 %2))
               #(if (nil? %1) true (<= %1 %2)))]
    (reset! sort-by_
            (fn sort-results [result property-val-fn]
              ;; first because there is one binding result in query-wrapper
              (sort-by #(-> % first (property-val-fn k))
                       comp
                       result)))
    ;; blank b/c this post-process filter doesn't effect query
    {}))

(defn- build-page
  [e]
  (let [page-name (page-ref/get-page-name! (str (first (rest e))))
        page-name (util/page-name-sanity-lc page-name)]
    {:query (list 'page '?b page-name)
     :rules [:page]}))

(defn- build-namespace
  [e]
  (let [page-name (page-ref/get-page-name! (str (first (rest e))))
        page (util/page-name-sanity-lc page-name)]
    (when-not (string/blank? page)
      {:query (list 'namespace '?p page)
       :rules [:namespace]})))

(defn- build-page-ref
  [e]
  (let [page-name (-> (page-ref/get-page-name! e)
                      (util/page-name-sanity-lc))]
    {:query (list 'page-ref '?b page-name)
     :rules [:page-ref]}))

(defn- build-block-content [e]
  {:query (list 'block-content '?b e)
   :rules [:block-content]})

(defn build-query
  "This fn converts a form/list in a query e.g. `(operator arg1 arg2)` to its datalog
  equivalent. This fn is called recursively on sublists for boolean operators
  `and`, `or` and `not`. This fn should return a map with :query and :rules or nil.

Some bindings in this fn:

* e - the list being processed
* fe - the query operator e.g. `property`"
  ([e env]
   (build-query e (assoc env :vars (atom {})) 0))
  ([e {:keys [sort-by blocks? sample] :as env :or {blocks? (atom nil)}} level]
   ; {:post [(or (nil? %) (map? %))]}
   (let [fe (first e)
         fe (when fe (symbol (string/lower-case (name fe))))
         page-ref? (page-ref/page-ref? e)]
     (when (or (and page-ref?
                    (not (contains? #{'page-property 'page-tags} (:current-filter env))))
               (contains? #{'between 'property 'todo 'task 'priority 'page} fe)
               (and (not page-ref?) (string? e)))
       (reset! blocks? true))
     (cond
       (nil? e)
       nil

       page-ref?
       (build-page-ref e)

       (string? e)                      ; block content full-text search, could be slow
       (build-block-content e)

       (contains? #{'and 'or 'not} fe)
       (build-and-or-not e env level fe)

       (= 'between fe)
       (build-between e)

       (= 'property fe)
       (build-property e env)

       ;; task is the new name and todo is the old one
       (or (= 'todo fe) (= 'task fe))
       (build-task e env)

       (= 'priority fe)
       (build-priority e)

       (= 'sort-by fe)
       (build-sort-by e sort-by)

       (= 'page fe)
       (build-page e)

       (= 'namespace fe)
       (build-namespace e)

       (= 'page-property fe)
       (build-page-property e env)

       (= 'page-tags fe)
       (build-page-tags e)

       (= 'all-page-tags fe)
       (build-all-page-tags)

       (= 'sample fe)
       (build-sample e sample)

       :else
       nil))))

;; parse fns
;; =========

(defonce tag-placeholder "~~~tag-placeholder~~~")
(defn pre-transform
  [s]
  (if (common-util/wrapped-by-quotes? s)
    s
    (let [quoted-page-ref (fn [matches]
                            (let [match' (string/replace (second matches) "#" tag-placeholder)]
                              (str "\"" page-ref/left-brackets match' page-ref/right-brackets "\"")))]
      (some-> s
              (string/replace page-ref/page-ref-re quoted-page-ref)
              (string/replace text-util/between-re
                              (fn [[_ x]]
                                (->> (string/split x #" ")
                                     (remove string/blank?)
                                     (map (fn [x]
                                            (if (or (contains? #{"+" "-"} (first x))
                                                    (and (util/safe-re-find #"\d" (first x))
                                                         (some #(string/ends-with? x %) ["y" "m" "d" "h" "min"])))
                                              (keyword (name x))
                                              x)))
                                     (string/join " ")
                                     (util/format "(between %s)"))))
              (string/replace #"\"[^\"]+\"" (fn [s] (string/replace s "#" tag-placeholder)))
              (string/replace " #" " #tag ")
              (string/replace #"^#" "#tag ")
              (string/replace tag-placeholder "#")))))

(defn- add-bindings!
  [q {:keys [db-graph?]}]
  (let [forms (set (flatten q))
        syms ['?b '?p 'not]
        [b? p? not?] (-> (set/intersection (set syms) forms)
                         (map syms))]
    (cond
      not?
      (cond
        (and b? p?)
        (concat [['?b :block/uuid] ['?p :block/name] ['?b :block/page '?p]] q)

        b?
        (if db-graph?
          ;; This keeps built-in properties from showing up in not results.
          ;; May need to be revisited as more class and property filters are explored
          (concat [['?b :block/uuid] '[(missing? $ ?b :logseq.property/built-in?)]] q)
          (concat [['?b :block/uuid]] q))

        p?
        (concat [['?p :block/name]] q)

        :else
        q)

      (and b? p?)
      (concat [['?b :block/page '?p]] q)

      :else
      q)))

(defn simplify-query
  [query]
  (if (string? query)
    query
    (walk/postwalk
     (fn [f]
       (if (and
            (coll? f)
            (contains? #{'and 'or} (first f))
            (= 2 (count f)))
         (second f)
         f))
     query)))

(def custom-readers {:readers {'tag (fn [x] (page-ref/->page-ref x))}})
(defn parse
  [s {:keys [db-graph?] :as opts}]
  (when (and (string? s)
             (not (string/blank? s)))
    (let [s (if (= \# (first s)) (page-ref/->page-ref (subs s 1)) s)
          form (some->> s
                        (pre-transform)
                        (reader/read-string custom-readers))
          sort-by (atom nil)
          blocks? (atom nil)
          sample (atom nil)
          form (simplify-query form)
          {result :query rules :rules}
          (when form (build-query form {:sort-by sort-by
                                        :blocks? blocks?
                                        :db-graph? db-graph?
                                        :sample sample}))
          result' (when (seq result)
                    (let [key (if (coll? (first result))
                                ;; Only queries for this branch are not's like:
                                ;; [(not (page-ref ?b "page 2"))]
                                (keyword (ffirst result))
                                (keyword (first result)))]
                      (add-bindings! (if (= key :and) (rest result) result) opts)))]
      {:query result'
       :rules (if db-graph?
                (rules/extract-rules rules/db-query-dsl-rules rules {:deps rules/rules-dependencies})
                (mapv rules/query-dsl-rules rules))
       :sort-by @sort-by
       :blocks? (boolean @blocks?)
       :sample sample})))

;; Main fns
;; ========

(defn query-wrapper
  [where {:keys [blocks? block-attrs]}]
  (let [block-attrs (or block-attrs (butlast model/block-attrs))
        q (if blocks?                   ; FIXME: it doesn't need to be either blocks or pages
            `[:find (~'pull ~'?b ~block-attrs)
              :in ~'$ ~'%
              :where]
            '[:find (pull ?p [*])
              :in $ %
              :where])]
    (if (coll? (first where))
      (apply conj q where)
      (conj q where))))

(defn parse-query
  ([q] (parse-query q {}))
  ([q options]
   (let [q' (template/resolve-dynamic-template! q)]
     (parse q' (merge {:db-graph? (config/db-based-graph? (state/get-current-repo))} options)))))

(defn pre-transform-query
  [q]
  (let [q' (template/resolve-dynamic-template! q)]
    (pre-transform q')))

(defn- sort-by-prep
  "For a db graph, adds a block attribute :block/properties-by-name to be used
  for sorting with its keys being property names"
  [col]
  ;; Only modify result shapes that we know of
  (if (map? (ffirst col))
    (map (fn [blocks]
           (mapv (fn [block]
                   (assoc block
                          :block/properties-by-name
                          (->> (db-property/properties block)
                               (map (fn [[k v]]
                                      [(:block/original-name (db-utils/entity k))
                                       (or (some->> (:db/id v)
                                                    db-utils/entity
                                                    db-property/get-property-value-name)
                                           v)]))
                               (into {}))))
                 blocks))
         col)
    col))

(defn get-db-property-value
  "Fetch a property's value given a block map and property name. Similar to
  query-table/sort-by-fn. We should standardize this soon"
  [m prop]
  (case prop
    :created-at (:block/created-at m)
    :updated-at (:block/updated-at m)
    (get-in m [:block/properties-by-name (name prop)])))

(def db-block-attrs
  "Like ldb/block-attrs but for query dsl an db graphs"
  ;; '*' needed as we need to pull user properties and don't know their names in advance
  '[*
    {:block/page [:db/id :block/name :block/original-name :block/journal-day]}
    {:block/_parent ...}])

(defn query
  "Runs a dsl query with query as a string. Primary use is from '{{query }}'"
  ([repo query-string]
   (query repo query-string {}))
  ([repo query-string query-opts]
   (when (and (string? query-string) (not= "\"\"" query-string))
     (let [{:keys [query rules sort-by blocks? sample]} (parse-query query-string)]
       (when-let [query' (some-> query (query-wrapper {:blocks? blocks?
                                                       :block-attrs (when (config/db-based-graph? repo)
                                                                      db-block-attrs)}))]
         (let [random-samples (if @sample
                                (fn [col]
                                  (take @sample (shuffle col)))
                                identity)
               sort-by' (if sort-by
                          (if (config/db-based-graph? repo)
                            (comp (fn [col] (sort-by col get-db-property-value)) sort-by-prep)
                            #(sort-by % (fn [m prop] (get-in m [:block/properties prop]))))
                          identity)
               transform-fn (comp sort-by' random-samples)]
           (query-react/react-query repo
                                    {:query query'
                                     :query-string query-string
                                     :rules rules}
                                    (merge
                                     {:use-cache? false
                                      :transform-fn transform-fn}
                                     query-opts))))))))

(defn custom-query
  "Runs a dsl query with query as a seq. Primary use is from advanced query"
  [repo query-m query-opts]
  (when (seq (:query query-m))
    (let [query-string (template/resolve-dynamic-template! (pr-str (:query query-m)))
          db-graph? (config/db-based-graph? repo)
          {:keys [query sort-by blocks? rules]} (parse query-string {:db-graph? db-graph?})]
      (when-let [query' (some-> query (query-wrapper {:blocks? blocks?
                                                      :block-attrs (when db-graph? db-block-attrs)}))]
        (query-react/react-query repo
                                 (merge
                                  query-m
                                  {:query query'
                                   :rules rules})
                                 (merge
                                  query-opts
                                  (when sort-by
                                    {:transform-fn
                                     (if db-graph?
                                       (comp (fn [col] (sort-by col get-db-property-value)) sort-by-prep)
                                       #(sort-by % (fn [m prop] (get-in m [:block/properties prop]))))})))))))

(defn query-contains-filter?
  [query filter-name]
  (string/includes? query (str "(" filter-name)))

(comment
  ;; {{query (and (page-property foo bar) [[hello]])}}

  (query "(and [[foo]] [[bar]])")

  (query "(or [[foo]] [[bar]])")

  (query "(not (or [[foo]] [[bar]]))")

  (query "(between -7d +7d)")

  (query "(between -7d today)")

  (query "(between created_at yesterday today)")

  (query "(and [[some page]] (property foo bar))")

  (query "(and [[some page]] (task now later))")

  (query "(and [[some page]] (priority A))")

  ;; nested query
  (query "(and [[baz]] (or [[foo]] [[bar]]))")

  (query "(and [[some page]] (sort-by created-at))")

  (query "(and (page-property foo bar) [[hello]])"))
