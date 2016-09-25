(ns ^:figwheel-always datomish-user-agent-service.server
  (:require-macros
   [datomish.pair-chan :refer [go-pair <?]]
   [datomish.promises :refer [go-promise]]
   [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [cljs-promises.async]
            [cljs-promises.core :refer [promise]]
            [datomish.api :as d]
            [datomish.js-sqlite] ;; Otherwise, we won't have the ISQLiteConnectionFactory defns.
            [datomish.pair-chan]
            [datomish.promises]
            [datomish-user-agent-service.api :as api]
            [cljs.core.async :as a :refer [chan <! >!]]))

(.install (nodejs/require "source-map-support"))

(defonce http (nodejs/require "http"))
(defonce express (nodejs/require "express"))
(defonce expressValidator (nodejs/require "express-validator"))
(defonce expressWs (nodejs/require "express-ws-routes"))
;; Monkeypatch!
(.extendExpress expressWs #js {})
(defonce bodyParser (nodejs/require "body-parser"))

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

(defn- router [connection-pair-chan]
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

            (let [results (<? (api/<starred-pages (d/db (<? connection-pair-chan)) ;; TODO -- unify on conn over db?
                                                  {:limit 100} ;; TODO - js/Number.MAX_SAFE_INTEGER
                                                  ))]
              (<? (diff "PROFILE_DIFF_BOOKMARKS" (map :url results)))
              (<? (diff "PROFILE_DIFF_RECENT_BOOKMARKS" (map :url results))))))
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
                  (let [results (<? (api/<visited (d/db (<? connection-pair-chan)) ;; TODO -- unify on conn over db?
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
                  (let [results (<? (api/<starred-pages (d/db (<? connection-pair-chan)) ;; TODO -- unify on conn over db?
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
                  (let [results (<? (api/<saved-pages-matching-string (d/db (<? connection-pair-chan)) ;; TODO -- unify on conn over db?
                                                                      (aget req "query" "q")
                                                                      ;; {:limit (int (or (-> req .-query .-limit) 100))} ;; TODO - js/Number.MAX_SAFE_INTEGER
                                                                      ))]
                    (.json res (clj->js {:results results}))))))))))

(defn- log-handler [req res next]
  (println "req" req)
  (next))

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
  (let [router
        (router connection-pair-chan)

        app
        (doto (express)
          (.use log-handler)

          (.use (.json bodyParser))
          (.use (expressValidator))
          (.use "/v1" router)

          (.get "/__heartbeat__"
                (fn [req res] (.json res (clj->js {:version "v1"}))))

          (.use not-found-handler)
          (.use error-handler))]
    app))

(defn createServer [request-listener {:keys [port]
                                      :or {port 9090}}]
  (let [server
        (.createServer http request-listener)

        ;; There's magic around `handle` here: this is arranging to not need to capture the Express
        ;; Application directly; handle is an internal detail of express-ws-routes.
        _
        (aset server "wsServer" (.createWebSocketServer expressWs server #js {:handle request-listener}))

        start
        (fn []
          (promise (fn [resolve reject]
                     (.listen server port resolve))))

        stop
        (fn []
          (promise (fn [resolve reject]
                     (.close server (fn [err] (if err
                                                (reject err)
                                                (resolve)))))))]
    [start stop server]))
