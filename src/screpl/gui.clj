(ns screpl.gui
  (:require [cljfx.api :as fx]
            [clojure.string :as string]
            [sci.core :as sci]
            [screpl.core :as core])
  (:import [javafx.scene.control Dialog DialogEvent]
           [javafx.stage FileChooser FileChooser$ExtensionFilter]))

(declare message stop-gui)

; = globals ==================================================================================== {{{ =

(def ^:dynamic *sci-ctx*
 "Global variable, holds the context for SCI."
 (sci/init {:namespaces {}}))

(def ^:dynamic *state
  "Global variable, holds the state for the GUI."
  (atom {:buttons [{:fx/type :button
                    :text "Load project"
                    :on-action {:event/type :load-project}}]
         :source-data []
         :target-data []}))

; ============================================================================================== }}} =
; = views ====================================================================================== {{{ =

; - buttons ------------------------------------------------------------------------------------ {{{ -

(defn buttons-view
  [state]
  {:fx/type :tool-bar
   :items (:buttons state)})

; ---------------------------------------------------------------------------------------------- }}} -
; - dialog ------------------------------------------------------------------------------------- {{{ -

(defn dialog-view
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
; - data --------------------------------------------------------------------------------------- {{{ -

(defn column-factory
  [property]    ; the keyword in source- and target-data
  {:fx/type :table-column
   :text (-> property name string/capitalize)
   :cell-value-factory property})

(defn data-view
  [state]
  {:fx/type :h-box
   :children (cond-> [{:fx/type :table-view
                       :column-resize-policy :constrained
                       :columns (if (empty? (:target-data state))
                                  [(column-factory :display)]
                                  [(column-factory :id)
                                   (column-factory :display)])
                       :items (:source-data state)
                       :selection-mode :multiple}]
               ; target data
               (seq (:target-data state))
               (conj {:fx/type :table-view
                      :column-resize-policy :constrained
                      :columns [(column-factory :id)
                                (column-factory :display)]
                      :items (:target-data state)
                      :selection-mode :multiple}))})

; ---------------------------------------------------------------------------------------------- }}} -
; - root --------------------------------------------------------------------------------------- {{{ -

(defn root-scene
  [state]
  {:fx/type :scene
   :root {:fx/type :v-box
          :children [(buttons-view state)
                     (data-view state)]}})

(defn root-view
  [state]
  {:fx/type :stage
   :showing true
   :title "SCRepl"
   :scene (root-scene state)
   :on-close-request {:event/type :window-close}})

; ---------------------------------------------------------------------------------------------- }}} -
   
; ============================================================================================== }}} =
; = handlers =================================================================================== {{{ =

; - load-project ------------------------------------------------------------------------------- {{{ -

(defn load-project
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
        (when-let [trg (:target-data project)]
          (swap! *state assoc :target-data trg))
        (swap! *state assoc :source-data (:source-data project))))))

; ---------------------------------------------------------------------------------------------- }}} -

; - event-handler ------------------------------------------------------------------------------ {{{ -

(defn event-handler
  [event]
  (try
    (case (:event/type event)
      :load-project (load-project event)
      :window-close (stop-gui))
    (catch Exception e (message :error e))))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = other ====================================================================================== {{{ =

; - message ------------------------------------------------------------------------------------ {{{ -

(defn message
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
                 (dialog-view :error "Error" (str "Error" (apply str bits) ":\n" (:cause err))))))))

; ---------------------------------------------------------------------------------------------- }}} -

; - renderer ----------------------------------------------------------------------------------- {{{ -

(def renderer
  (fx/create-renderer
    :middleware (fx/wrap-map-desc root-view)
    :error-handler (bound-fn [^Throwable ex] (.printStackTrace ^Throwable ex *err*))
    :opts {:fx.opt/map-event-handler event-handler 
           ; improved errors; see https://github.com/cljfx/dev
           :fx.opt/type->lifecycle @(requiring-resolve 'cljfx.dev/type->lifecycle)}))

; ---------------------------------------------------------------------------------------------- }}} -
; - start-gui ---------------------------------------------------------------------------------- {{{ -

(defn start-gui
  []
  ; (javafx.application.Platform/setImplicitExit true)
  ; (fx/on-fx-thread
  (fx/mount-renderer *state renderer))

; ---------------------------------------------------------------------------------------------- }}} -
; - stop-gui ----------------------------------------------------------------------------------- {{{ -

(defn stop-gui
  []
  ; (fx/on-fx-thread
  (fx/unmount-renderer *state renderer))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
