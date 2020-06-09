(ns dumpa.binlog
  (:require [clojure.core.async :as async :refer [>!!]]
            [clojure.tools.logging :as log])
  (:import [com.github.shyiko.mysql.binlog
            BinaryLogClient
            BinaryLogClient$EventListener
            BinaryLogClient$LifecycleListener]))

(defn- call
  "Utiltiy for invoking callbacks that are potentially nil"
  [callback & args]
  (when callback
    (apply callback args)))

(defn lifecycle-listener
  [{:keys [on-connect
           on-communication-failure
           on-event-deserialization-failure
           on-disconnect]}]
  (reify
    BinaryLogClient$LifecycleListener
    (onConnect [this client]
      (call on-connect client)
      (log/info "BinaryLogClient connected"))
    (onCommunicationFailure [this client ex]
      (call on-communication-failure client ex)
      (log/warn "BinaryLogClient communication failure: " ex))
    (onEventDeserializationFailure [this client ex]
      (call on-event-deserialization-failure client ex)
      (log/warn "BinaryLogClient event deserialization failure: " ex))
    (onDisconnect [this client]
      (call on-disconnect client)
      (log/info "BinaryLogClient disconnected"))))

(defn event-listener
  "Create an event listener for the BinaryLogClient"
  [out ^BinaryLogClient client {:keys [on-event]}]
  (reify
    BinaryLogClient$EventListener
    (onEvent [this payload]
      (call on-event payload client)
      (>!! out payload))))

(defn- with-listeners
  "Apply event listeners to a BinaryLogClient"
  ^BinaryLogClient [^BinaryLogClient client out callbacks]
  (doto client
    (.registerEventListener (event-listener out client callbacks))
    (.registerLifecycleListener (lifecycle-listener callbacks))))

(defn new-binlog-client
  "Create a new binary log client for connecting to database using the
  conenction parameters host port user password and server-id. The
  client will start reading binary log at given file and
  position. Events are written to out channel. Out is closed on client
  disconnect."
  [{:keys [host
           port
           user
           password
           server-id
           stream-keepalive-interval
           stream-keepalive-timeout]}
   callbacks
   {:keys [file position]}
   out]
  (with-listeners (doto (BinaryLogClient. host port user password)
                    (.setServerId server-id)
                    (.setBinlogPosition position)
                    (.setBinlogFilename file)
                    (.setKeepAliveInterval stream-keepalive-interval)
                    (.setKeepAliveConnectTimeout stream-keepalive-timeout)) out callbacks))

(defn start-client [^BinaryLogClient client timeout]
  (.connect client timeout))

(defn stop-client [^BinaryLogClient client]
  (.disconnect client))
