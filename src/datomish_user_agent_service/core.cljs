(ns ^:figwheel-always datomish-user-agent-service.core
  (:require-macros
   [datomish.pair-chan :refer [go-pair <?]]
   [cljs.core.async.macros :refer [go]])
  (:require [cljs.nodejs :as nodejs]
            [datomish.api :as d]
            [datomish-user-agent-service.api :as api]
            [datomish-user-agent-service.server :as server]
            [cljs.core.async :as a :refer [chan <! >!]]))

(enable-console-print!)

(defn on-js-reload []
  (js/console.log "on-js-reload"))

(defonce http (nodejs/require "http"))

;; Define your app data so that it doesn't get over-written on reload.
;; This is tricky, since we can't use a top-level go-pair (see
;; http://dev.clojure.org/jira/browse/ASYNC-110).  Instead we fill a
;; concrete promise channel during `-main`, and the server reads the
;; (unique, constant) value as often as it needs it.
(defonce connection-pair-chan
  (a/promise-chan))

(def app (server/app connection-pair-chan))

(defn server [port]
  ;; This is the secret sauce. you want to capture a reference to the app function (don't use it
  ;; directly) this allows it to be redefined on each reload this allows you to change routes and
  ;; have them hot loaded as you code.
  (doto (.createServer http #(app %1 %2))
    (.listen port)))

(defn -main []
  (a/take!
    (go-pair
      (js/console.log "Opening Datomish knowledge-base.")
      (let [c (<? (d/<connect "")) ;; In-memory for now.
            _ (<? (d/<transact! c api/tofino-schema))]
        c))
    (partial a/offer! connection-pair-chan))
  (server 3000))

(set! *main-cli-fn* -main) ;; this is required
