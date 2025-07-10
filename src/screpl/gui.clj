;; A JavaFX user interface.

;; 1. [Global variables](#gui/globals)
;; 2. [Parts of the interface](#gui/views)
;; 3. [Functionality](#gui/handlers)
;; 4. [Other](#gui/other)

(ns screpl.gui
  (:require [cljfx.api :as fx]
            [clojure.string :as string]
            [sci.core :as sci]
            [screpl.core :as core])
  (:import [javafx.scene.control Dialog DialogEvent]
           [javafx.stage FileChooser FileChooser$ExtensionFilter]))


; = globals ==================================================================================== {{{ =

;; <a id="gui/globals"></a>
;; ## Global variables

(declare message stop-gui)

(def ^:dynamic *sci-ctx*
 "Global variable, holds the context for SCI."
 (sci/init {:namespaces {}}))

(def ^:dynamic *state
  "Global variable, holds the state for the GUI."
  (atom {:buttons [{:fx/type :button
                    :text "Load project"
                    :on-action {:event/type :load-project}}]
         :sound-changes []
         :source-data []}))

; ============================================================================================== }}} =
; = views ====================================================================================== {{{ =

;; <a id="gui/views"></a>
;; ## Parts of the interface

;; Functions that describe the look and behaviour of the GUI.

;; - [buttons](#gui/buttons-view)
;; - [dialog](#gui/dialog-view)
;; - [data](#gui/data-view)
;; - [root](#gui/root-view)

; - buttons ------------------------------------------------------------------------------------ {{{ -

;; <a id="gui/buttons-view"></a>

(defn- buttons-view
  "A bar with buttons exposing core functions."
  [state]
  {:fx/type :tool-bar
   :items (:buttons state)})

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
              :min-width 150
              :text "Display"} 
    :id      {:fx/type :table-column
              :cell-value-factory property
              :min-width 50
              :text "ID"}
    (throw (ex-info "An error that shouldn't have happened in column-factory." {})))) 

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

(defn- item-view
  "Makes a view for a single sound change."
  [item]
  {:fx/type :h-box
   :padding 3
   :spacing 5
   :children [{:fx/type :check-box
               :on-action (fn [_]
                            (swap! *state update :sound-changes
                                   (partial map (fn [i]
                                                  (cond-> i
                                                    (= (:id i) (:id item))
                                                    (update :active? not))))))
               :selected (:active? item)}
              {:fx/type :label
               :disable (-> item :active? not)
               :text (-> item :fn meta :name str)
               :tooltip {:fx/type :tooltip
                         :show-delay [333 :ms]
                         :text (-> item :fn meta :doc)}}
              {:fx/type :button
               :text "▲"}
              {:fx/type :button
               :text "▼"}]})

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -

; - data-view - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -{{{ -

(defn- data-view
  "Tables displaying sound changes and data."
  [state]
  {:fx/type :h-box
   :padding 10
   :spacing 10
   :children (cond-> [; sound changes
                      {:fx/type :scroll-pane
                       :content {:fx/type :v-box
                                 :children (map item-view (:sound-changes state))}}
                      ; source-data
                      {:fx/type :table-view
                       :column-resize-policy :constrained
                       :columns (if (empty? (:target-data state))
                                  [(column-maker :display)]
                                  [(column-maker :id)
                                   (column-maker :display)])
                       :items (:source-data state)
                       :row-factory {:fx/cell-type :table-row
                                     :describe data-tooltip}
                       :selection-mode :multiple}]
               ; target data
               (seq (:target-data state))
               (conj {:fx/type :table-view
                      :column-resize-policy :constrained
                      :columns [(column-maker :id)
                                (column-maker :display)]
                      :items (:target-data state)
                      :row-factory {:fx/cell-type :table-row
                                    :describe data-tooltip}
                      :selection-mode :multiple}))})

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -

; ---------------------------------------------------------------------------------------------- }}} -
; - dialog ------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/dialog-view"></a>

(defn- dialog-view
  "A dialog to display errors etc."
  [type       ; :error
   header     ; title text
   content]   ; main text
  {:fx/type :alert
   :alert-type type
   :showing true
   :on-close-request (fn [^DialogEvent e]
                       (when (nil? (.getResult ^Dialog (.getSource e)))
                         (.consume e)))
   :header-text header
   :content-text content
   :button-types [:ok]})

; ---------------------------------------------------------------------------------------------- }}} -
; - root --------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/root-view"></a>

(defn- root-scene
  "The root scene."
  [state]
  {:fx/type :scene
   :root {:fx/type :v-box
          :children [(buttons-view state)
                     (data-view state)]}})

(defn- root-view
  "The root stage."
  [state]
  {:fx/type :stage
   :showing true
   :title "SCRepl"
   :scene (root-scene state)
   :on-close-request {:event/type :window-close}})

; ---------------------------------------------------------------------------------------------- }}} -
   
; ============================================================================================== }}} =
; = handlers =================================================================================== {{{ =

;; <a id="gui/handlers"></a>
;; ## Functionality

;; Wrappers provide a link between [screpl.core](#screpl.core) functions and the GUI. The [event handler](#gui/event-handler) reacts to user’s actions in the GUI and dispatches work to the appropriate wrappers, while simultaneously catching errors from [screpl.core](#screpl.core), allowing it to focus on the happy path.

;; - [load-project](#gui/load-project)
;; - [event-handler(#gui/event-handler)

; - load-project ------------------------------------------------------------------------------- {{{ -

;; <a id="gui/load-project"></a>

(defn- load-project
  "Wrapper around `core/load-project`."
  [event]
  ; prepare a file chooser window
  (let [file-chooser  (FileChooser.)
        window        (-> event :fx/event .getSource .getScene .getWindow)] 
    (.addAll (.getExtensionFilters file-chooser)
             [(FileChooser$ExtensionFilter. "Clojure files (*.clj)" ["*.clj"])
              (FileChooser$ExtensionFilter. "All files (*.*)" ["*.*"])])
    ; wait for file selection
    (when-let [selected-file (.showOpenDialog file-chooser window)]
      (let [project (core/load-project *sci-ctx* (.getAbsolutePath selected-file))]
        (when (seq (:target-data project))
          (swap! *state assoc :target-data (:target-data project)))
        (swap! *state assoc :source-data (:source-data project))
        (swap! *state assoc :sound-changes (map-indexed
                                             (fn [idx itm]
                                               (hash-map :active? true
                                                         :fn itm
                                                         :id idx))
                                             (:sound-changes project)))))))

; ---------------------------------------------------------------------------------------------- }}} -

; - event-handler ------------------------------------------------------------------------------ {{{ -

;; <a id="gui/event-handler"></a>

(defn- event-handler
  "Reacts to user’s actions in the GUI and dispatches work to other functions. At the same time, catches errors and redirects them to `message`."
  [event]
  (try
    (case (:event/type event)
      :load-project (load-project event)
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
  [type          ; :error, :info, :ok, :quest
   & messages]   ; an Exception when :error, string(s) otherwise
  (case type
    :error (let [err  (-> messages first Throwable->map)
                 data (:data err)
                 bits (cond-> []
                        (:filename data) (conj (str " in " (:filename data)))
                        (:index data) (conj (str " in item " (:index data)))
                        (:display data) (conj (str " (" (:display data) ")"))
                        (:field data) (conj (str " in " (:field data))))]
             (fx/on-fx-thread
               (fx/create-component
                 (dialog-view :error "Error" (str "Error" (apply str bits) ": " (:cause err))))))))

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

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
