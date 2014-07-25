(ns iron-mq-clojure.client
  (:use [cheshire.core :only [parse-string generate-string]])
  (:import (java.net URL)))

(def aws-host "mq-aws-us-east-1.iron.io")
(def rackspace-host "mq-rackspace-dfw.iron.io")

(defn create-client
  "Creates an IronMQ client from the passed token, project-id and options.

  token - can be obtained from hud.iron.io/tokens
  project-id - can be obtained from hud.iron.io
  
  Options can be:
  :api-version - the version of the API to use, as an int. Defaults to 1.
  :scheme - the HTTP scheme to use when communicating with the server. Defaults to https.
  :host - the API's host. Defaults to aws-host, the IronMQ AWS cloud. Can be a string
          or rackspace-host, which holds the host for the IronMQ Rackspace cloud.
  :port - the port, as an int, that the server is listening on. Defaults to 443.
  :max-retries - maximum number of retries on HTTP error 503."
  [token project-id & options]
  (let [default {:token token
                 :project-id project-id
                 :api-version 3
                 :scheme "https"
                 :host aws-host
                 :port 443
                 :max-retries 5}]
    (merge default (apply hash-map options))))

(defn create-message
  "Creates a message that can then be pushed to a queue.
  body - is the body of the message.
  
  Options can be:
  :timeout - the timeout (in seconds) after which the message will be returned to the
             queue, after a successful get-message or get-messages. Defaults to 60.
  :delay - the delay (in seconds) before which the message will not be available on the
           queue after being pushed. Defaults to 0.
  :expires_in - the number of seconds to keep the message on the queue before deleting
                it automatically. Defaults to 604,800 (7 days). Max is 2,592,000 seconds
                (30 days)."
  [body & options]
  (merge {:body body} (apply hash-map options)))

(defn request
  "Sends an HTTP request to the IronMQ API.

  client - an instance of an IronMQ client, created with create-client.
  method - a string specifying the HTTP request method (GET, POST, etc.)
  endpoint - the IronMQ API endpoint following the project ID, with a leading /
  body - a string you would like to pass with the request. Set it to nil if not passing a body."
  [client method endpoint body]
  (let [path (format "/%d/projects/%s%s"
               (:api-version client)
               (:project-id client)
               endpoint)
        url (URL. (:scheme client)
              (:host client)
              (:port client)
              path)]
    (loop [try 0]
      (let [conn (. url openConnection)]
        (doto conn
          (.setRequestMethod method)
          (.setRequestProperty "Content-Type" "application/json")
          (.setRequestProperty "Authorization" (format "OAuth %s" (:token client)))
          (.setRequestProperty "User-Agent" "ironmq-clojure-1.0.3"))
        (if-not (empty? body)
          (. conn setDoOutput true))
        (. conn connect)
        (if-not (empty? body)
          (spit (. conn getOutputStream) body))
        (let [status (. conn getResponseCode)]
          (if (= status 200)
            (parse-string (slurp (. conn getInputStream)))
            (if (and (= status 503) (< try (:max-retries client)))
              (do
                (Thread/sleep (* (Math/pow 4 try) 100 (Math/random)))
                (recur (+ try 1)))
              (throw (Exception. (slurp (. conn getErrorStream)))))))))))

(defn queues
  "Returns a list of queues that a client has access to.

  client - an IronMQ client created with create-client."
  [client]
  (map (fn [q] (get q "name"))
    (get (request client "GET" "/queues" nil)
      "queues")))

(defn queue-size
  "Returns the size of a queue, as an int.

  client - an IronMQ client created with create-client.
  queue - the name of a queue, passed as a string."
  [client queue]
  (get (request client "GET" (format "/queues/%s" queue) nil)
    "size"))

(defn queue-info
  "Executes an HTTP request to get details on a queue, and return it."

  [client queue]
  (get (request client "GET" (format "/queues/%s" queue) nil)
    "queue"))

(defn clear
  "Executes an HTTP request to clear messages from a queue."

  [client queue]
  (request client "DELETE" (format "/queues/%s/messages" queue) "{}"))

(defn delete-queue
  "Delete queue by name."

  [client queue]
  (request client "DELETE" (format "queues/%s" queue) "{}"))

