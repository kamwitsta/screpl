;; The entry point to SCRepl.
;; 
;; Its only purpose is to start the UI.

(ns screpl.main
  (:gen-class)
  (:require [screpl.cli :as cli]    ; main -> cli -> core
            [screpl.gui :as gui]))

; (defn -main [& opts]
  ; (case (first opts)
    ; "cli"    (cli/start-repl)
    ; "--help" (println "Usage: screpl [OPTION]\n\n  cli     start the command-line interface\n  --help display this message\n\nIf no option is given, the graphical interface will be started.")
    ; (gui/start-gui)))
