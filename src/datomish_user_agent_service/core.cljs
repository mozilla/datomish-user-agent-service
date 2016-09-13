(ns ^:figwheel-always datomish-user-agent-service.core
  (:require [cljs.nodejs :as nodejs]))

(defonce express (nodejs/require "express"))
(defonce serve-static (nodejs/require "serve-static"))
(defonce http (nodejs/require "http"))

(enable-console-print!)

;; (nodejs/enable-util-print!)
(println "Hello from the Node!")

;; define your app data so that it doesn't get over-written on reload

(defonce app-state (atom {:text "Hello world!"}))

(defn on-js-reload []
  ;; optionally touch your app-state to force rerendering depending on
  ;; your application
  ;; (swap! app-state update-in [:__figwheel_counter] inc)
  )

;; app gets redefined on reload.
(def app (express))

;; routes get redefined on each reload.
(. app (get "/hello" 
            (fn [req res] (. res (send "Hello world")))))

;; (. app (use (serve-static "resources/public" #js {:index "index.html"})))

(def -main 
  (fn []
    ;; This is the secret sauce. you want to capture a reference to 
    ;; the app function (don't use it directly) this allows it to be redefined on each reload
    ;; this allows you to change routes and have them hot loaded as you
    ;; code.
    (doto (.createServer http #(app %1 %2))
      (.listen 3000))))

(set! *main-cli-fn* -main) ;; this is required
