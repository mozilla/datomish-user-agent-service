;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.

(ns datomish-user-agent-service.api
  #?(:cljs
     (:require-macros
      [datomish.pair-chan :refer [go-pair <?]]
      ;; [datomish.node-tempfile-macros :refer [with-tempfile]]
      [cljs.core.async.macros :as a :refer [go]]))
  (:require
   [datomish.api :as d]
   [datomish.transact] ;; Otherwise, no IConnection.close.
   [datomish.util :as util]
   #?@(:clj [
             ;; [datomish.jdbc-sqlite]
             [datomish.pair-chan :refer [go-pair <?]]
             ;; [tempfile.core :refer [tempfile with-tempfile]]
             ;; [datomish.test-macros :refer [deftest-async deftest-db]]
             ;; [clojure.test :as t :refer [is are deftest testing]]
             [clojure.core.async :refer [go <! >!]]])
   #?@(:cljs [
              ;; [datomish.js-sqlite]
              [datomish.pair-chan]
              ;; [datomish.test-macros :refer-macros [deftest-async deftest-db]]
              ;; [datomish.node-tempfile :refer [tempfile]]
              ;; [cljs.test :as t :refer-macros [is are deftest testing async]]
              [cljs.core.async :as a :refer [<! >!]]]))
  #?(:clj
     (:import [clojure.lang ExceptionInfo]))
  ;; #?(:clj
  ;;    (:import [datascript.db DB]))
  )

#?(:cljs
   (def Throwable js/Error))

(def page-schema
  [{:db/id (d/id-literal :db.part/user)
    :db/ident              :page/url
    :db/valueType          :db.type/string          ; Because not all URLs are java.net.URIs. For JS we may want to use /uri.
    :db/fulltext           true
    :db/cardinality        :db.cardinality/one
    :db/unique             :db.unique/identity
    :db/doc                "A page's URL."
    :db.install/_attribute :db.part/db}
   {:db/id (d/id-literal :db.part/user)
    :db/ident              :page/title
    :db/valueType          :db.type/string
    :db/fulltext           true
    :db/cardinality        :db.cardinality/one      ; We supersede as we see new titles.
    :db/doc                "A page's title."
    :db.install/_attribute :db.part/db}
   {:db/id (d/id-literal :db.part/user)
    :db/ident              :page/starred
    :db/valueType          :db.type/boolean
    :db/cardinality        :db.cardinality/one
    :db/doc                "Whether the page is starred."
    :db.install/_attribute :db.part/db}
   {:db/id (d/id-literal :db.part/user)
    :db/ident              :page/visit
    :db/valueType          :db.type/ref
    :db/unique             :db.unique/value
    :db/cardinality        :db.cardinality/many
    :db/doc                "A visit to the page."
    :db.install/_attribute :db.part/db}])

(def visit-schema
  [{:db/id (d/id-literal :db.part/user)
    :db/ident              :visit/visitAt
    :db/valueType          :db.type/instant
    :db/cardinality        :db.cardinality/one
    :db/doc                "The instant of the visit."
    :db.install/_attribute :db.part/db}])

(def session-schema
  [{:db/id (d/id-literal :db.part/user)
    :db/ident              :session/startedFromAncestor
    :db/valueType          :db.type/ref     ; To a session.
    :db/cardinality        :db.cardinality/one
    :db/doc                "The ancestor of a session."
    :db.install/_attribute :db.part/db}
   {:db/id (d/id-literal :db.part/user)
    :db/ident              :session/startedInScope
    :db/valueType          :db.type/string
    :db/cardinality        :db.cardinality/one
    :db/doc                "The parent scope of a session."
    :db.install/_attribute :db.part/db}
   {:db/id (d/id-literal :db.part/user)
    :db/ident              :session/startReason
    :db/valueType          :db.type/string    ; TODO: enum?
    :db/cardinality        :db.cardinality/many
    :db/doc                "The start reasons of a session."
    :db.install/_attribute :db.part/db}
   {:db/id (d/id-literal :db.part/user)
    :db/ident              :session/endReason
    :db/valueType          :db.type/string    ; TODO: enum?
    :db/cardinality        :db.cardinality/many
    :db/doc                "The end reasons of a session."
    :db.install/_attribute :db.part/db}
   {:db/id (d/id-literal :db.part/user)
    :db/ident              :event/session
    :db/valueType          :db.type/ref      ; To a session.
    :db/cardinality        :db.cardinality/one
    :db/doc                "The session in which a tx took place."
    :db.install/_attribute :db.part/db}])

