;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

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

;; Define your app data so that it doesn't get over-written on reload.
;; This is tricky, since we can't use a top-level go-pair (see
;; http://dev.clojure.org/jira/browse/ASYNC-110).  Instead we fill a
;; concrete promise channel during `-main`, and the server reads the
;; (unique, constant) value as often as it needs it.
(defonce connection-pair-chan
  (a/promise-chan))

(defn on-js-reload []
  (js/console.log "on-js-reload"))

(def app (server/app connection-pair-chan))

;; We want to refer to app by name; partial captures the value at
;; definition time.
(defn- handle [& rest]
  (apply app rest))

(defn -main []
  (.on js/process "uncaughtException" (fn [err]
                                        (println (aget err "stack"))
                                        (js/process.exit 101)))

  (.on js/process "unhandledRejection" (fn [reason p]
                                         (println (aget reason "stack"))
                                         (js/process.exit 102)))

  (a/take!
    (server/<connect "") ;; In-memory for now.
    (partial a/offer! connection-pair-chan))

  (let [[start stop] (server/createServer handle {:port 9090})]
    (start)))

(set! *main-cli-fn* -main) ;; this is required
