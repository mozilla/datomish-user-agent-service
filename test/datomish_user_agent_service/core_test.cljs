;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns datomish-user-agent-service.core-test
  (:require-macros
   [datomish.pair-chan :refer [go-pair <?]]
   [datomish.test-macros :refer [deftest-async]]
   ;; [datomish.node-tempfile-macros :refer [with-tempfile]]
   [cljs.core.async.macros])
  (:require
   ;; [datomish.node-tempfile :refer [tempfile]]
   [cljs.nodejs :as nodejs]
   [cljs.core.async :as a :refer [<! >!]]
   [cljs.test :refer-macros [is are deftest testing async]]
   [cljs.spec :as s]
   ;; [cljs-http.client :as http]
   [cljs-promises.async]

   [datomish.api :as d]

   [datomish.js-sqlite] ;; Otherwise, we won't have the ISQLiteConnectionFactory defns.
   [datomish.pair-chan]
   [datomish.util]
   [datomish-user-agent-service.core :as core]
   ))

(nodejs/require "isomorphic-fetch")

(defn <fetch
  ([url]
   (<fetch url {}))
  ([url options]
   (let [defaults
         {:method "GET"
          :headers {:Accept "application/json"
                    :Content-Type "application/json"}}

         options
         (merge defaults options)

         ->json-body
         (fn [res]
           (if-not (.-ok res)
             (datomish.util/raise "Failed to fetch" {:url url :options options :res res}) ;; Not helpful, since res.body is a promise.
             (.json res)))]
     (cljs-promises.async/pair-port
       (->
         (js/fetch url (clj->js options))
         (.then ->json-body)
         (.catch (fn [e] (datomish.util/raise "Failed to fetch" {:url url :options options :error e})))
         (.then #(js->clj % :keywordize-keys true))
         )))))

(defn <post
  ([url body]
   (<post url body {}))
  ([url body options]
   (<fetch url (merge options {:method "POST" :body (js/JSON.stringify (clj->js body))}))))

(def <get <fetch)

(deftest-async b-test
  (testing "FIXME, I fail."
    (is (= nil nil))
    ))

(deftest-async test-heartbeat
  (let [server (core/server 3002)]
    (try
      (is (= (<? (<get "http://localhost:3002/__heartbeat__")) {}))
      (finally (.close server)))))

(deftest-async test-session
  (let [server (core/server 3002)]
    (try
      (let [{s1 :session} (<? (<post "http://localhost:3002/v1/session/start" {}))
            {s2 :session} (<? (<post "http://localhost:3002/v1/session/start" {:scope s1}))]
        (is (number? s1))
        (is (number? s2))
        (is (= s1 (dec s2)))

        (is (= (<? (<post "http://localhost:3002/v1/session/end" {:session s1})) {}))
        (is (= (<? (<post "http://localhost:3002/v1/session/end" {:session s2})) {}))

        ;; TODO: 404 the second time through.
        (is (= (<? (<post "http://localhost:3002/v1/session/end" {:session s1})) {})))

      (finally (.close server)))))
