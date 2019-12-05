(ns dumpa.stream
  "Transformations for the stream of parsed binlog events."
  (:require [clojure.core.async :as async :refer [go go-loop <! >! thread chan]]
            [schema.core :as s]
            [dumpa.table-schema :as table-schema]
            [clojure.tools.logging :as log]
            [manifold.stream]
            [dumpa.query :as query]
            [dumpa.utils :as utils]
            [dumpa.row-format :as row-format]
            [dumpa.events :as events]
            [dumpa.binlog :as binlog]
            [dumpa.table-schema :as table-schema]))

(defn- preserving-reduced
  [rf]
  #(let [ret (rf %1 %2)]
     (if (reduced? ret)
       (reduced ret)
       ret)))

(def filter-txs
  "A stateful transducer to filter away canceled
  transactions. Internally batches all events in a transaction and
  releases them at once on successful commit. It removes the events
  marking tx boundaries."
  (fn [rf]
    (let [ongoing? (volatile! false)
          tx       (volatile! [])
          prf      (preserving-reduced rf)
          reset-tx (fn []
                     (let [tx-content @tx]
                       (vreset! ongoing? false)
                       (vreset! tx [])
                       tx-content))
          start-tx (fn [] (vreset! ongoing? true))]

      (fn
        ([] (rf))
        ([result] (rf result))

        ([result input]
         (condp = (events/event-type input)
           :tx-begin (do (start-tx)
                         result)

           :tx-commit (reduce prf result (reset-tx))

           :tx-rollback (do (reset-tx)
                            result)
           (if @ongoing?
             (do (vswap! tx conj input)
                 result)
             (rf result input))))))))

(defn add-binlog-filename
  "Build a stateful transducer to add binlog filename to all
  events. Normally the events data only contains binlog position. This
  transducer tracks the current filename, starting from init-filename,
  and updates the filename from rotate events. Transducer also removes
  the rotate events."
  [init-filename]
  (fn [rf]
    (let [filename (volatile! init-filename)]
      (fn
        ([] (rf))
        ([result] (rf result))

        ([result input]
         (let [[type data _] input]
           (if (= type :rotate)
             (do (vreset! filename (:filename data))
                 result)
             (rf result (assoc-in input [2 :next-file] @filename)))))))))