(def save-schema
  [{:db/id (d/id-literal :db.part/user)
    :db.install/_attribute :db.part/db
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/ref
    :db/ident :save/page}
   {:db/id (d/id-literal :db.part/user)
    :db.install/_attribute :db.part/db
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/instant
    :db/ident :save/savedAt}
   {:db/id (d/id-literal :db.part/user)
    :db.install/_attribute :db.part/db
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string
    :db/fulltext true
    :db/ident :save/title}
   {:db/id (d/id-literal :db.part/user)
    :db.install/_attribute :db.part/db
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string
    :db/fulltext true
    :db/ident :save/excerpt}
   {:db/id (d/id-literal :db.part/user)
    :db.install/_attribute :db.part/db
    :db/cardinality :db.cardinality/one
    :db/valueType :db.type/string
    :db/fulltext true
    :db/ident :save/content}])

(def tofino-schema (concat page-schema visit-schema session-schema save-schema))

(defn instant [x]
  #?(:cljs x)
  #?(:clj (.getTime x)))

(defn now []
  #?(:cljs (js/Date.))
  #?(:clj (java.util.Date.)))

;; Returns the session ID.
(defn <start-session [conn {:keys [ancestor scope reason]
                            :or {reason "none"}}]
  (let [id (d/id-literal :db.part/user -1)
        base {:db/id                  id
              :session/startedInScope (str scope)
              :session/startReason    reason}
        datoms
        (if ancestor
          [(assoc base :session/startedFromAncestor ancestor)
           {:db/id         :db/tx
            :event/session ancestor}]
          [base])]

    (go-pair
      (->
        (<? (d/<transact! conn datoms))
        :tempids
        (get id)))))

(defn <end-session [conn {:keys [session reason]
                          :or   {reason "none"}}]
  (d/<transact!
    conn
    [{:db/id         :db/tx
      :event/session session}                              ; So meta!
     {:db/id             session
      :session/endReason reason}]))

(defn <active-sessions [db]
  (d/<q
    db
    '[:find ?id ?reason ?ts :in $
      :where
      [?id :session/startReason ?reason ?tx]
      [?tx :db/txInstant ?ts]
      (not-join [?id]
                [?id :session/endReason _])]))

(defn <ended-sessions [db]
  (d/<q
    db
    '[:find ?id ?endReason ?ts :in $
      :where
      [?id :session/endReason ?endReason ?tx]
      [?tx :db/txInstant ?ts]]))

(defn <star-page [conn {:keys [url uri title session starred]
                        :or {starred true}}]
  (let [page (d/id-literal :db.part/user -1)]
    (d/<transact!
      conn
      [{:db/id        :db/tx
        :event/session session}
       (merge
         (when title
           {:page/title title})
         {:db/id        page
          :page/url     (or uri url)
          :page/starred starred})])))

;; TODO: limit.
(defn <starred-pages [db {:keys [limit since]
                          :or {limit 10}}]
  (let [where
        (if since
          '[[?page :page/starred true ?tx]
            [?tx :db/txInstant ?starredOn]
            [(> ?starredOn ?since)]
            [?page :page/url ?url]
            [(get-else $ ?page :page/title "") ?title]]

          '[[?page :page/starred true ?tx]
            [?tx :db/txInstant ?starredOn]
            [?page :page/url ?url]
            [(get-else $ ?page :page/title "") ?title]])]

    (go-pair
      (let [rows (<? (d/<q
                       db
                       {:find '[?url ?title ?starredOn]
                        :in (if since '[$ ?since] '[$])
                        :where where}
                       {:limit limit
                        :order-by [[:starredOn :desc]]
                        :inputs {:since since}}))]
        (map (fn [[url title starredOn]]
               {:url url :title title :starredOn starredOn})
             rows)))))

