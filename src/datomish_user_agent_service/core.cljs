(ns ^:figwheel-always datomish-user-agent-service.core
  (:require-macros
   [datomish.pair-chan :refer [go-pair <?]]
   [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [datomish.api :as d]
            [datomish.js-sqlite] ;; Otherwise, we won't have the ISQLiteConnectionFactory defns.
            [datomish.pair-chan]
            [datomish-user-agent-service.api :as api]
            [datomish-user-agent-service.server :as server]
            [cljs.core.async :as a :refer [chan <! >!]]))

(enable-console-print!)

(defonce http (nodejs/require "http"))

;; define your app data so that it doesn't get over-written on reload
(defonce connection-pair-chan
  (go-pair
    (js/console.log "Opening Datomish knowledge-base.")
    (let [c (<? (d/<connect "")) ;; In-memory for now.
          _ (<? (d/<transact! c api/tofino-schema))]
      c)))

(defn on-js-reload []
  (js/console.log "on-js-reload"))

(def app (server/app connection-pair-chan))

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
