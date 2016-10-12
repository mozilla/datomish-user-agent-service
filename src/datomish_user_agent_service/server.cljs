;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns ^:figwheel-always datomish-user-agent-service.server
  (:require-macros
   [datomish.pair-chan :refer [go-pair <?]]
   [datomish.promises :refer [go-promise]]
   [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [cljs.reader]
            [cljs-promises.async]
            [cljs-promises.core :refer [promise]]
            [datomish.api :as d]
            [datomish.datom]
            [datomish.db]
            [datomish.js-sqlite] ;; Otherwise, we won't have the ISQLiteConnectionFactory defns.
            [datomish.pair-chan]
            [datomish.promises]
            [datomish-user-agent-service.api :as api]
            [cljs.core.async :as a :refer [chan <! >!]]))

;; TODO: move this to Datomish.
;; TODO: figure out what to do with TempIds in JS.
(extend-type datomish.datom/Datom
  IEncodeJS
  (-clj->js [d] (clj->js {:e (.-e d) :a (.-a d) :v (.-v d) :tx (.-tx d) :added (.-added d)})))

(extend-type datomish.datom/Datom
  IPrintWithWriter
  (-pr-writer [d writer opts]
    (pr-sequential-writer writer pr-writer
                          "#db/datom [" " " "]"
                          opts [(.-e d) (.-a d) (.-v d) (.-tx d) (.-added d)])))

(extend-type datomish.db/TempId
  IPrintWithWriter
  (-pr-writer [id-literal writer opts]
    (pr-sequential-writer writer pr-writer
                          "#db/id [" " " "]"
                          opts [(:part id-literal) (:idx id-literal)])))

(extend-type datomish.db/LookupRef
  IPrintWithWriter
  (-pr-writer [id-literal writer opts]
    (pr-sequential-writer writer pr-writer
                          "#db/ref [" " " "]"
                          opts [(:a id-literal) (:v id-literal)])))

(cljs.reader/register-tag-parser! "db/datom" (partial apply datomish.datom/datom))

(cljs.reader/register-tag-parser! "db/id" (partial apply datomish.db/id-literal))

(cljs.reader/register-tag-parser! "db/ref" (partial apply datomish.db/lookup-ref))

(.install (nodejs/require "source-map-support"))

