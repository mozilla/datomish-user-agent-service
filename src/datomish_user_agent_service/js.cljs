;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns datomish-user-agent-service.js
  (:refer-clojure :exclude [])
  (:require-macros
   [datomish.pair-chan :refer [go-pair <?]]
   [datomish.promises :refer [go-promise]])
  (:require
   [cljs.nodejs :as nodejs]
   [cljs.core.async :as a :refer [take! <! >!]]
   [cljs.reader]
   [cljs-promises.core :refer [promise]]
   [datomish.api :as d]
   [datomish.cljify :refer [cljify]]
   [datomish.promises]
   [datomish.pair-chan]
   [datomish-user-agent-service.api :as api]
   [datomish-user-agent-service.server :as server]
   ))

(defonce http (nodejs/require "http"))

(defn ^:export UserAgentService [options]
  (go-promise
    identity

    ;; TODO: use whatever validation library we use for body parameters.
    (let [options (cljify options)]
      (when-not (number? (:port options))
        (throw (js/Error. "UserAgentService requires a `port` number.")))
      (when-not (string? (:db options))
        (throw (js/Error. "UserAgentService requires a `db` string.")))
      ;; TODO: allow version to vary.
      (when-not (= "v1" (:version options))
        (throw (js/Error. "UserAgentService requires a valid `version`.")))
      (when-not (string? (:contentServiceOrigin options))
        (throw (js/Error. "UserAgentService requires a `contentServiceOrigin` string.")))

      (let [{:keys [port db version contentServiceOrigin]} options]
        (js/console.log "Opening Datomish knowledge-base at" (:db options))

        (let [c (go-pair ;; Blocked on (repeatedly!) in server/app.  This is just one way to async sequence.
                  (let [c (<? (d/<connect db)) ;; In-memory for now.
                        _ (<? (d/<transact! c api/tofino-schema))] ;; TODO: don't do try to write.
                    c))

              app
              (server/app c)
              
              server
              (doto (.createServer http #(app %1 %2))
                (.listen port))

              stop
              (fn []
                ;; TODO: close DB before exiting process.
                (go-promise
                  identity

                  (<? (d/<close c))

                  ;; A promise that returns a promise.
                  (promise (fn [resolve reject]
                             (.close server (fn [err] (if err
                                                        (reject err)                             
                                                        (do
                                                          (resolve)))))))))
              ]
          stop)))))

(defn -main [])
(set! *main-cli-fn* -main)
