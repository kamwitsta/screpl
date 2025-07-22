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

(declare load-project message print-tree project-info stop-gui)

;; Used to pass `:cancel` messages to `core` functions.
(def cancel-ch (async/chan))

;; The width of the views in [data-view](#gui/data-view).
(def column-width 200)

;; Update the progress bar after every ... steps.
(def progress-step 1000)

(def ^:dynamic *state
  "Global variable, holds the state for the GUI."
  (atom {; dialog
         :dialog nil
         ; output
         :output {:text ""
                  :tooltip "No results yet."}
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
;; - [menu](#gui/menu-view)
;; - [output](#gui/output-view)
;; - [root](#gui/root-view)

; - buttons ------------------------------------------------------------------------------------ {{{ -

;; <a id="gui/buttons-view"></a>

(defn- buttons-view
  "A bar with buttons exposing core functions."
  [_]
  {:fx/type :tool-bar
   :items [{:fx/type :button
            :text "Load project"
            :on-action (partial load-project :button)
            :tooltip {:fx/type :tooltip, :show-delay [333 :ms], :text "No project loaded."}}
           {:fx/type :separator}
           {:fx/type :button
            :disable true
            :on-action print-tree
            :text "Print tree"}]})

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
    (throw (ex-info (str "An error in column factory that shouldn't have happened. `property`=" property) {})))) 

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
                      :on-selected-item-changed {:event/type :select-source-datum}
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
                     :on-selected-item-changed {:event/type :select-target-datum}
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
       :on-close-request {:event/type :close-dialog}
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
       :on-close-request {:event/type :close-dialog}
       :showing true
       :width 200})))

; ---------------------------------------------------------------------------------------------- }}} -
; - menu --------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/menu-view"></a>

(defn- menu-view
  "A menu bar exposing core functions."
  [_]
  {:fx/type :menu-bar
   :menus [{:fx/type :menu
            :text "File"
            :items [{:fx/type :menu-item
                     :text "Load project"
                     :accelerator [:shortcut :o]
                     :on-action (partial load-project :menu)}
                    {:fx/type :separator-menu-item}
                    {:fx/type :menu-item
                     :text "Quit"
                     :on-action stop-gui}]}
           {:fx/type :menu
            :text "Tree"
            :items [{:fx/type :menu-item
                     :text "Print tree"
                     :accelerator [:shortcut :q]
                     :disable true
                     :on-action print-tree}]}]})

; ---------------------------------------------------------------------------------------------- }}} -
; - output ------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/output-view"></a>

(defn- output-view
  "A field display the results."
  [state]
  {:fx/type :web-view
   :url (str "data:text/html;charset=utf-8,"
             "<html><body style='font-family:monospace;font-size:15px' "
             "title='" (-> state :output :tooltip) "'>"
             (-> state :output :text)
             "</body></html>")})

; ---------------------------------------------------------------------------------------------- }}} -
; - root --------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/root-view"></a>

(defn- root-scene
  "The root scene."
  [state]
  {:fx/type :scene
   :root {:fx/type :v-box
          :children [(menu-view state)
                     (buttons-view state)
                     {:fx/type :split-pane
                      :divider-positions [0.5]
                      :orientation :vertical
                      :items [(output-view state)
                              (data-view state)]}]}})

