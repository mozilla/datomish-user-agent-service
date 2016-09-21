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

;; TODO: consider doing this locally.
(cljs-promises.async/extend-promises-as-pair-channels!)

(defn <fetch
  ([url]
   (<fetch url {}))
  ([url options]
   (let [defaults
         {:method "GET"
          :headers {:Accept "application/json"
                    :Content-Type "application/json"}}

         options
         (merge defaults options)]

     (go-pair
       (let [res  (<? (js/fetch url (clj->js options)))
             body (<? (.json res))
             ]
         (if (.-ok res)
           (js->clj body :keywordize-keys true)
           (datomish.util/raise (str "Failed to " (:method options)) {:url url :options options :body body :res res})))))))

(defn <post
  ([url body]
   (<post url body {}))
  ([url body options]
   (<fetch url (merge options {:method "POST" :body (js/JSON.stringify (clj->js body))}))))


(def <get <fetch)

(defn- dissoc-timestamps [key pages]
  ;; TODO: verify timestamps are microseconds.
  (map #(dissoc % key) pages))

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

(deftest-async test-visits
  (let [server (core/server 3002)]
    (try
      (let [{s :session} (<? (<post "http://localhost:3002/v1/session/start" {}))]
        ;; No title.
        (is (= (<? (<post "http://localhost:3002/v1/visits" {:session s
                                                             :url "https://reddit.com/"}))
               {}))
        ;; With title.
        (is (= (<? (<post "http://localhost:3002/v1/visits" {:session s
                                                             :url "https://www.mozilla.org/en-US/firefox/new/"
                                                             :title "Download Firefox - Free Web Browser"}))
               {}))
        ;; TODO: 400 with no URL or no session (or invalid URL?).

        (is (= (dissoc-timestamps :lastVisited (:pages (<? (<get "http://localhost:3002/v1/visits?limit=2"))))
               [{:url "https://www.mozilla.org/en-US/firefox/new/",
                 :title "Download Firefox - Free Web Browser"}
                {:url "https://reddit.com/",
                 :title ""}]))

        (is (= (dissoc-timestamps :lastVisited (:pages (<? (<get "http://localhost:3002/v1/visits?limit=1"))))
               [{:url "https://www.mozilla.org/en-US/firefox/new/",
                 :title "Download Firefox - Free Web Browser"}])))
      (finally (.close server)))))

(deftest-async test-stars
  (let [server (core/server 3002)]
    (try
      (let [{s :session} (<? (<post "http://localhost:3002/v1/session/start" {}))]
        ;; TODO: Allow no title.
        (is (= (<? (<post "http://localhost:3002/v1/stars/star"
                          {:url "https://reddit.com/"
                           :title "reddit - the front page of the internet"
                           :session s}))
               {}))

        ;; With title.
        (is (= (<? (<post "http://localhost:3002/v1/stars/star"
                          {:url "https://www.mozilla.org/en-US/firefox/new/"
                           :title "Download Firefox - Free Web Browser"
                           :session s}))
               {}))

        (is (= (dissoc-timestamps :starredOn (:results (<? (<get "http://localhost:3002/v1/stars"))))
               [{:url "https://www.mozilla.org/en-US/firefox/new/",
                 :title "Download Firefox - Free Web Browser"}
                {:url "https://reddit.com/",
                 :title "reddit - the front page of the internet"}
                ]))

        (is (= (dissoc-timestamps :starredOn (:results (<? (<get "http://localhost:3002/v1/stars?limit=1"))))
               [{:url "https://www.mozilla.org/en-US/firefox/new/",
                 :title "Download Firefox - Free Web Browser"}
                ]))

        (is (= (<? (<post "http://localhost:3002/v1/stars/unstar"
                          {:url "https://www.mozilla.org/en-US/firefox/new/"
                           :session s}))
               {}))

        (is (= (dissoc-timestamps :starredOn (:results (<? (<get "http://localhost:3002/v1/stars"))))
               [{:url "https://reddit.com/",
                 :title "reddit - the front page of the internet"}
                ]))

        )
      (finally (.close server)))))
