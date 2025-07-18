; anything to do with the tree needs to be tested with
; clj -J-Xmx10M -M a.clj
; (memory limited to 10MB) to make sure it is properly lazy

(ns dev
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [screpl.gui :refer [start-gui]]))

; (stop-gui)
(refresh)
(start-gui)

; run this inside lein repl if it says it can't find stop-gui
; also works: changing something in a source file, saving, changing back, saving
; (require '[clojure.tools.namespace.repl :refer [refresh]]) (refresh)
; (require '[cljfx.dev :refer [help-ui]]) (help-ui)
