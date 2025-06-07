(defproject screpl "0.1.0"    ; also update in cli.clj and in doc files
  :description "Replication of sound changes"
  :url "https://github.com/kamwitsta/screpl"
  :license {:name "CC BY-NC-SA 4.0 or later"
            :url "https://creativecommons.org/licenses/by-nc-sa/4.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [clansi "1.0.0"]
                 [io.github.tonsky/fast-edn "1.1.2"]
                 [metosin/malli "0.17.0"]
                 [org.babashka/sci "0.9.45"]]
  :main ^:skip-aot screpl.main
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[nrepl "1.3.1"]
                                  [cider/cider-nrepl "0.55.7"]
                                  [org.clojure/tools.namespace "1.5.0"]
                                  [com.clojure-goes-fast/clj-memory-meter "0.4.0"]]
                   :jvm-opts ["-Djdk.attach.allowAttachSelf"]
                   :plugins [[lein-marginalia "0.9.2"]]}
             :uberjar {:aot :all
                       :jvm-opts ["-Dclojure.compiler.direct-linking=true"]}})

; generate documentation with Marginalia:
; lein marg -d ./doc -f 4-documentation.html -L -X src/screpl/
