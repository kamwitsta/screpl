;; The entry point to SCRepl.
;; 
;; Its only purpose is to start the UI.

(ns screpl.main
  (:gen-class)
  (:require [screpl.cli :as cli]))  ; main -> cli -> core

(defn -main []
  (cli/start-repl))
