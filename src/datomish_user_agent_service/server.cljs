(ns ^:figwheel-always datomish-user-agent-service.server
  (:require-macros
   [datomish.pair-chan :refer [go-pair <?]]
   [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [datomish.api :as d]
            [datomish.js-sqlite] ;; Otherwise, we won't have the ISQLiteConnectionFactory defns.
            [datomish.pair-chan]
            [datomish-user-agent-service.api :as api]
            [cljs.core.async :as a :refer [chan <! >!]]))


(defonce express (nodejs/require "express"))
(defonce expressValidator (nodejs/require "express-validator"))
(defonce bodyParser (nodejs/require "body-parser"))

;; TODO: validate in CLJS.
(defn- auto-caught-route-error [validator method]
  (fn [req res next]
    (go-pair
      (try
        (when validator
          (validator req))
        (let [errors (.validationErrors req)]
          (if errors
            ;; TODO: log.
            (doto res
              (.status 401)
              (.json (clj->js errors)))
            ;; TODO: .catch errors in method?
            (<? (method req res next))))
        (catch js/Error e
          (js/console.log "caught error" e)
          (doto res
            (.status 500)
            (.json (clj->js {:error (clojure.string/split (aget e "stack") "\n")})))
          )))))

(defn- router [connection-pair-chan]
  (doto (-> express .Router)

    ;; TODO: write a small macro to cut down this boilerplate.
    (.post "/session/start"
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

    (.post "/session/end"
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

    (.post "/visits"
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
                  (.json res (clj->js {:pages results})))))))

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
                   ;; TODO: dispatch bookmark diffs to WS.
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
                   ;; TODO: dispatch bookmark diffs to WS.
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

    (.post "/pages"
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
                   ;; TODO: dispatch bookmark diffs to WS.
                   (.json res (clj->js {})))))))

    (.get "/query"
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
                  (.json res (clj->js {:results results})))))))))

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
    (.use (.json bodyParser))
    (.use (expressValidator))
    (.use "/v1" (router connection-pair-chan))

    (.get "/__heartbeat__" 
          (fn [req res] (.json res (clj->js {:version "v1"}))))

    (.use not-found-handler)
    (.use error-handler)))
