;; A JavaFX user interface.

;; 1. [Global variables](#gui/globals)
;; 2. [Parts of the interface](#gui/views)
;; 3. [Functionality](#gui/handlers)
;; 4. [Other](#gui/other)

(ns screpl.gui
  (:require [cljfx.api :as fx]
            [clojure.core.async :as async]
            [clojure.string :as string]
            [screpl.core :as core])
  (:import [javafx.stage FileChooser FileChooser$ExtensionFilter]))


; = globals ==================================================================================== {{{ =

;; <a id="gui/globals"></a>
;; ## Global variables

(declare message project-info stop-gui)

;; The width of the views in [data-view](#gui/data-view).
(def column-width 200)

;; Global channel to send "cancel" messages to `core`.
(def cancel-ch (async/chan))

(def ^:dynamic *state
  "Global variable, holds the state for the GUI."
  (atom {;dialog
         :dialog nil
         ;menu
         :menu {:load-project {:fx/type :button
                               :on-action {:event/type :load-project}
                               :text "Load project"
                               :tooltip {:fx/type :tooltip, :show-delay [333 :ms], :text "No project loaded."}}
                :separator    {:fx/type :separator}
                :print-tree   {:fx/type :button
                               :disable true
                               :on-action {:event/type :print-tree}
                               :text "Print tree"}}
         ; output
         :output ""
         ; project
         :sound-changes []
         :source-data []
         ; selections (handled by [event-handler](#gui/event-handler)
         :selection {:source-data nil
                     :target-data nil}}))

; ============================================================================================== }}} =
; = views ====================================================================================== {{{ =

;; <a id="gui/views"></a>
;; ## Parts of the interface

;; Functions that describe the look and behaviour of the GUI.

;; - [buttons](#gui/buttons-view)
;; - [data](#gui/data-view)
;; - [dialog](#gui/dialog-view)
;; - [output](#gui/output-view)
;; - [root](#gui/root-view)

; - buttons ------------------------------------------------------------------------------------ {{{ -

;; <a id="gui/buttons-view"></a>

(defn- buttons-view
  "A bar with buttons exposing core functions."
  [state]
  {:fx/type :tool-bar
   :items (-> state :menu vals)})

; ---------------------------------------------------------------------------------------------- }}} -
; - data --------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/data-view"></a>

; - column-maker - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - {{{ -

(defn- column-maker
  "Makes a single column for tables displaying source and target data."
  [property]    ; the keyword in source- and target-data
  (case property
    :display {:fx/type :table-column
              :cell-value-factory property
              :pref-width (* column-width 3/4)
              :text "Display"} 
    :id      {:fx/type :table-column
              :cell-value-factory property
              :pref-width (* column-width 1/4)
              :text "ID"}
    (throw (ex-info "An error in column factory that shouldn't have happened." {})))) 

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -
; - data-tooltip - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - {{{ -

(defn- data-tooltip
  "Makes a tooltip for source and target datum's."
  [item]
  {:tooltip {:fx/type :tooltip
             :show-delay [333 :ms]
             :text (->> item
                        (map (fn [[k v]] (str (name k) ": " (pr-str v))))
                        (string/join "\n"))}})

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -
; - item-view - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -{{{ -

(defn- swap-elements
  "Swaps two elements inside a collection. Also works on lazy sequences because `coll` is converted to a vector."
  [coll i j]
  (let [v (vec coll)]
    (assoc v
           i (nth v j)
           j (nth v i))))

(defn- item-view
  "Makes a view for a single sound change."
  [index
   item]
  {:fx/type :h-box
    :padding 3
    :spacing 9
    :children [{:fx/type :h-box
                :alignment :center-right
                :spacing 1
                :children [{:fx/type :button
                            :on-action (fn [_]
                                         (swap! *state update :sound-changes
                                                #(swap-elements % (dec index) index)))
                            :font 8
                            :max-height 18
                            :max-width 18
                            :min-height 18
                            :min-width 18
                            :text "▲"
                            :visible (> index 0)}
                           {:fx/type :button
                            :on-action (fn [_]
                                         (swap! *state update :sound-changes
                                                #(swap-elements % index (inc index))))
                            :font 8
                            :max-height 18
                            :max-width 18
                            :min-height 18
                            :min-width 18
                            :text "▼"
                            :visible (< index (dec (count (:sound-changes @*state))))}]}
               {:fx/type :check-box
                :on-action (fn [_]
                             (swap! *state update :sound-changes
                                    (partial map (fn [i]
                                                   (cond-> i
                                                     (= (:id i) (:id item))
                                                     (update :active? not))))))
                :selected (:active? item)}
               {:fx/type :label
                :disable (-> item :active? not)
                :text (-> item :item meta :name str)
                :tooltip {:fx/type :tooltip
                          :show-delay [333 :ms]
                          :text (-> item :item meta :doc)}}]})

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -

; - data-view - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -{{{ -

(defn- data-view
  "Tables displaying sound changes and data."
  [state]
  (let [has-target-data? (seq (:target-data state))]
    {:fx/type :split-pane
     :divider-positions (if has-target-data? [0.333 0.666] [0.5])
     :items (cond-> [; sound changes
                     {:fx/type :scroll-pane
                      :content {:fx/type :v-box
                                :children (map-indexed item-view (:sound-changes state))}
                      :pref-width column-width}
                     ; source-data
                     {:fx/type :table-view
                      :column-resize-policy :constrained  ; don't show an extra column
                      :columns (if has-target-data?
                                 [(column-maker :id)
                                  (column-maker :display)]
                                 [(column-maker :display)])
                      :items (:source-data state)
                      :on-selected-item-changed {:event/type ::select-source-datum}
                      :pref-width column-width
                      :row-factory {:fx/cell-type :table-row
                                    :describe data-tooltip}
                      :selection-mode :single}]
              ; target data
              has-target-data?
              (conj {:fx/type :table-view
                     :column-resize-policy :constrained  ; don't show an extra column
                     :columns [(column-maker :id)
                               (column-maker :display)]
                     :items (:target-data state)
                     :on-selected-item-changed {:event/type ::select-target-datum}
                     :pref-width column-width
                     :row-factory {:fx/cell-type :table-row
                                   :describe data-tooltip}
                     :selection-mode :single}))}))

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -

; ---------------------------------------------------------------------------------------------- }}} -
; - dialog ------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/dialog-view"></a>

(defn- dialog-view
  "A dialog to display errors etc."
  [state]
  (when-let [dialog (:dialog state)]
    (case (:type dialog)
      :error
      {:fx/type :alert
       :alert-type :error
       :button-types [:ok]
       :content-text (:content-text dialog)
       :header-text ""
       :on-close-request {:event/type :dialog-close}
       :showing true}

      :progress-indet
      {:fx/type :stage
       :scene {:fx/type :scene
               :root {:fx/type :v-box
                      :alignment :center
                      :children [{:fx/type :label
                                  :text (:message dialog)}
                                 {:fx/type :progress-bar}
                                 {:fx/type :button
                                  :on-action {:event/type :cancel-operation}
                                  :text "Cancel"}]}}
       :on-close-request {:event/type :dialog-close}
       :showing true
       :width 200})))

; ---------------------------------------------------------------------------------------------- }}} -
; - output ------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/output-view"></a>

(defn- output-view
  "Tables displaying sound changes and data."
  [state]
  {:fx/type :text-area
   :editable false
   :style {:-fx-font-family "monospace"}
   :text (:output state)})

; ---------------------------------------------------------------------------------------------- }}} -
; - root --------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/root-view"></a>

(defn- root-scene
  "The root scene."
  [state]
  {:fx/type :scene
   :root {:fx/type :split-pane
          :divider-positions [0.1 0.5]
          :orientation :vertical
          :items [(buttons-view state)
                  (output-view state)
                  (data-view state)]}})

(defn- root-view
  "The root stage."
  [state]
  {:fx/type fx/ext-many
   :desc (cond-> [{:fx/type :stage
                   :on-close-request {:event/type :window-close}
                   :scene (root-scene state)
                   :showing true
                   :title "SCRepl"
                   :width (* column-width 2)}]
           (:dialog state) (conj (dialog-view state)))})

; ---------------------------------------------------------------------------------------------- }}} -
   
; ============================================================================================== }}} =
; = handlers =================================================================================== {{{ =

;; <a id="gui/handlers"></a>
;; ## Functionality

;; Wrappers provide a link between [screpl.core](#screpl.core) functions and the GUI. The [event handler](#gui/event-handler) reacts to user’s actions in the GUI and dispatches work to the appropriate wrappers, while simultaneously catching errors from [screpl.core](#screpl.core), allowing it to focus on the happy path.

;; - [load-project](#gui/load-project)
;; - [project-info](#gui/project-info)
;; - [print-tree](#gui/print-tree)
;; - [event-handler(#gui/event-handler)

; - load-project ------------------------------------------------------------------------------- {{{ -

;; <a id="gui/load-project"></a>

(defn- load-project
  "Wrapper around `core/load-project`."
  [event]
  ; prepare a file chooser window
  ; (let [file-chooser  (FileChooser.)
        ; window        (-> event :fx/event .getSource .getScene .getWindow)] 
    ; (.addAll (.getExtensionFilters file-chooser)
             ; [(FileChooser$ExtensionFilter. "Clojure files (*.clj)" ["*.clj"])
              ; (FileChooser$ExtensionFilter. "All files (*.*)" ["*.*"])])
    ; wait for file selection
    ; (when-let [selected-file (.showOpenDialog file-chooser window)]
      ; (let [project (core/load-project (.getAbsolutePath selected-file))]
  (let [project (core/load-project "/home/kamil/screpl/doc/sample-project.clj")]
    (when (seq (:target-data project))
      ; resize the window to include target data
      (when (empty? (:target-data @*state))
       (let [stage (-> event :fx/event .getSource .getScene .getWindow)]
          (.setHeight stage (.getHeight stage))   ; seems redundant but is necessary
          (.setWidth stage (+ column-width (.getWidth stage)))))
      (swap! *state assoc :target-data (:target-data project)))
    (swap! *state assoc :source-data (:source-data project))
    (swap! *state assoc :sound-changes (map-indexed
                                         (fn [idx itm]
                                           (hash-map :active? true
                                                     :id idx
                                                     :item itm))
                                         (:sound-changes project)))))
    ; (swap! *state assoc-in [:buttons :load-project :tooltip :text] (project-info selected-file))))

; ---------------------------------------------------------------------------------------------- }}} -
; - project-info ------------------------------------------------------------------------------- {{{ -

;; <a id="gui/project-info"></a>

(defn- project-info
  "Displays basic information about the currently loaded project."
  [filename]
  (let [scs   (count (:sound-changes @*state))
        src   (count (:source-data @*state))
        trg   (count (:target-data @*state))]
    (str "Project " filename ".\n"
         "Contains:\n"
         "  " scs " sound change functions,\n"
         "  " src " source data items,\n"
         "  " (if (= 0 trg)
                "no target data."
                (str trg " target data items.")))))

; ---------------------------------------------------------------------------------------------- }}} -
; - print-tree --------------------------------------------------------------------------------- {{{ -

;; <a id="gui/project-info"></a>

(defn- print-tree
  "Wrapper around `core/grow-tree` and then `core/print-tree`."
  [_]
  (let [fns         (->> @*state :sound-changes (filter :active?) (map :item))
        val         (-> @*state :selection :source-data)
        tree        (core/grow-tree fns val)
        output-ch   (async/chan 12)  ; 12 just so the gui doesn't make `core` wait
        progress-ch (async/chan 12)]
    ; prepare
    (message :progress-indet)
    (swap! *state assoc :output "")
    ; listen for output
    (async/go-loop
      []
      (when-let [output (async/<! output-ch)]
        (swap! *state update :output str output)
        (recur)))
    ; listen for progress
    (async/go-loop
      []
      (when-let [progress (async/<! progress-ch)]
        (swap! *state assoc-in [:dialog :message] (str "Printed " progress " lines..."))
        (recur)))
    ; run `core/print-tree`
    (async/go
      (core/print-tree tree cancel-ch output-ch progress-ch)
      (async/close! output-ch)
      (async/close! progress-ch)
      (swap! *state assoc :dialog nil))))

; ---------------------------------------------------------------------------------------------- }}} -

; - event-handler ------------------------------------------------------------------------------ {{{ -

;; <a id="gui/event-handler"></a>

(defn- event-handler
  "Reacts to user’s actions in the GUI and dispatches work to other functions. At the same time, catches errors and redirects them to `message`."
  [event]
  (try
    (case (:event/type event)

      ; wrappers
      :load-project (load-project event)
      :print-tree (print-tree event)

      ; selections
      ::select-source-datum
      (do
        (swap! *state assoc-in [:selection :source-data] (:fx/event event))
        (if (nil? (-> @*state :selection :source-data))
          (swap! *state assoc-in [:menu :print-tree :disable] true)
          (swap! *state assoc-in [:menu :print-tree :disable] false)))
      ::select-target-datum
      (swap! *state assoc-in [:selection :target-data] (:fx/event event))

      ; other
      :cancel-operation (async/>!! cancel-ch :cancel)
      :dialog-close (swap! *state assoc :dialog nil)
      :window-close (stop-gui))
    (catch Exception e (message :error e))))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = other ====================================================================================== {{{ =

;; <a id="gui/other"></a>
;; ## Other

;; The more technical parts: managing the GUI, and displaying dialogs.

;; - [message](#gui/message)
;; - [renderer](#gui/renderer)
;; - [start-gui](#gui/start-gui)
;; - [stop-gui](#gui/stop-gui)

; - message ------------------------------------------------------------------------------------ {{{ -

;; <a id="gui/message"></a>

(defn- message
  "Displays a dialog with a message."
  [type          ; :error, :progress-indet
   & messages]   ; an Exception when :error, string(s) or number(s) otherwise
  (case type
    :error          (let [err      (-> messages first Throwable->map)
                          data     (:data err)
                          location (cond-> []
                                     (:filename data) (conj (str " in " (:filename data)))
                                     (:index data)    (conj (str " in item " (:index data)))
                                     (:display data)  (conj (str " (" (:display data) ")"))
                                     (:field data)    (conj (str " in " (:field data))))]
                      (swap! *state assoc :dialog
                             {:type :error
                              :content-text (str "Error" (apply str location) ":\n" (:cause err))}))
    :progress-indet (swap! *state assoc :dialog
                           {:type :progress-indet
                            :message "Processing..."})))

; ---------------------------------------------------------------------------------------------- }}} -

; - renderer ----------------------------------------------------------------------------------- {{{ -

;; <a id="gui/renderer"></a>

;; A layer of abstraction that takes care of the changing state. See https://github.com/cljfx/cljfx?tab=readme-ov-file#renderer 
(def renderer
  (fx/create-renderer
    :middleware (fx/wrap-map-desc root-view)
    ; improved errors; see https://github.com/cljfx/dev
    :error-handler (bound-fn [^Throwable ex] (.printStackTrace ^Throwable ex *err*))
    :opts {:fx.opt/map-event-handler event-handler 
           ; improved errors; see https://github.com/cljfx/dev
           :fx.opt/type->lifecycle @(requiring-resolve 'cljfx.dev/type->lifecycle)}))

; ---------------------------------------------------------------------------------------------- }}} -
; - start-gui ---------------------------------------------------------------------------------- {{{ -

;; <a id="gui/start-gui"></a>

(defn ^:export start-gui
  "Start the GUI"
  []
  ; (javafx.application.Platform/setImplicitExit true)
  ; (fx/on-fx-thread
  (fx/mount-renderer *state renderer))

; ---------------------------------------------------------------------------------------------- }}} -
; - stop-gui ----------------------------------------------------------------------------------- {{{ -

;; <a id="gui/stop-gui"></a>

(defn ^:export stop-gui
  "Stop the GUI."
  []
  ; (fx/on-fx-thread
  (fx/unmount-renderer *state renderer))
  ; (System/exit 0))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
