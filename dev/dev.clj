; anything to do with the tree needs to be tested with
; clj -J-Xmx10M -M a.clj
; (memory limited to 10MB) to make sure it is properly lazy

(ns dev
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            ; [screpl.a :refer [start-gui stop-gui]]))
            [screpl.gui :refer [start-gui stop-gui]]))

(stop-gui)
(refresh)
(start-gui)
(gui/load-project nil)

(comment
  run this inside lein repl if it says it can't find stop-gui
  (require '[clojure.tools.namespace.repl :refer [refresh]]) (refresh))
; {:event/type :load-project, :fx/event #object[javafx.event.ActionEvent 0x1a2b30fd javafx.event.ActionEvent[source=Button@5e74a3ca[styleClass=button]'Load project']]}
