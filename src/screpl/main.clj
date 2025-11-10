(ns screpl.main
  (:gen-class)
  (:require [screpl.gui :as gui]))

(defn -main []
  (gui/start-gui))
