(ns reimann.server
  (:use [reimann.core])
  (:use [reimann.common])
  (:require [reimann.query :as query])
  (:require [reimann.index :as index])
  (:use clojure.tools.logging)
  (:use lamina.core)
  (:use aleph.tcp)
  (:use gloss.core)
  (:use [protobuf.core])
  (:require gloss.io))

; maybe later
;(defn udp-server []
;  (let [channel (wait-for-result (udp-socket {:port 5555}))]   

; Handles a buffer with the given core.
(defn handle [core buffer]
  (let [msg (decode buffer)]
    ; Send each event/state to each stream
    (doseq [event (concat (:events msg) (:states msg))
            stream (deref (:streams core))]
      (stream event))
   
    (if (:query msg)
      ; Handle query
      (let [ast (query/ast (:string (:query msg)))]
        (if-let [i (deref (:index core))]
          {:ok true :states (index/search i ast)}
          {:ok false :error "no index"}))

      ; Generic acknowledge 
      {:ok true})))

; Returns a handler that applies messages to the given streams (by reference)
(defn handler [core]
  (fn [channel client-info]
    (receive-all channel (fn [buffer]
      (when buffer
        ; channel isn't closed; this is our message
        (try
          (enqueue channel (encode (handle core buffer)))
          (catch java.nio.channels.ClosedChannelException e
            (warn (str "channel closed")))
          (catch com.google.protobuf.InvalidProtocolBufferException e
            (warn (str "invalid message, closing " client-info))
            (close channel))
          (catch Exception e
            (warn e "Handler error")
            (close channel))))))))

(defn tcp-server
  ([core] (tcp-server core {}))
  ([core opts]
    (let [opts (merge {:port 5555
                       :frame (finite-block :int32)}
                      opts)
        handler (handler core)
        server (start-tcp-server handler opts)] 
      (info (str "TCP server " (select-keys [:host :port] opts) " online"))
      server)))
