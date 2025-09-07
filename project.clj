(defproject screpl "0.4.1"
  :description "Replication of sound changes"
  :url "https://github.com/kamwitsta/screpl"
  :license {:name "CC BY-NC-SA 4.0 or later"
            :url "https://creativecommons.org/licenses/by-nc-sa/4.0/"}
  :dependencies [[org.clojure/clojure "1.12.0"]
                 ; data upload
                 [io.github.camsaul/toucan2 "1.0.565"] 
                 [io.github.tonsky/fast-edn "1.1.2"]
                 [metosin/malli "0.17.0"]
                 [org.babashka/sci "0.9.45"]
                 [org.mariadb.jdbc/mariadb-java-client "3.5.3"]
                 ; gui
                 [cljfx "1.9.5"]
                 [com.github.mifmif/generex "1.0.2"]
                 [io.github.cljfx/dev "1.0.39"]
                 [org.clojure/core.async "1.8.741"]
                 [org.openjfx/javafx-base "17.0.2" :classifier "win"]
                 [org.openjfx/javafx-controls "17.0.2" :classifier "win"]
                 [org.openjfx/javafx-fxml "17.0.2" :classifier "win"]
                 [org.openjfx/javafx-graphics "17.0.2" :classifier "win"]
                 [org.openjfx/javafx-web "17.0.2" :classifier "win"]]
  :main ^:skip-aot screpl.main
  :target-path "target/%s"
  :profiles {:dev {:dependencies [[nrepl "1.3.1"]
                                  [cider/cider-nrepl "0.55.7"]
                                  [org.clojure/tools.namespace "1.5.0"]
                                  [com.clojure-goes-fast/clj-memory-meter "0.4.0"]]
                   :jvm-opts ["-Ddev.mode=true"
                              "-Djdk.attach.allowAttachSelf"
                              "-Djava.awt.headless=false"]
                   :plugins  [[lein-marginalia "0.9.2"]]}
             :uberjar {:aot :all
                       :jvm-opts ["-Ddev.mode=false"
                                  "-Dclojure.compiler.direct-linking=true"]
                       :injections [(javafx.application.Platform/exit)]
                       :uberjar-name "screpl.jar"}})

; generate documentation with Marginalia:
; lein marg -d ./doc -f 3-documentation.html -L -X src/screpl/
