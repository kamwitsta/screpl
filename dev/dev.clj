; to compile:
; lein uberjar

; to run:
; lein repl
; (in nixos first `javafx-env` (cf. configuration.nix))

; inside `lein repl`, to rerun change something in the source, save, change back, then run:
; (require '[clojure.tools.namespace.repl :refer [refresh]]) (refresh)

; inside `lein repl`, to show javafx help
; (require '[cljfx.dev :refer [help-ui]]) (help-ui)


; anything to do with the tree needs to be tested with
; clj -J-Xmx10M -M a.clj
; (memory limited to 10MB) to make sure it's properly lazy

(ns dev
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [screpl.gui :refer [start-gui]]))

; (stop-gui)
(refresh)
(start-gui)

