(ns cerber.stores.client
  (:require [mount.core :refer [defstate]]
            [cerber
             [config :refer [app-config]]
             [db :as db]
             [store :refer :all]]
            [clojure.string :as str]
            [failjure.core :as f]
            [cerber.error :as error]
            [cerber.stores.token :as token]
            [cerber.helpers :as helpers]))

(declare ->map init-clients)

(defrecord Client [id secret info redirects grants scopes])

(defrecord SqlClientStore []
  Store
  (fetch-one [this [client-id]]
    (->map (first (db/find-client {:id client-id}))))
  (revoke-one! [this [client-id]]
    (db/delete-client {:id client-id}))
  (store! [this k client]
    (when (= 1 (db/insert-client client)) client))
  (purge! [this]
    (db/clear-clients)))

(defmulti create-client-store identity)

(defmacro with-client-store
  "Changes default binding to default client store."
  [store & body]
  `(binding [*client-store* ~store] ~@body))

(defstate ^:dynamic *client-store*
  :start (create-client-store (-> app-config :clients :store)))

(defstate defined-clients
  :start (init-clients (-> app-config :clients :defined)))

(defmethod create-client-store :in-memory [_]
  (->MemoryStore "clients" (atom {})))

(defmethod create-client-store :redis [_]
  (->RedisStore "clients" (:redis-spec app-config)))

(defmethod create-client-store :sql [_]
  (->SqlClientStore))

(defn validate-uri
  "Returns java.net.URL instance of given uri or failure info in case of error."
  [uri]
  (if (empty? uri)
    (error/internal-error "redirect-uri cannot be empty")
    (if (or (>= (.indexOf uri "#") 0)
            (>= (.indexOf uri " ") 0)
            (>= (.indexOf uri "..") 0))
      (error/internal-error "Illegal characters in redirect URI")
      (try
        (java.net.URL. uri)
        (catch Exception e (error/internal-error (.getMessage e)))))))

(defn validate-redirects
  "Goes through all redirects and returns list of validation failures."
  [redirects]
  (filter f/failed? (map validate-uri redirects)))

(defn revoke-client
  "Revokes previously generated client and all tokens generated to this client so far."
  [client-id]
  (revoke-one! *client-store* [client-id])
  (token/revoke-by-pattern [client-id "*"]))

(defn create-client
  "Creates new client"
  [info redirects grants scopes approved? & [id secret]]
  (let [result (validate-redirects redirects)
        client {:id (or id (helpers/generate-secret))
                :secret (or secret (helpers/generate-secret))
                :info info
                :approved? approved?
                :scopes (helpers/vec->str scopes)
                :grants (helpers/vec->str grants)
                :redirects (helpers/vec->str redirects)
                :created-at (helpers/now)}]

    (if (empty? result)
      (if (store! *client-store* [:id] client)
        (map->Client (assoc client
                            :scopes (or scopes [])
                            :grants (or grants [])
                            :redirects (or redirects [])))
        (error/internal-error "Cannot store client"))
      (first result))))

(defn find-client [client-id]
  (if-let [found (and client-id (fetch-one *client-store* [client-id]))]
    (let [{:keys [approved scopes grants redirects]} found]
      (map->Client found))))

(defn purge-clients
  []
  "Removes clients from store. Used for tests only."
  (purge! *client-store*))

(defn init-clients
  "Initializes configured clients."
  [clients]
  (f/try*
   (reduce (fn [reduced {:keys [id secret info redirects grants scopes approved?]}]
             (conj reduced (create-client info redirects grants scopes approved? id secret)))
           {}
           clients)))

(defn grant-allowed? [client grant]
  (let [grants (:grants client)]
    (or (empty? grants)
        (.contains grants grant))))

(defn redirect-uri-valid? [client redirect-uri]
  (.contains (:redirects client) redirect-uri))

(defn scopes-valid?
  "Checks whether given scopes are valid ones assigned to client."
  [client scopes]
  (let [client-scopes (:scopes client)]
    (or (empty? scopes)
        (every? #(.contains client-scopes %) scopes))))

(defn ->map [result]
  (when-let [{:keys [approved scopes grants redirects]} result]
    (-> result
        (assoc  :approved? approved
                :scopes (helpers/str->vec scopes)
                :grants (helpers/str->vec grants)
                :redirects (helpers/str->vec redirects))
        (dissoc :approved))))