(defn <save-page [conn {:keys [url uri title session excerpt content]}]
  (let [save (d/id-literal :db.part/user -1)
        page (d/id-literal :db.part/user -2)]
    (d/<transact!
      conn
      [{:db/id        :db/tx
        :event/session session}
       {:db/id page
        :page/url (or uri url)}
       (merge
         {:db/id        save
          :save/savedAt (now)
          :save/page page}
         (when title
           {:save/title title})
         (when excerpt
           {:save/excerpt excerpt})
         (when content
           {:save/content content}))])))

;; TODO: use since.
;; TODO: return lastVisited, sort by lastVisited.
;; TODO: support snippet extraction.
(defn <saved-pages [db {:keys [limit since]
                        :or {:limit 10}}]
  (go-pair
    (->>
      (<?
        (d/<q
          db
          '[:find ?page ?url ?title ?excerpt
            :in $
            :where
            [?save :save/page ?page]
            [?save :save/savedAt ?instant]
            [?page :page/url ?url]
            [(get-else $ ?save :save/title "") ?title]
            [(get-else $ ?save :save/excerpt "") ?excerpt]]
          {:limit limit
           :order-by [[:instant :desc]]}))
      (map (fn [[page url title excerpt]]
             {:url url :title title :excerpt excerpt :snippet "" :lastVisited nil})))))

(defn <pages-matching-string [db string {:keys [limit since]
                                         :or {:limit 10}}]
  (let [string (str "*" string "*") ;; Wildcard match.  TODO: escape string properly.

        ;; TODO: extract matching snippet.
        ;; TODO: return lastVisited, order by lastVisited.
        ;; TODO: use since, if present.
        query
        '[:find
          ?url
          ?title ; There's no more than one title per URL, so we don't need to worry about duplicate URLs.
          ?excerpt
          :in $ ?str
          :where
          (or-join [?page ?title ?excerpt]
            (and
              [(fulltext $ #{:page/url :page/title} ?str) [[?page]]]
              [(get-else $ ?page :page/title "") ?title]
              [(ground "") ?excerpt])
            (and
              [(fulltext $ #{:save/title :save/excerpt :save/content} ?str) [[?save]]]
              [?save :save/page ?page]
              [(get-else $ ?save :save/title "") ?title]
              [(get-else $ ?save :save/excerpt "") ?excerpt]))
          [?page :page/url ?url]]]

    (go-pair
      (set
        (map
          (fn [[url title excerpt]]
            {:url url
             :title title
             :excerpt excerpt
             :snippet ""
             :lastVisited nil})
          (<? (d/<q db query
                    {:inputs {:str string}
                     :limit limit})))))))

;; TODO: return ID?
(defn <add-visit [conn {:keys [url uri title session]}]
  (let [visit (d/id-literal :db.part/user -1)
        page (d/id-literal :db.part/user -2)]
    (d/<transact!
      conn
      [{:db/id        :db/tx
        :event/session session}
       {:db/id        visit
        :visit/visitAt (now)}
       (merge
         (when title
           {:page/title title})
         {:db/id        page
          :page/url     (or uri url)
          :page/visit visit})])))

(defn- third [x]
  (nth x 2))

(defn <visited [db
                {:keys [limit since]
                 :or {limit 10}}]
  (let [where
        (if since
          '[[?visit :visit/visitAt ?time]
            [(> ?time ?since)]
            [?page :page/visit ?visit]
            [?page :page/url ?url]
            [(get-else $ ?page :page/title "") ?title]]

          '[[?page :page/visit ?visit]
            [?visit :visit/visitAt ?time]
            [?page :page/url ?url]
            [(get-else $ ?page :page/title "") ?title]])]

    (go-pair
      (let [rows (<? (d/<q
                       db
                       {:find '[?url ?title (max ?time)]
                        :in (if since '[$ ?since] '[$])
                        :where where}
                       {:limit limit
                        :order-by [[:_max_time :desc]]
                        :inputs {:since since}}))]
        (map (fn [[url title lastVisited]]
               {:url url :title title :lastVisited lastVisited :snippet ""})
             rows)))))

(defn <find-title [db url]
  ;; Until we support [:find ?title . :in…] we crunch this by hand.
  (d/<q db
        '[:find ?title . :in $ ?url
          :where
          [?page :page/url ?url]
          [(get-else $ ?page :page/title "") ?title]]
        {:inputs {:url url}}))