(defn post-messages
  "Pushes multiple messages to a queue in a single HTTP request.

  client - an IronMQ client, created with create-client.
  queue - the name of a queue, passed as a string.
  messages - an array of messages created with create-message. It can also be an 
             array of strings, which will be used as the body and passed through
             create-message."
  [client queue & messages]
  (get (request client "POST" (format "/queues/%s/messages" queue)
         (generate-string {:messages (map
                                       (fn [m]
                                         (if (string? m)
                                           (create-message m) m))
                                       messages)}))
    "ids"))

(defn post-message
  "Pushes a single message to a queue.
  
  client - an IronMQ client, created with create-client.
  queue - the name of a queue, passed as a string.
  message - a message created with create-message. It can also be a string, which
            will be used as the body and passed through create-message."
  [client queue message]
  (first (post-messages client queue message)))

(defn reserve-messages
  "Reserves an array of messages from a queue.

  client - an IronMQ client, created with create-client.
  queue - the name of a queue, passed as a string."
  [client queue n]
  (get (request client "POST" (format "/queues/%s/reservations" queue)
         (generate-string {:n n}))
    "messages")
  )

(defn reserve-message
  "Reserves a message from a queue.

  client - an IronMQ client, created with create-client.
  queue - the name of a queue, passed as a string.
  n - the number of messages to retrieve, passed as an int."
  [client queue]
  (reserve-messages client queue 1))

(defn get-message
  "Returns a single message from a queue.

  client - an IronMQ client, created with create-client.
  queue - the name of a queue, passed as a string."
  [client queue]
  (reserve-message client queue))

(defn get-messages
  "Returns an array of messages that are on a queue.

  client - an IronMQ client, created with create-client.
  queue - the name of a queue, passed as a string.
  n - the number of messages to retrieve, passed as an int."
  [client queue n]
  (reserve-messages client queue n))

(defn get-message-by-id
  "Returns a message by id.

   client - an IronMQ client, created with create-client.
   queue - the name of a queue, passed as a string.
   message-id - the id of the message."
  [client queue message-id]
  (request client "GET" (format "queues/%s/messages/%s" queue message-id) nil))

(defn delete-message
  "Removes a message from a queue.
  
  client - an IronMQ client, created with create-client.
  queue - the name of a queue, passed as a string.
  message - the message object to be removed, as retrieve from get-message
            or get-messages."

  ([client queue message-id reservation-id]
    (request client "DELETE" (format "/queues/%s/messages/%s" queue message-id)
      (generate-string {:reservation_id reservation-id})))
  ([client queue message-id]
    (request client "DELETE" (format "/queues/%s/messages/%s" queue message-id) "{}")))

(defn delete-messages
  "Execute an HTTP request to delete messages from queue."

  [client queue ids]
  (request client "DELETE" (format "queues/%s/messages" queue)
    (generate-string{:ids (map (fn[id] (generate-string{:id id})) ids)}{:pretty true})))

(defn touch-message
  "Touching a reserved message extends its timeout to the duration specified when the message was created.

   message - the reserved message."
  [client queue message]
  (request client "POST" (format "/queues/%s/messages/%s/touch" queue (get message "id"))
    (generate-string {:reservation_id (get message "reservation_id")})))

(defn peek-message
  "Peeks message from a queue.

   client - an IronMQ client, created with create-client.
   queue - the name of a queue, passed as a string.
   n - the number of messages to retrieve."
  ([client queue n]
    (request client "GET" (format "/queues/%s/messages?n=%d" queue n) nil))
  ([client queue]
    (request client "GET" (format "/queues/%s/messages" queue) nil)))

(defn release-message
  "Release locked message after specified time. If there is no message with such id on the queue.

   client - an IronMQ client, created with create-client.
   queue - the name of a queue, passed as a string.
   message - the reserved message.
   delay - the time after which the message will be released."
  ([client queue message]
    (request client "POST" (format "queues/%s/messages/%s/release" queue (get message "id"))
      (generate-string {:reservation_id (get message "reservation_id")})))
  ([client queue message delay]
    (request client "POST" (format "queues/%s/messages/%s/release" queue (get message "id"))
      (generate-string {:reservation_id (get message "reservation_id")
                        :delay delay}))))