(defonce http (nodejs/require "http"))
(defonce express (nodejs/require "express"))
(defonce expressValidator (nodejs/require "express-validator"))
(defonce expressWs (nodejs/require "express-ws-routes"))
;; Monkeypatch!
(.extendExpress expressWs #js {})
(defonce bodyParser (nodejs/require "body-parser"))
(defonce morgan (nodejs/require "morgan"))

;; TODO: validate in CLJS.
(defn- auto-caught-route-error [validator method]
  (fn [req res next]
    (go-promise
      identity

      (try
        (when validator
          (validator req))
        (let [errors (.validationErrors req)]
          (if errors
            (doto res
              (.status 401)
              (.json (clj->js errors)))
            ;; TODO: .catch errors in method?
            (<? (method req res next))))
        (catch :default e
          (js/console.log "caught error" e)
          (doto res
            (.status 500)
            (.json (clj->js {:error (clojure.string/split (aget e "stack") "\n")})))
          )))))

(defn- api-router [connection-pair-chan]
  ;; TODO: use transaction listeners to propagate diffs independent of /v1 namespace.
  (doto (-> express .Router)

    (.get "/query"
          (auto-caught-route-error
            (fn [req]
              (-> req
                  (.checkQuery "q")
                  (.notEmpty))
              (-> req
                  (.checkQuery "limit") ;; TODO: respect limit; or remove limit parameter and use rnewman's `args`.
                  (.optional)
                  (.isInt)))
            (fn [req res]
              (go-pair
                (let [q-str   (aget req "query" "q")
                      q-edn   (cljs.reader/read-string q-str)
                      results (<? (d/<q (d/db (<? connection-pair-chan)) q-edn))]
                  (cond
                    (.accepts req "json")
                    (doto res
                      (.json (clj->js results)))

                    (.accepts req "application/edn")
                    (doto res
                      (.set "Content-Type" "application/edn") ;; TODO: charset?
                      (.send (prn-str results)))

                    :default
                    (doto res
                      (.status 406)
                      (.send #js {:error "Not Acceptable"}))
                    ))))))

    (.post "/transact"
           (auto-caught-route-error
             (fn [req])
             (fn [req res]
               (go-pair
                 (if-not (.is req "application/edn") ;; TODO: accept application/json?
                   (doto res
                     (.status 415)
                     (.json (clj->js {:error "Unsupported Media Type"})))
                   (let [body    (aget req "body")
                         edn     (cljs.reader/read-string body)
                         tx-data (:tx-data edn)
                         report  (<? (d/<transact! (<? connection-pair-chan) tx-data))
                         results (select-keys report [:tx-data :tempids])]
                     (cond
                       (.accepts req "json")
                       (doto res
                         (.json (clj->js results)))

                       (.accepts req "application/edn")
                       (doto res
                         (.set "Content-Type" "application/edn") ;; TODO: charset?
                         (.send (prn-str results)))

                       :default
                       (doto res
                         (.status 406)
                         (.send #js {:error "Not Acceptable"}))
                       )))))))
    ))

(defn- v1-router [connection-pair-chan]
  (let [ws-clients
        (atom #{})

        diff
        (fn [type payload]
          (println "Sending diff message of type" type "and payload" payload "to" (count @ws-clients) "clients")
          (let [message (js/JSON.stringify (clj->js {:message "diff" :type type :payload payload}))]
            (go-pair
              (doseq [ws @ws-clients]
                (.send ws message)))))

        send-bookmark-diffs
        (fn []
          (go-promise
            identity

            (let [results
                  (->>
                    (<? (api/<starred-pages (d/db (<? connection-pair-chan))
                                            {:limit 100} ;; TODO - js/Number.MAX_SAFE_INTEGER
                                            ))
                    (map :url))]
              (<? (diff "PROFILE_DIFF_BOOKMARKS" results))
              (<? (diff "PROFILE_DIFF_RECENT_BOOKMARKS" results)))))
        ]

    (doto (-> express .Router)

      (.websocket "/ws" (fn [info cb next]
                          (cb (fn [ws]
                                (go-promise
                                  identity

                                  (swap! ws-clients conj ws)
                                  (.on ws "close" (fn [] (swap! ws-clients disj ws)))

                                  (.send ws (js/JSON.stringify #js {:message "protocol"
                                                                    :version "v1"
                                                                    :clientCount (count @ws-clients)}))
                                  (.send ws (js/JSON.stringify #js {:type "connected"}))

                                  ;; Asynchronously send bookmark diffs.
                                  (send-bookmark-diffs)

                                  )))))


      ;; TODO: write a small macro to cut down this boilerplate.
      (.post "/sessions/start"
             (auto-caught-route-error
               (fn [req]
                 (-> req
                     (.checkBody "scope")
                     (.optional)
                     (.isInt))
                 (-> req
                     (.checkBody "ancestor")
                     (.optional)
                     (.isInt))
                 )
               (fn [req res]
                 (go-pair
                   (let [session (<? (api/<start-session (<? connection-pair-chan)
                                                         {:ancestor (aget req "body" "ancestor")
                                                          :scope (aget req "body" "scope")}))]
                     (.json res (clj->js {:session session})))))))

      (.post "/sessions/end"
             (auto-caught-route-error
               (fn [req]
                 (-> req
                     (.checkBody "session")
                     (.notEmpty)
                     (.isInt))
                 )
               (fn [req res]
                 (go-pair
                   (let [_ (<? (api/<end-session (<? connection-pair-chan)
                                                 {:session (aget req "body" "session")}))]
                     (.json res (clj->js {})))))))

      (.post "/visits/visit"
             (auto-caught-route-error
               (fn [req]
                 (-> req
                     (.checkBody "url")
                     (.notEmpty))
                 (-> req
                     (.checkBody "title")
                     (.optional))
                 (-> req
                     (.checkBody "session")
                     (.notEmpty)
                     (.isInt))
                 )
               (fn [req res]
                 (go-pair
                   (let [_ (<? (api/<add-visit (<? connection-pair-chan)
                                               {:url (aget req "body" "url")
                                                :title (aget req "body" "title")
                                                :session (aget req "body" "session")}))]
                     (.json res (clj->js {})))))))

      (.get "/visits"
            (auto-caught-route-error
              (fn [req]
                (-> req
                    (.checkQuery "limit")
                    (.optional)
                    (.isInt))
                )
              (fn [req res]
                (go-pair
                  (let [results (<? (api/<visited (d/db (<? connection-pair-chan))
                                                  {:limit (int (aget req "query" "limit"))}))]
                    (.json res (clj->js {:results results})))))))

      (.post "/stars/star"
             (auto-caught-route-error
               (fn [req]
                 (-> req
                     (.checkBody "url")
                     (.notEmpty))
                 (-> req
                     (.checkBody "title")
                     (.optional))
                 (-> req
                     (.checkBody "session")
                     (.notEmpty)
                     (.isInt))
                 )
               (fn [req res]
                 (go-pair
                   (let [_ (<? (api/<star-page (<? connection-pair-chan)
                                               {:url (aget req "body" "url")
                                                :title (aget req "body" "title") ;; TODO: allow no title.
                                                :starred true
                                                :session (int (aget req "body" "session"))}))]

                     ;; Asynchronously send bookmark diffs.
                     (send-bookmark-diffs)

                     (.json res (clj->js {})))))))

      (.post "/stars/unstar"
             (auto-caught-route-error
               (fn [req]
                 (-> req
                     (.checkBody "url")
                     (.notEmpty))
                 (-> req
                     (.checkBody "session")
                     (.notEmpty)
                     (.isInt))
                 )
               (fn [req res]
                 (go-pair
                   (let [_ (<? (api/<star-page (<? connection-pair-chan)
                                               {:url (aget req "body" "url")
                                                :starred false
                                                :session (int (aget req "body" "session"))}))]

                     ;; Asynchronously send bookmark diffs.
                     (send-bookmark-diffs)

                     (.json res (clj->js {})))))))

      (.get "/stars"
            (auto-caught-route-error
              (fn [req]
                (-> req
                    (.checkQuery "limit")
                    (.optional)
                    (.isInt))
                )
              (fn [req res]
                (go-pair
                  (let [results (<? (api/<starred-pages (d/db (<? connection-pair-chan))
                                                        {:limit (int (or (aget req "query" "limit") 100))} ;; TODO - js/Number.MAX_SAFE_INTEGER
                                                        ))]
                    (.json res (clj->js {:results results})))))))

      (.post "/pages/page"
             (auto-caught-route-error
               (fn [req]
                 (-> req
                     (.checkBody "url")
                     (.notEmpty))
                 (-> req
                     (.checkBody #js ["page" "textContent"]) ;; #js is required here and below.
                     (.notEmpty))
                 (-> req
                     (.checkBody #js ["page" "title"])
                     (.optional))
                 (-> req
                     (.checkBody #js ["page" "excerpt"])
                     (.optional))
                 (-> req
                     (.checkBody "session")
                     (.notEmpty)
                     (.isInt))
                 )
               (fn [req res]
                 (go-pair
                   (let [_ (<? (api/<save-page (<? connection-pair-chan)
                                               {:url (aget req "body" "url")
                                                :title (aget req "body" "page" "title")
                                                :excerpt (aget req "body" "page" "excerpt")
                                                :content (aget req "body" "page" "textContent")
                                                :session (int (aget req "body" "session"))}))]
                     (.json res (clj->js {})))))))

      (.get "/pages"
            (auto-caught-route-error
              (fn [req]
                (-> req
                    (.checkQuery "q")
                    (.notEmpty))
                (-> req
                    (.checkQuery "limit")
                    (.optional)
                    (.isInt))
                (-> req
                    (.checkQuery "since")
                    (.optional)
                    (.isInt))
                (-> req
                    (.checkQuery "snippetSize")
                    (.optional))
                )
              (fn [req res]
                (go-pair
                  (let [results (<? (api/<pages-matching-string
                                      (d/db (<? connection-pair-chan))
                                      (aget req "query" "q")
                                      {:limit (int (or (-> req .-query .-limit) 10)) ;; TODO - js/Number.MAX_SAFE_INTEGER
                                       :since (-> req .-query .-since)}))]
                    (.json res (clj->js {:results results})))))))
      )))

(defn cross-origin-handler [contentServiceOrigin]
  (fn [req res next]
    (let [origin (.get req "origin")]
      (when (and origin (clojure.string/starts-with? origin contentServiceOrigin))
        ;; For some reason, setting the `Access-Control-Allow-Origin` header to the
        ;; `contentServiceOrigin` value doesn't work for our custom `tofino://` http scheme, when
        ;; receiving requests from electron. For example, when allowing origin `tofino://` and the
        ;; request is from `tofino://history`, CORS won't work even though it should. As a
        ;; workaround, whitelist directly.
        (doto res
          (.header "Access-Control-Allow-Origin" origin)
          (.header "Access-Control-Allow-Methods" "GET,PUT,POST,DELETE")
          (.header "Access-Control-Allow-Headers" "Content-Type")))
      (next))))

(defn- error-handler [err req res next]
  (doto res
    (.status 500)
    (.json (clj->js {:error err}))))

(defn- not-found-handler [req res next]
  (doto res
    (.status 404)
    (.json (clj->js {:url (aget req "originalUrl")}))))

;; TODO: logging throughout.
(defn app [connection-pair-chan]
  (doto (express)
    (.use (morgan "dev"))

    (.use (cross-origin-handler "tofino://")) ;; TODO: parameterize origin.

    (.use (.json bodyParser #js {:type "application/json"}))
    (.use (.text bodyParser #js {:type "application/edn"}))
    (.use (.urlencoded bodyParser #js {:extended false :type "application/x-www-form-urlencoded"}))

    (.use (expressValidator))
    (.use "/v1" (v1-router connection-pair-chan))
    (.use "/api" (api-router connection-pair-chan))

    (.get "/__heartbeat__"
          (fn [req res] (.json res (clj->js {:version "v1"}))))

    (.use not-found-handler)
    (.use error-handler)))

(defn createServer [request-listener {:keys [port hostname]
                                      :or {port 9090
                                           hostname "localhost"}}]
  (let [server
        (.createServer http request-listener)

        ;; There's magic around `handle` here: this is arranging to not need to capture the Express
        ;; Application directly; handle is an internal detail of express-ws-routes.
        _
        (aset server "wsServer" (.createWebSocketServer expressWs server #js {:handle request-listener}))

        start
        (fn []
          (promise (fn [resolve reject]
                     (.listen server port hostname resolve))))

        stop
        (fn []
          (promise (fn [resolve reject]
                     (.close server (fn [err] (if err
                                                (reject err)
                                                (resolve)))))))]
    [start stop server]))

(defn <connect [path]
  ;; Using pair-port and go-promise works around issues seen using a promise-chan in this way.
  (cljs-promises.async/pair-port
    (go-promise
      identity

      (let [c (<? (d/<connect path))
            _ (<? (d/<transact! c api/tofino-schema))] ;; TODO: don't do try to write.
        c))))
