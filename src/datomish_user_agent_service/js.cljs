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
   [datomish-user-agent-service.server :as server]
   ))

(enable-console-print!)

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
        (println "Opening Datomish knowledge-base at" db)

        (let [connection-pair-chan
              (server/<connect db)

              app
              (server/app connection-pair-chan)

              [start stop]
              (server/createServer app {:port port})]
          [start stop])))))

(defn -main [])
(set! *main-cli-fn* -main)

(aset js/module "exports" #js {:UserAgentService UserAgentService})
