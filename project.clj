(defproject datomish-user-agent-service "0.1.0-SNAPSHOT"
  :description "A Tofino User Agent Service built on top of Datomish."
  :url "https://github.com/mozilla/datomish-user-agent-service"
  :license {:name "Mozilla Public License Version 2.0"
            :url  "https://github.com/mozilla/datomish-user-agent-service/blob/master/LICENSE"}
  :dependencies [[org.clojure/clojurescript "1.9.227"]
                 [org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.2.391"]
                 [jamesmacaulay/cljs-promises "0.1.0"]
                 [datomish "0.1.0-SNAPSHOT"]]

  :min-lein-version "2.6.1"

  :plugins [[lein-figwheel "0.5.4-7"]
            [lein-cljsbuild "1.1.3" :exclusions [[org.clojure/clojure]]]]

  :source-paths ["src"]

  :clean-targets ^{:protect false} ["target"]

  :cljsbuild {:builds
              [
               {:id "dev"
                :source-paths ["src"]

                ;; the presence of a :figwheel configuration here
                ;; will cause figwheel to inject the figwheel client
                ;; into your build
                :figwheel {:on-jsload "datomish-user-agent-service.core/on-js-reload"
                           ;; :open-urls will pop open your application
                           ;; in the default browser once Figwheel has
                           ;; started and complied your application.
                           ;; Comment this out once it no longer serves you.
                           ;; :open-urls ["http://localhost:3449/index.html"]
                           }

                :compiler {:main datomish-user-agent-service.core
                           :target :nodejs
                           :asset-path "target/dev"
                           :output-to "target/dev/datomish_user_agent_service.js"
                           :output-dir "target/dev"
                           :source-map-timestamp true
                           ;; To console.log CLJS data-structures make sure you enable devtools in Chrome
                           ;; https://github.com/binaryage/cljs-devtools
                           ;; :preloads [devtools.preload]
                           }}

               {:id "test" ;; Same as "dev", but for testing.
                :source-paths ["src" "test"]

                :compiler {:main datomish-user-agent-service.test
                           :target :nodejs
                           :asset-path "target/test"
                           :output-to "target/test/datomish_user_agent_service.js"
                           :output-dir "target/test"
                           :source-map true
                           :source-map-timestamp true
                           ;; To console.log CLJS data-structures make sure you enable devtools in Chrome
                           ;; https://github.com/binaryage/cljs-devtools
                           ;; :preloads [devtools.preload]
                           }}

               ;; This next build is an compressed minified build for
               ;; production. You can build this with:
               ;; lein cljsbuild once min
               {:id "min"
                :source-paths ["src"]
                :compiler {:output-to "release-node/datomish_user_agent_service.bare.js"
                           :output-dir "release-node"
                           :main datomish-user-agent-service.core
                           :target :nodejs
                           :optimizations :advanced
                           :pretty-print false}}]}

  :figwheel {;; :http-server-root "public" ;; default and assumes "resources"
             ;; :server-port 3449 ;; default
             ;; :server-ip "127.0.0.1"

             :css-dirs ["resources/public/css"] ;; watch and update CSS

             ;; Start an nREPL server into the running figwheel process
             ;; :nrepl-port 7888

             ;; Server Ring Handler (optional)
             ;; if you want to embed a ring handler into the figwheel http-kit
             ;; server, this is for simple ring servers, if this

             ;; doesn't work for you just run your own server :) (see lein-ring)

             ;; :ring-handler hello_world.server/handler

             ;; To be able to open files in your editor from the heads up display
             ;; you will need to put a script on your path.
             ;; that script will have to take a file path and a line number
             ;; ie. in  ~/bin/myfile-opener
             ;; #! /bin/sh
             ;; emacsclient -n +$2 $1
             ;;
             ;; :open-file-command "myfile-opener"

             ;; if you are using emacsclient you can just use
             ;; :open-file-command "emacsclient"

             ;; if you want to disable the REPL
             ;; :repl false

             ;; to configure a different figwheel logfile path
             ;; :server-logfile "tmp/logs/figwheel-logfile.log"
             }


  ;; setting up nREPL for Figwheel and ClojureScript dev
  ;; Please see:
  ;; https://github.com/bhauman/lein-figwheel/wiki/Using-the-Figwheel-REPL-within-NRepl


  ;; :profiles {:dev {:dependencies [[binaryage/devtools "0.7.2"]
  ;;                                 [figwheel-sidecar "0.5.4-7"]
  ;;                                 [com.cemerick/piggieback "0.2.1"]]
  ;;                  ;; need to add dev source path here to get user.clj loaded
  ;;                  :source-paths ["src" "dev"]
  ;;                  ;; for CIDER
  ;;                  ;; :plugins [[cider/cider-nrepl "0.12.0"]]
  ;;                  :repl-options {; for nREPL dev you really need to limit output
  ;;                                 :init (set! *print-length* 50)
  ;;                                 :nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}}}

  :profiles {:dev {:dependencies [[cljsbuild "1.1.3"]
                                  [tempfile "0.2.0"]
                                  [binaryage/devtools "0.7.2"]
                                  [figwheel-sidecar "0.5.4-7"]
                                  [com.cemerick/piggieback "0.2.1"]
                                  [org.clojure/tools.nrepl "0.2.10"]
                                  [cljs-http "0.1.41"]]
                   :jvm-opts ["-Xss4m"]
                   ;; need to add dev source path here to get user.clj loaded
                   :source-paths ["src" "dev"]
                   :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}
                   :plugins      [[lein-cljsbuild "1.1.3"]
                                  [lein-doo "0.1.6"]
                                  [venantius/ultra "0.4.1"]
                                  [com.jakemccrary/lein-test-refresh "0.16.0"]]
                   }}

  :doo {:build "dev"}

  )