(def group-table-maps
  "A stateful transducer to group table-map events with the following
  write/update/delete operations."
  (fn [rf]
    (let [last-table-map (volatile! [::none])]
      (fn
        ([] (rf))
        ([result] (rf result))

        ([result input]
         (let [table-map @last-table-map
               event-type (events/event-type input)]
           (cond
             (= event-type :table-map) (do
                                         (vreset! last-table-map input)
                                         result) ; Delay table-map events

             ;; Return [table-map op] as pair
             (#{:write :update :delete} event-type) (rf result [table-map input])

             ;; Wrap op
             :else (rf result [input]))))))))

(defn- ->db [event-pair]
  (let [[[event-type event-body]] event-pair]
    (:db event-body)))

(defn- ->table [event-pair]
  (let [[table-map _] event-pair
        event-body (events/event-data table-map)]
    (keyword (:table event-body))))

(defn filter-database
  "Returns a transducer that removes events that are not from the
  given database"
  [expected-db]
  (filter #(= (->db %) expected-db)))

(defn filter-tables
  "Returns a transducer that removes events that are not from any of
  the given tables. Does not filter events that do not contain table
  information (i.e. alter table event). If expected-tables is nil or
  empty degenerates to an allow all filter."
  [expected-tables]
  (if (seq expected-tables)
    (filter #(let [table (->table %)]
               (or
                (nil? table)
                (some? (expected-tables table)))))
    (map identity)))

(defn- clear-schema-cache! [schema-cache]
  (reset! schema-cache {}))

(defn- get-from-cache [schema-cache table]
  (@schema-cache (keyword table)))

(defn- add-to-cache! [schema-cache table schema]
  (swap! schema-cache assoc (keyword table) schema))


(defn- validate-schema [schema table-map]
  (let [{:keys [db table]}         (events/event-data table-map)
        meta                       (events/event-meta table-map)
        validation-error (s/check table-schema/TableSchema schema)]
    (if validation-error
      (throw (ex-info "Invalid schema"
                      {:schema schema
                       :db db
                       :table table
                       :meta {:table-map meta :error validation-error}}))
      true)))

(defn- validation-error [schema]
  (s/check table-schema/TableSchema schema))

(defn- valid-schema? [schema]
  (nil? (validation-error schema)))

(defn- fetch-table-schema
  [table db {:keys [db-spec id-fns schema-cache keepalive-interval running?]}]
  (go
    (if-let [schema (get-from-cache schema-cache table)]
      schema
      (let [table-spec (table-schema/->table-spec (keyword table) id-fns)
            schema     (<!
                        (thread
                          (utils/retry
                           #(table-schema/load-schema db-spec db table-spec)
                           #(log/warn (str "Table load failed. Trying again in " %2 " ms") %1)
                           running?
                           keepalive-interval)))]
        (add-to-cache! schema-cache table schema)
        schema))))

(defn- with-table-schema
  [event-pair opts]
  (go
    (let [[table-map mutation] event-pair]
      (if (= (events/event-type table-map) :table-map)
        (let [{:keys [db table]} (events/event-data table-map)
              schema             (<! (fetch-table-schema table db opts))]
          (cond
            (nil? schema)
            [(row-format/error
              "Couldn't load the schema. Maybe the binlog client was stopped?"
              {:schema schema :db db :table table}
              {:table-meta (events/event-meta table-map)
               :error nil})]

            (not (valid-schema? schema))
            [(row-format/error
              "Invalid schema"
              {:schema schema :db db :table table}
              {:table-meta (events/event-meta table-map)
               :error (validation-error schema)})]

            :else
            [(assoc-in table-map [1 :schema] schema) mutation]))
        table-map))))

(defn add-table-schema
  "Processes event-pairs from in channel and adds a schema to the
  table-map event in case the first event is a table-map. Writes
  resulting enriched event-pairs to out channel. If the event-pair
  contains a :alter-table event then that event is filtered and schema
  cache is cleared."
  [out in {:keys [schema-cache] :as opts}]
  (go-loop []
    (if-some [event-pair (<! in)]
      (let [[table-map _] event-pair]
        (if (= (events/event-type table-map) :alter-table)
          (do (clear-schema-cache! schema-cache)
              (recur))
          (do (>! out (<! (with-table-schema event-pair opts)))
              (recur))))
      (async/close! out))))

(defn- convert-text [col #^bytes val]
  (when val
    (String. val (java.nio.charset.Charset/forName (:character-set col)))))

(defmulti convert-type :type)
(defmethod convert-type :tinytext   [col val] (convert-text col val))
(defmethod convert-type :text       [col val] (convert-text col val))
(defmethod convert-type :mediumtext [col val] (convert-text col val))
(defmethod convert-type :longtext   [col val] (convert-text col val))
(defmethod convert-type :default    [col val] val)

(defn- ->name-value [cols row-data]
  (let [cols-names (map :name cols)
        row-data (map convert-type cols row-data)]
    (zipmap cols-names row-data)))

(defn- ->row-format
  [row-data mutation-type table id-fn cols meta]
  (let [mapped-row (->name-value cols row-data)
        id         (id-fn mapped-row)]
    (if (= :delete mutation-type)
      (row-format/delete table id mapped-row meta)
      (row-format/upsert table id mapped-row meta))))

(defn convert-with-schema
  "Given an event-pair of table-map and mutation (write/update/delete)
  returns a seq of tuples, one per row in mutation, of format
  [row-type table-key id mapped-row meta]. The elements are:
    * row-type - :upsert or :delete
    * table-key - The table name as keyword
    * id - Value of the row primary key
    * mapped-row - Row contents as a map for :upsert, nil for :delete
    * meta - binlog position and ts from the source binlog event"
 [event-pair]
 (let [[table-map mutation] event-pair
       mutation-type (events/event-type mutation)]
    (if (and (= :table-map (events/event-type table-map))
             (#{:write :update :delete} mutation-type))
      (let [table     (-> table-map events/event-data :table keyword)
            schema    (-> table-map events/event-data :schema)
            id-fn     (:id-fn schema)
            cols      (:cols schema)
            rows      (-> mutation events/event-data :rows)
            meta      (events/event-meta mutation)]
        (map
         #(->row-format % mutation-type table id-fn cols meta)
         rows))
      event-pair)))

(def parse-events
  (comp (map events/parse-event)
        (remove nil?)))

(defn process-events [binlog-pos db only-tables]
  (comp filter-txs
        (add-binlog-filename (:filename binlog-pos))
        group-table-maps
        (filter-database db)
        (filter-tables (set only-tables))))


(defn- ->connected-source [ch]
  (manifold.stream/->source ch))


(defprotocol IStartable
  (start! [this] "Start the given stream if not already started."))

(defprotocol IStoppable
  (stop! [this] "Stop the given stream if started."))

(defprotocol ISourceable
  (source [this] "Return the stream output as a Manifold source."))

(defprotocol INextPosition
  (next-position [this]
    "Return the binlog position to use to continue streaming."))

(defrecord TableLoadStream [conf tables binlog-pos out-ch out-stream started?]
  IStartable
  (start! [_]
    (when-not @started?
      (let [{:keys [db-spec conn-params id-fns]} conf
            db (:db conn-params)
            position  (query/binlog-position db-spec)
            schema-chan (table-schema/load-and-parse-schemas
                         tables db-spec db id-fns)]
        (async/pipeline-async 1
                              out-ch
                              (partial query/stream-table db-spec)
                              schema-chan)
        (reset! binlog-pos position))
      (reset! started? true)))

  ISourceable
  (source [_] out-stream)

  INextPosition
  (next-position [_] @binlog-pos))

(defn new-table-load-stream [tables conf out-ch]
  (map->TableLoadStream {:conf conf
                         :tables tables
                         :binlog-pos (atom nil)
                         :out-ch out-ch
                         :out-stream (->connected-source out-ch)
                         :started? (atom nil)}))

(defrecord BinlogStream [conf client out-stream started? stopped?]
  IStartable
  (start! [_]
    (when-not @started?
      (let [timeout (get-in conf [:conn-params :initial-connection-timeout])]
        (binlog/start-client client timeout))
      (reset! started? true)))

  IStoppable
  (stop! [_]
    (when (and @started? (not @stopped?))
      (binlog/stop-client client)
      (manifold.stream/close! out-stream)
      (reset! stopped? true)))

  ISourceable
  (source [_] out-stream))

(defn new-binlog-stream [conf binlog-pos only-tables out-ch]
  (let [db-spec            (:db-spec conf)
        db                 (get-in conf [:conn-params :db])
        id-fns             (:id-fns conf)
        keepalive-interval (get-in conf [:conn-params :query-max-keepalive-interval])
        schema-cache       (atom {})
        events-xform       (comp
                            parse-events
                            (process-events binlog-pos db only-tables))
        events-ch          (chan 1 events-xform)
        schema-loaded-ch   (chan 1)
        started?           (atom false)
        stopped?           (atom false)
        client             (binlog/new-binlog-client (:conn-params conf)
                                                     binlog-pos
                                                     events-ch)]

    (add-table-schema schema-loaded-ch
                             events-ch
                             {:schema-cache schema-cache
                              :db-spec db-spec
                              :id-fns id-fns
                              :keepalive-interval keepalive-interval
                              :running? #(and @started? (not @stopped?))})
    (async/pipeline 4
                    out-ch
                    (comp (map convert-with-schema)
                          cat)
                    schema-loaded-ch)

    (map->BinlogStream {:conf conf
                        :client client
                        :out-stream (->connected-source out-ch)
                        :started? started?
                        :stopped? stopped?})))
