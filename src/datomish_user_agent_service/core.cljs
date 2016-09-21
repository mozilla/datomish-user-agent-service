(ns ^:figwheel-always datomish-user-agent-service.core
  (:require-macros
   [datomish.pair-chan :refer [go-pair <?]]
   [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [datomish.api :as d]
            [datomish.js-sqlite] ;; Otherwise, we won't have the ISQLiteConnectionFactory defns.
            [datomish.pair-chan]
            [datomish-user-agent-service.api :as api]
            [cljs.core.async :as a :refer [chan <! >!]]))

(enable-console-print!)

(.install (nodejs/require "source-map-support"))

(defonce express (nodejs/require "express"))
(defonce http (nodejs/require "http"))
(defonce expressValidator (nodejs/require "express-validator"))
(defonce bodyParser (nodejs/require "body-parser"))

;; define your app data so that it doesn't get over-written on reload
(defonce app-state
  (go-pair
    (js/console.log "Opening Datomish knowledge-base.")
    (let [c (<? (d/<connect "")) ;; In-memory for now.
          _ (<? (d/<transact! c api/tofino-schema))]
      c)))

(defn on-js-reload []
  (js/console.log "on-js-reload"))

;; app gets redefined on reload.
(def app (express))

(def router (. express Router))

(doto app
  (.use (.json bodyParser))
  (.use (expressValidator))
  (.use "/v1" router))

;; routes get redefined on each reload.
(. app (get "/__heartbeat__" 
            (fn [req res] (. res (json (clj->js {}))))))

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
            (.json (clj->js {:error (clojure.string/split (.-stack e) "\n")})))
          )))))

;; TODO: write a small macro to cut down this boilerplate.
(. router (post "/session/start"
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
                      (let [session (<? (api/<start-session (<? app-state)
                                                            {:ancestor (-> req .-body .-ancestor)
                                                             :scope (-> req .-body .-scope)}))]
                        (. res (json (clj->js {:session session})))))))))

(. router (post "/session/end"
                (auto-caught-route-error
                  (fn [req]
                    (-> req
                        (.checkBody "session")
                        (.notEmpty)
                        (.isInt))
                    )
                  (fn [req res]
                    (go-pair
                      (let [_ (<? (api/<end-session (<? app-state)
                                                    {:session (-> req .-body .-session)}))]
                        (. res (json (clj->js {})))))))))

(. router (post "/visits"
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
                      (let [_ (<? (api/<add-visit (<? app-state)
                                                  {:url (-> req .-body .-url)
                                                   :title (-> req .-body .-title)
                                                   :session (-> req .-body .-session)}))]
                        (. res (json (clj->js {})))))))))

(. router (get "/visits"
               (auto-caught-route-error
                 (fn [req]
                   (-> req
                       (.checkQuery "limit")
                       (.optional)
                       (.isInt))
                   )
                 (fn [req res]
                   (go-pair
                     (let [results (<? (api/<visited (d/db (<? app-state)) ;; TODO -- unify on conn over db?
                                                     {:limit (int (-> req .-query .-limit))}))]
                       (. res (json (clj->js {:pages results})))))))))

(. router (post "/stars/star"
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
                      (let [_ (<? (api/<star-page (<? app-state)
                                                  {:url (-> req .-body .-url)
                                                   :title (-> req .-body .-title) ;; TODO: allow no title.
                                                   :starred true
                                                   :session (int (-> req .-body .-session))}))]
                        ;; TODO: dispatch bookmark diffs to WS.
                        (. res (json (clj->js {})))))))))

(. router (post "/stars/unstar"
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
                      (let [_ (<? (api/<star-page (<? app-state)
                                                  {:url (-> req .-body .-url)
                                                   :starred false
                                                   :session (int (-> req .-body .-session))}))]
                        ;; TODO: dispatch bookmark diffs to WS.
                        (. res (json (clj->js {})))))))))

(. router (get "/stars"
               (auto-caught-route-error
                 (fn [req]
                   (-> req
                       (.checkQuery "limit")
                       (.optional)
                       (.isInt))
                   )
                 (fn [req res]
                   (go-pair
                     (let [results (<? (api/<starred-pages (d/db (<? app-state)) ;; TODO -- unify on conn over db?
                                                           {:limit (int (or (-> req .-query .-limit) 100))} ;; TODO - js/Number.MAX_SAFE_INTEGER
                                                           ))]
                       (. res (json (clj->js {:results results})))))))))


(defn error-handler [err req res next]
  (doto res
    (.status 500)
    (.json (clj->js {:error err}))))

(defn not-found-handler [req res next]
  (js/console.log (.-url req))
  (doto res
    (.status 404)
    (.json (clj->js {:url (.-originalUrl req)}))))

(.use app not-found-handler)
(.use app error-handler)

;; (def -main 
;;   (fn []
;;     ;; This is the secret sauce. you want to capture a reference to 
;;     ;; the app function (don't use it directly) this allows it to be redefined on each reload
;;     ;; this allows you to change routes and have them hot loaded as you
;;     ;; code.
;;     (let [port (or (.-PORT (.-env js/process)) 3000)]
;;       (server port
;;               #(js/console.log (str "Server running at http://127.0.0.1:" port "/"))))))

(defn server [port]
  ;; This is the secret sauce. you want to capture a reference to the app function (don't use it
  ;; directly) this allows it to be redefined on each reload this allows you to change routes and
  ;; have them hot loaded as you code.
  (doto (.createServer http #(app %1 %2))
    (.listen port)))

(def -main (partial server 3000))
;; (fn []
;;   ;; This is the secret sauce. you want to capture a reference to the app function (don't use it
;;   ;; directly) this allows it to be redefined on each reload this allows you to change routes and
;;   ;; have them hot loaded as you code.
;;   (doto (.createServer http #(app %1 %2))
;;     (.listen 3000))))

(set! *main-cli-fn* -main) ;; this is required