(defn- root-view
  "The root stage."
  [state]
  {:fx/type fx/ext-many
   :desc (cond-> [{:fx/type :stage
                   :on-close-request {:event/type :close-window}
                   :scene (root-scene state)
                   :showing true
                   :title "SCRepl"
                   :height (* column-width 4)
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
  [source event]
  ; (let [project (core/load-project "/home/kamil/screpl/doc/sample-project.clj")]
  ; (let [project (core/load-project "/home/kamil/devel/clj/screpl/doc/sample-project.clj")]
  (case source
    :button (println (-> event .getSource .getScene .getWindow))
    :menu (println (-> event .getSource .getParentPopup .getScene)))

  (comment
  ; prepare a file chooser window
    (let [file-chooser  (FileChooser.)
          window        (-> event :fx/event .getSource .getScene .getWindow)] 
      (.addAll (.getExtensionFilters file-chooser)
               [(FileChooser$ExtensionFilter. "Clojure files (*.clj)" ["*.clj"])
                (FileChooser$ExtensionFilter. "All files (*.*)" ["*.*"])])
      ; wait for file selection
      (when-let [selected-file (.showOpenDialog file-chooser window)]
        (let [project (core/load-project (.getAbsolutePath selected-file))]
          (when (seq (:target-data project))
            ; resize the window to include target data
            (when (empty? (:target-data @*state))
              (.setHeight window (.getHeight window))   ; seems redundant but is necessary
              (.setWidth window (+ column-width (.getWidth window))))
            (swap! *state assoc :target-data (:target-data project)))
          (swap! *state assoc :source-data (:source-data project))
          (swap! *state assoc :sound-changes (map-indexed
                                               (fn [idx itm]
                                                 (hash-map :active? true
                                                           :id idx
                                                           :item itm))
                                               (:sound-changes project)))
          (swap! *state assoc-in [:buttons :load-project :tooltip :text] (project-info selected-file)))))))

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

(defn- format-tooltip
  "Format a tooltip with basic statistics of a tree."
  [tree
   counts]
  (let [linebreak "%26%2310%3B"]  ; (java.net.URLEncoder/encode "&#10;")
    (str "A tree from \"" (-> ((:tree-fn tree)) first :display) "\" through" linebreak
         "  " (:fn-count tree) " sound changes, with" linebreak
         "  " (format "%,d" (:nodes counts)) " nodes and" linebreak
         "  " (format "%,d" (:leaves counts)) " leaves.")))

(defn- print-tree
  "Wrapper around `core/grow-tree` and then `core/print-tree`."
  [_]
  (let [fns       (->> @*state :sound-changes (filter :active?) (map :item))
        val       (-> @*state :selection :source-data)
        tree      (core/grow-tree fns val)
        counter   (atom 0)
        output-ch (async/chan 12)]  ; 12 is just to make sure nothing has to wait
    ; listen for output
    (async/go-loop
      []
      (when-let [output (async/<! output-ch)]
        (case (:status output)
          :cancelled   (do
                         (swap! *state assoc-in [:output :text] str
                                "<span style='color:white;background-color:crimson;'>Cancelled.</span>")
                         (swap! *state assoc :dialog nil))
          :completed   (swap! *state assoc :dialog nil)     ; close the dialog
          :in-progress (do
                        (swap! *state update-in [:output :text] str (:output output))
                        (swap! counter inc)
                        (when (= 0 (mod @counter progress-step))
                          (swap! *state assoc-in [:dialog :message]
                                 (str "Printed " (format "%,d" @counter) " lines...")))
                        (recur))
          (throw (ex-info (str "An error in print-tree that shouldn't have happened. `output`=" output) {}))))) 
    ; run `core/print-tree`
    (async/go
      (message :progress-indet)                    ; open dialog
      (swap! *state assoc-in [:output :text] "")   ; wipe `output-view`
      (let [counts (core/print-tree tree cancel-ch output-ch)]      ; actually run
        (swap! *state assoc-in [:output :tooltip] (format-tooltip tree @counts)))   ; tooltip
      (async/close! output-ch))))                  ; clean up

; ---------------------------------------------------------------------------------------------- }}} -

; - event-handler ------------------------------------------------------------------------------ {{{ -

;; <a id="gui/event-handler"></a>

(defn- event-handler
  "Reacts to user’s actions in the GUI and dispatches work to other functions. At the same time, catches errors and redirects them to `message`."
  [event]
  (try
    (case (:event/type event)
      ; selections
      :select-source-datum
      (do
        (swap! *state assoc-in [:selection :source-data] (:fx/event event))
        (if (nil? (-> @*state :selection :source-data))
          (swap! *state assoc-in [:menu :print-tree :disable] true)
          (swap! *state assoc-in [:menu :print-tree :disable] false)))
      :select-target-datum
      (swap! *state assoc-in [:selection :target-data] (:fx/event event))
      ; other
      :cancel-operation (async/>!! cancel-ch :cancel)
      :close-dialog (swap! *state assoc :dialog nil)
      :close-window (stop-gui))
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
  (fx/mount-renderer *state renderer))

; ---------------------------------------------------------------------------------------------- }}} -
; - stop-gui ----------------------------------------------------------------------------------- {{{ -

;; <a id="gui/stop-gui"></a>

(defn ^:export stop-gui
  "Stop the GUI."
  []
  (async/close! cancel-ch)
  (fx/unmount-renderer *state renderer))
  ; (System/exit 0))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
