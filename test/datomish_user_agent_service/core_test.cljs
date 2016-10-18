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

   [datomish-user-agent-service.api :as api]
   [datomish-user-agent-service.core :as core]
   [datomish-user-agent-service.server :as server]
   ))

(.install (nodejs/require "source-map-support"))

(nodejs/require "isomorphic-fetch")

;; TODO: consider doing this locally.
(cljs-promises.async/extend-promises-as-pair-channels!)

(defn <server [port]
  (go-pair
    (let [connection-pair-chan
          (server/<connect "") ;; The channel, not the value!

          app
          (server/app connection-pair-chan)

          [start stop]
          (server/createServer app {:port port})]
      ;; `start` returns a promise that's resolved or rejected when the
      ;; server is started. We use cljs-promises to turn this into a channel,
      ;; then wait on it.
      (<?
        (cljs-promises.async/pair-port
          (start)))
      stop)))

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
  (let [stop (<? (<server 3002))]
    (try
      (is (= (<? (<get "http://localhost:3002/__heartbeat__")) {:version "v1"}))
      (finally (<? (stop))))))

(deftest-async test-session
  (let [stop (<? (<server 3002))]
    (try
      (let [{s1 :session} (<? (<post "http://localhost:3002/v1/sessions/start" {}))
            {s2 :session} (<? (<post "http://localhost:3002/v1/sessions/start" {:scope s1}))]
        (is (number? s1))
        (is (number? s2))
        (is (= s1 (dec s2)))

        (is (= (<? (<post "http://localhost:3002/v1/sessions/end" {:session s1})) {}))
        (is (= (<? (<post "http://localhost:3002/v1/sessions/end" {:session s2})) {}))

        ;; TODO: 404 the second time through.
        (is (= (<? (<post "http://localhost:3002/v1/sessions/end" {:session s1})) {})))

      (finally (<? (stop))))))

(deftest-async test-visits
  (let [stop (<? (<server 3002))]
    (try
      (let [{s :session} (<? (<post "http://localhost:3002/v1/sessions/start" {}))]
        ;; No title.
        (is (= (<? (<post "http://localhost:3002/v1/visits/visit" {:session s
                                                                   :url "https://reddit.com/"}))
               {}))
        ;; With title.
        (is (= (<? (<post "http://localhost:3002/v1/visits/visit" {:session s
                                                                   :url "https://www.mozilla.org/en-US/firefox/new/"
                                                                   :title "Download Firefox - Free Web Browser"}))
               {}))
        ;; TODO: 400 with no URL or no session (or invalid URL?).

        (is (= (dissoc-timestamps :lastVisited (:results (<? (<get "http://localhost:3002/v1/visits?limit=2"))))
               [{:url "https://www.mozilla.org/en-US/firefox/new/",
                 :title "Download Firefox - Free Web Browser"
                 :snippet ""}
                {:url "https://reddit.com/",
                 :title ""
                 :snippet ""}]))

        (is (= (dissoc-timestamps :lastVisited (:results (<? (<get "http://localhost:3002/v1/visits?limit=1"))))
               [{:url "https://www.mozilla.org/en-US/firefox/new/",
                 :title "Download Firefox - Free Web Browser"
                 :snippet ""}])))
      (finally (<? (stop))))))

(deftest-async test-stars
  (let [stop (<? (<server 3002))]
    (try
      (let [{s :session} (<? (<post "http://localhost:3002/v1/sessions/start" {}))]
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
      (finally (<? (stop))))))

(deftest-async test-page-details
  (let [stop (<? (<server 3002))]
    (try
      (let [{s :session} (<? (<post "http://localhost:3002/v1/sessions/start" {}))]
        (is (= (<? (<post "http://localhost:3002/v1/pages/page"
                          {:session s
                           :url "https://test.domain/"
                           :page {:title "test title"
                                  :excerpt "excerpt from test.domain"
                                  :textContent "Long text content containing excerpt from test.domain and other stuff"
                                  }}))
               {}))

        (is (= (<? (<post "http://localhost:3002/v1/pages/page"
                          {:session s
                           :url "https://another.domain/"
                           :page {:title "another title"
                                  :excerpt "excerpt from another.domain"
                                  :textContent "Long text content containing excerpt from another.domain and other stuff"
                                  }}))
               {}))

        (is (= (dissoc-timestamps :lastVisited (:results (<? (<get "http://localhost:3002/v1/pages?q=Long%20text&snippetSize=tiny"))))
               [{:url "https://test.domain/",
                 :title "test title"
                 :excerpt "excerpt from test.domain"
                 :snippet "" ;; TODO: support snippets.
                 ;; :snippet "<b>Long</b> <b>text</b> content containing excerpt…"
                 }
                {:url "https://another.domain/",
                 :title "another title"
                 :excerpt "excerpt from another.domain"
                 :snippet ""
                 ;; :snippet "<b>Long</b> <b>text</b> content containing excerpt…"
                 }
                ]))

        ;; ;; TODO: support limit, since.
        ;; (is (= (dissoc-timestamps :lastVisited (:results (<? (<get "http://localhost:3002/v1/query?q=Long%20text&snippetSize=large&limit=1"))))
        ;;        [{:url "https://another.domain/",
        ;;          :title "another title"
        ;;          :snippet "<b>Long</b> <b>text</b> content containing excerpt from another.domain and other stuff"}
        ;;         ]))

        )
      (finally (<? (stop))))))
