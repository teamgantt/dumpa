(defproject com.teamgantt/dumpa "0.0.3"
  :description "Live replicate data from a MySQL database to your own process"
  :url "https://github.com/teamgantt/dumpa"
  :license {:name "Apache License, Version 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0"}
  :source-paths ["src"]

  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/core.async "0.6.532"]
                 [org.clojure/tools.logging "0.3.1"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [mysql/mysql-connector-java "5.1.39"]
                 [org.clojure/java.jdbc "0.6.1"]
                 [prismatic/schema "1.0.5"]
                 [com.github.shyiko/mysql-binlog-connector-java "0.4.1"]
                 [manifold "0.1.2"]]

  :global-vars {*warn-on-reflection* false}
  :min-lein-version "2.5.0"
  
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ["vcs" "tag" "v" "--no-sign"]
                  ["deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]

  :deploy-repositories [["clojars" {:sign-releases false :url "https://repo.clojars.org" :username :env/clojars_username :password :env/clojars_password}]
                        ["releases"  {:sign-releases false :url "https://repo.clojars.org" :username :env/clojars_username :password :env/clojars_password}]
                        ["snapshots" {:sign-releases false :url "https://repo.clojars.org" :username :env/clojars_username :password :env/clojars_password}]]  

  :profiles {:dev {:source-paths ["config" "dev"]
                   :dependencies [[com.stuartsierra/component "0.2.3"]
                                  [reloaded.repl "0.2.0"]
                                  [io.aviso/config "0.1.7"]
                                  [org.clojure/test.check "0.8.2"]
                                  [com.gfredericks/test.chuck "0.2.0"]]}})
