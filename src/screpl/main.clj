;; The entry point to SCRepl.
;; 
;; Its only purpose is to start the UI.

(ns screpl.main
  (:gen-class)
  (:require [screpl.gui :as gui]))

(defn -main []
  (gui/start-gui))
