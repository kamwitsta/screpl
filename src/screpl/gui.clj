;; A JavaFX user interface.

;; 1. [Global variables](#gui/globals)
;; 2. [General utility functions](#gui/general)
;; 3. [Parts of the interface](#gui/views)
;; 4. [Functionality](#gui/handlers)
;; 5. [Other](#gui/other)

(ns screpl.gui
  (:require [cljfx.api :as fx]
            [clojure.core.async :as async]
            [clojure.java.browse :as browse]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [screpl.core :as core])
  (:import [javafx.stage FileChooser FileChooser$ExtensionFilter]
           [com.mifmif.common.regex Generex]))


; = globals ==================================================================================== {{{ =

;; <a id="gui/globals"></a>
;; ## Global variables

(declare filter-data load-project message print-paths print-tree stop-gui surround-string unescape-unicode)

;; Used to pass `:cancel` messages to `core` functions.
(def cancel-ch (async/chan))

;; The width of the views in [data-view](#gui/data-view).
;; Also used to derive window size.
(def column-width 200)

;; Update the progress bar after every … steps.
(def progress-step 1000)

(def ^:dynamic *state
 "Global variable, holds the state for the GUI."
 (atom {; references to accordions
        :accordion {:data nil
                    :sound-changes nil}
        ; ad hoc tabs
        :ad-hoc {:data ""
                 :sound-changes ""}
        ; dialog window for errors and progress reporting
        :dialog nil
        ; filtering data in `data-view`
        :filter-pattern ""
        ; `output-view`
        :output {:text ""
                 :tooltip "No results yet."}
        ; `paths-view` window
        :paths-view {:pattern ""
                     :intermediate true
                     :showing false}
        ; the loaded project
        :project {:filename nil
                  :data []
                  :data-filtered []
                  :sound-changes []
                  :has-target-data? false}
        ; selected item in `data-view` (handled by `event-handler`
        :selection nil 
        ; size of the main window
        :window {:height (* column-width 4)
                 :width (* column-width 2)}}))

; ============================================================================================== }}} =
; = general ==================================================================================== {{{ =

;; <a id="gui/general"></a>
;; ## General utility functions

(defn- get-active-pane
  "Checks which pane is currently open in an accordion."
  [box]
  ; when .getExpandedPane or .getText fail, they don't just return nil, they throw an error
  (try
    (-> @*state :accordion box .getExpandedPane .getText)
    (catch Exception _ nil)))


(defn- get-active-data
  "Gets active source data from *state."
  []
  (case (get-active-pane :data)
    "Ad hoc"  (-> @*state :ad-hoc :data (surround-string "[" "]") core/load-data first)
    "Project" (:selection @*state)
    nil))


(defn- get-active-functions
  "Gets active sound change functions from *state."
  []
  (case (get-active-pane :sound-changes)
    "Ad hoc"  (-> @*state :ad-hoc :sound-changes core/load-scs)
    "Project" (->> @*state :project :sound-changes (filter :active?) (map :item))
    nil))


(defn- sample-strings
  "Generate strings that match a pattern (given as a String)."
  [pattern    ; (String) match this pattern
   & n]       ; return this many samples (3 if not given)
  (try
    (str "Sample matching strings:\n  "
      (let [grx (Generex. (unescape-unicode pattern))]
        (->> (repeatedly #(.random grx))
             (take (or n 3))
             (string/join "\n  "))))
    (catch Exception e "invalid regular expression")))


(defn- surround-string
  "Adds a prefix and a suffix to a string"
  [s prefix suffix]
  (str prefix s suffix))


(defn- unescape-unicode
  "Convert Unicode escape sequence to actual character."
  ; Generex can't do it on its own.
  [s]
  (string/replace s #"\\u([0-9a-fA-F]{4})" 
                   (fn [[_ hex-digits]]
                     (str (char (Integer/parseInt hex-digits 16))))))

; ============================================================================================== }}} =
; = views ====================================================================================== {{{ =

;; <a id="gui/views"></a>
;; ## Parts of the interface

;; Functions that describe the look and behaviour of the GUI.

;; - [data](#gui/data-view)
;; - [dialog](#gui/dialog-view)
;; - [menu](#gui/menu-view)
;; - [output](#gui/output-view)
;; - [paths](#gui/paths-view)
;; - [root](#gui/root-view)

; - data --------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/data-view"></a>

; - accordion-created  - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -{{{ -

(defn- accordion-created
  "Cljfx does not export pane-expansion props for :accordion. This function saves a reference to an :accordion so that Java interop can be later used to check which pane is currently expanded, and also opens the first pane as by default all panes are closed on startup."
  [event    ; on-create event = the accordion
   type]    ; :data or :sound-changes
  (swap! *state assoc-in [:accordion type] event)
  (-> event (.setExpandedPane (-> event .getPanes first))))

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -
; - data-columns - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - {{{ -

(defn- data-columns
  "Makes data columns for `data-view`."
  [has-target-data?]
  (let [id-col  {:fx/type :table-column
                 :cell-value-factory #(-> % first :id)
                 :pref-width (* column-width 1/4)
                 :text "ID"}
        src-col {:fx/type :table-column
                 :cell-value-factory #(-> % first :display)
                 :pref-width column-width
                 :text "Source"}
        trg-col {:fx/type :table-column
                 :cell-value-factory #(-> % second :display)
                 :pref-width column-width
                 :text "Target"}]
    (if has-target-data?
      [id-col src-col trg-col]
      [src-col])))

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -
; - data-tooltip - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - {{{ -

(defn- data-tooltip
  "Makes a tooltip for a data item."
  [item]
  (letfn [(get-kvs [[k v]]
            (if (= k :id)
              ""
              (str "  " (name k) ": " (pr-str v) "\n")))]
    {:tooltip {:fx/type :tooltip
               :show-delay [333 :ms]
               :text (cond-> (str "ID: " (-> item first :id) "\n"
                                 "\nSource:\n"
                                 (->> item first (map get-kvs) (apply str)))
                       (-> @*state :project :has-target-data?)
                       (str "\nTarget:\n"
                            (->> item second (map get-kvs) (apply str))))}}))

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -
; - pane-click-handler - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - {{{ -

(defn- pane-click-handler
  "Makes sure one pane in an accordion is always open."
  [event]
  (let [source (-> event .getSource)
        parent (-> event .getSource .getParent)]
    (when (-> parent .getExpandedPane nil?)
      (-> parent (.setExpandedPane source)))))

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -
; - scs-item-view - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -{{{ -

(defn- swap-elements
  "Swaps two elements inside a collection. Also works on lazy sequences because `coll` is converted to a vector."
  [coll i j]
  (let [v (vec coll)]
    (assoc v
           i (nth v j)
           j (nth v i))))

(defn- scs-item-view
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
                                         (swap! *state update-in [:project :sound-changes]
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
                                         (swap! *state update-in [:project :sound-changes]
                                                #(swap-elements % index (inc index))))
                            :font 8
                            :max-height 18
                            :max-width 18
                            :min-height 18
                            :min-width 18
                            :text "▼"
                            :visible (< index (dec (count (->@*state :project :sound-changes))))}]}
               {:fx/type :check-box
                :selected (:active? item)
                :on-action (fn [_]
                             (swap! *state update-in [:project :sound-changes]
                                    (partial map (fn [i]
                                                   (cond-> i
                                                     (= (:id i) (:id item))
                                                     (update :active? not))))))}
               {:fx/type :label
                :disable (-> item :active? not)
                :text (-> item :item meta :name str)
                :tooltip {:fx/type :tooltip
                          :show-delay [333 :ms]
                          :text (-> item :item meta :doc)}}]})

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -

(defn- data-view
  "Tables displaying sound changes and data."
  [state]
  (let [has-target-data? (-> state :project :has-target-data?)]
    {:fx/type :split-pane
     :divider-positions (if has-target-data? [0.333 0.666] [0.5])
     :items [; sound changes
             {:fx/type fx/ext-on-instance-lifecycle   ; provides :on-created
              :on-created #(accordion-created % :sound-changes)
              :padding 0
              :pref-width column-width
              :desc {:fx/type :accordion
                     :panes [{:fx/type :titled-pane
                              :text "Project"
                              :content {:fx/type :scroll-pane
                                        :content {:fx/type :v-box
                                                  :children (map-indexed scs-item-view (-> state :project :sound-changes))}}
                              :on-mouse-clicked pane-click-handler}
                             {:fx/type :titled-pane
                              :text "Ad hoc"
                              :content {:fx/type :text-area
                                        :prompt-text "Write sound change function/s here…"
                                        ; there is a :text prop but it i don't seem to be able to bind it to state, so :on-text-changed instead
                                        :on-text-changed {:event/type ::ad-hoc-sc-key-pressed}}
                              :on-mouse-clicked pane-click-handler}]}}
             ; data
             {:fx/type fx/ext-on-instance-lifecycle   ; provides :on-created
              :on-created #(accordion-created % :data)
              :pref-width column-width
              :desc {:fx/type :accordion
                     :panes [{:fx/type :titled-pane
                              :text "Project"
                              :on-mouse-clicked pane-click-handler
                              :content {:fx/type :v-box
                                        :padding 0
                                        :children [{:fx/type :h-box
                                                    :children [{:fx/type :text-field
                                                                :prompt-text "Filter data using regular expressions…"
                                                                :text (-> state :filter-pattern)
                                                                :h-box/hgrow :always
                                                                :on-key-pressed {:event/type ::filter-key-pressed}
                                                                :on-text-changed {:event/type ::change-filter-pattern}
                                                                :tooltip {:fx/type :tooltip
                                                                          :show-delay [333 :ms]
                                                                          :text (-> state :filter-pattern sample-strings)}}
                                                               {:fx/type :button
                                                                :text "Filter"
                                                                :disable (-> @*state :project :filename nil?)
                                                                :on-action {:event/type ::filter-data}}]}
                                                   {:fx/type :table-view
                                                    :column-resize-policy :constrained  ; don't show an extra column
                                                    :columns (data-columns has-target-data?)
                                                    :items (-> state :project :data-filtered)
                                                    :on-selected-item-changed {:event/type ::select-datum}
                                                    :pref-width (* column-width (if has-target-data? 2.25 1))
                                                    :row-factory {:fx/cell-type :table-row
                                                                  :describe data-tooltip}
                                                    :selection-mode :single}]}}
                             {:fx/type :titled-pane
                              :text "Ad hoc"
                              :content {:fx/type :text-area
                                        :prompt-text "Write a source datum here…"
                                        ; there is a :text prop but it i don't seem to be able to bind it to state, so :on-text-changed instead
                                        :on-text-changed {:event/type ::ad-hoc-data-key-pressed}}
                              :on-mouse-clicked pane-click-handler}]}}]}))

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
       :content-text (:message dialog)
       :header-text ""
       :on-close-request {:event/type ::close-dialog}
       :showing true}

      :progress
      {:fx/type :stage
       :modality :application-modal     ; block the main window
       :scene {:fx/type :scene
               :root {:fx/type :v-box
                      :alignment :center
                      :spacing 6
                      :children [{:fx/type :label
                                  :text (:message dialog)}
                                 (if (pos? (-> state :dialog :progress))
                                   ; when the max value is known
                                   {:fx/type :progress-bar
                                    :progress (-> state :dialog :progress)}
                                   ; when it isn't
                                   {:fx/type :progress-indicator
                                    :max-height 24
                                    :max-width 24
                                    :min-height 24
                                    :min-width 24})
                                 {:fx/type :button
                                  :on-action {:event/type ::cancel-operation}
                                  :text "Cancel"}]}}
       :on-close-request {:event/type ::cancel-operation}
       :showing true
       :width 200}

      (throw (ex-info (str "An error in dialog-view that shouldn't have happened. `state`=" state) {})))))

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
                     :on-action {:event/type ::load-project}}
                    {:fx/type :menu-item
                     :text "Reload project"
                     :accelerator [:shortcut :r]
                     :disable (-> @*state :project :filename nil?)
                     :on-action {:event/type ::reload-project}}
                    {:fx/type :separator-menu-item}
                    {:fx/type :menu-item
                     :text "Quit"
                     :accelerator [:shortcut :q]
                     :on-action stop-gui}]}
           {:fx/type :menu
            :text "Single"
            :items [{:fx/type :menu-item
                     :text "Paths"
                     :accelerator [:shortcut :f]
                     :on-action {:event/type ::toggle-paths-view}}
                    {:fx/type :menu-item
                     :text "Print leaves"
                     :accelerator [:shortcut :l]
                     :disable (let [data (get-active-data)
                                    scs (get-active-functions)]
                                (println "data:" data)
                                (println "scs:" scs)
                                (println "and data scs:" (and data scs))
                                (nil? (and data scs)))
                     :on-action {:event/type ::print-leaves}}
                    {:fx/type :menu-item
                     :text "Print tree"
                     :accelerator [:shortcut :t]
                     ; :disable (and (get-active-data) (get-active-functions))
                     :on-action {:event/type ::print-tree}}]}
           {:fx/type :menu
            :text "Batch"
            :items [{:fx/type :menu-item
                     :text "Find irregulars"
                     :accelerator [:shortcut :i]
                     :disable (-> @*state :project :has-target-data? not)
                     :on-action {:event/type ::find-irregulars}}]}]})

; ---------------------------------------------------------------------------------------------- }}} -
; - output ------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/output-view"></a>

(defn- output-view
  "A field displaying the results."
  [state]
  (let [style (slurp (io/resource "output-view.css"))]
    {:fx/type :web-view
     :context-menu-enabled false
     :url (str "data:text/html;charset=utf-8,"
               "<html><head><style>"
               style
               "</style></head>"
               "<body title='" (-> state :output :tooltip) "'>"
               (-> state :output :text)
               "</body></html>")}))

; ---------------------------------------------------------------------------------------------- }}} -
; - paths -------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/paths-view"></a>

(defn- paths-view
  "A window with controls for path finding."
  [state]
  {:fx/type :stage
   :on-close-request {:event/type ::close-paths-view}
   :scene {:fx/type :scene
           :on-key-pressed {:event/type ::close-paths-view-maybe}
           :root {:fx/type :v-box
                  :padding 6
                  :spacing 6
                  :children [{:fx/type :text-field
                              :on-text-changed {:event/type ::change-paths-pattern}}
                             {:fx/type :label
                              :text (-> state :paths-view :pattern sample-strings)}
                             {:fx/type :separator}
                             {:fx/type :h-box
                              :spacing 6
                              :children [{:fx/type :v-box
                                          :children [{:fx/type :label
                                                      :text "."}
                                                     {:fx/type :label
                                                      :text "\\."}
                                                     {:fx/type :label
                                                      :text "\\u0000"}
                                                     {:fx/type :label
                                                      :text "[abc]"}
                                                     {:fx/type :label
                                                      :text "[^abc]"}
                                                     {:fx/type :label
                                                      :text "[a-zA-Z]"}
                                                     {:fx/type :label
                                                      :text "(ab|cd)"}
                                                     {:fx/type :label
                                                      :text "X?"}
                                                     {:fx/type :label
                                                      :text "X+"}
                                                     {:fx/type :label
                                                      :text "^X"}
                                                     {:fx/type :label
                                                      :text "X$"}]}
                                         {:fx/type :v-box
                                          :children [{:fx/type :label
                                                      :text "any character"}
                                                     {:fx/type :label
                                                      :text "dot"}
                                                     {:fx/type :label
                                                      :text "Unicode character"}
                                                     {:fx/type :label
                                                      :text "a, b, or c"}
                                                     {:fx/type :label
                                                      :text "not a, b, or c"}
                                                     {:fx/type :label
                                                      :text "a through z, or A through Z"}
                                                     {:fx/type :label
                                                      :text "ab, or cd"}
                                                     {:fx/type :label
                                                      :text "X, once or not at all"}
                                                     {:fx/type :label
                                                      :text "X, one or more times"}
                                                     {:fx/type :label
                                                      :text "X, at the beginning"}
                                                     {:fx/type :label
                                                      :text "X, at the end"}]}]}
                             {:fx/type :hyperlink
                              :text "Full list of constructs"
                              :on-action (fn [_] (browse/browse-url "https://docs.oracle.com/en/java/javase/15/docs/api/java.base/java/util/regex/Pattern.html"))}
                             {:fx/type :separator}
                             {:fx/type :v-box
                              :spacing 6
                              :children [{:fx/type :h-box
                                          :alignment :center-right
                                          :spacing 6
                                          :children [{:fx/type :label
                                                      :text "Search intermediate"}
                                                     {:fx/type :check-box
                                                      :selected (-> state :paths-view :intermediate)
                                                      :on-action (fn [_]
                                                                   (swap! *state update-in [:paths-view :intermediate] not))}]}
                                         {:fx/type :h-box
                                          :alignment :center-right
                                          :spacing 6
                                          :children [{:fx/type :button
                                                      :text "Print in tree"
                                                      ; :disable (and (get-active-data) (get-active-functions))
                                                      :on-action {:event/type ::print-tree-paths}}
                                                     {:fx/type :button
                                                      :text "Print paths"
                                                      ; :disable (and (get-active-data) (get-active-functions))
                                                      :on-action {:event/type ::print-paths}}]}]}]}}
   :showing (-> state :paths-view :showing)
   :title "Paths"})

; ---------------------------------------------------------------------------------------------- }}} -
; - root --------------------------------------------------------------------------------------- {{{ -

;; <a id="gui/root-view"></a>

(defn- root-scene
  "The root scene."
  [state]
  {:fx/type :scene
   :root {:fx/type :v-box
          :children [(menu-view state)
                     {:fx/type :split-pane
                      ; ↓ forces the recreation of the layout after the data change (and in consequence, window size)
                      :fx/key (some? (-> state :project :has-target-data?))
                      :divider-positions [0.5]
                      :orientation :vertical
                      :items [(output-view state)
                              (data-view state)]}]}})

(defn- root-view
  "The root stage."
  [state]
  {:fx/type fx/ext-many
   :desc (cond-> [{:fx/type :stage
                   :on-close-request {:event/type ::close-app}
                   :scene (root-scene state)
                   :showing true
                   :title "SCRepl"
                   :height (-> state :window :height)
                   :width (-> state :window :width)}
                  (paths-view state)]
           (:dialog state) (conj (dialog-view state)))})

; ---------------------------------------------------------------------------------------------- }}} -
   
; ============================================================================================== }}} =
; = handlers =================================================================================== {{{ =

;; <a id="gui/handlers"></a>
;; ## Functionality

;; Wrappers provide a link between [screpl.core](#screpl.core) functions and the GUI. The [event handler](#gui/event-handler) reacts to user’s actions in the GUI and dispatches work to the appropriate wrappers, while simultaneously catching errors from [screpl.core](#screpl.core), allowing it to focus on the happy path.

;; - [find-irregulars](#gui/find-irregulars)
;; - [grow-tree](#gui/grow-tree)
;; - [load-project](#gui/load-project)
;; - [print-leaves](#gui/print-leaves)
;; - [print-paths](#gui/print-paths)
;; - [print-tree](#gui/print-tree)
;; - [print-tree-paths](#gui/print-tree-paths)
;; - [event-handler(#gui/event-handler)

; - find-irregulars ---------------------------------------------------------------------------- {{{ -

;; <a id="gui/find-irregulars"></a>

(defn- find-irregulars
  "Wrapper around `core/grow-tree`, for use by other functions in the gui namespace."
  [_]
  (let [counter   (atom 0)
        functions (get-active-functions)
        output-ch (async/chan 12)]
    ; listen for output
    (async/go-loop []
      (when-let [output (async/<! output-ch)]
        (case (:status output)
          :cancelled (do
                       (swap! *state assoc :dialog nil)   ; close the dialog
                       (swap! *state update-in [:output :text] str
                              "<span style='color:white;background-color:crimson;'>Cancelled.</span>"))
          :completed (swap! *state assoc :dialog nil)     ; close the dialog
          :partial   (do
                       (swap! *state update-in [:output :text] str
                              (str "Expected: "
                                   (-> output :output :item first :display)
                                   " ≫ "
                                   (-> output :output :item second :display)
                                   "<br>"
                                   "Instead: "
                                   (->> output :output :result (string/join ", "))
                                   "<br><br>"))
                       (recur))
          :progress  (do
                       (swap! counter inc)
                       (swap! *state assoc-in [:dialog :progress]
                              (/ @counter (count functions)))
                       (recur))
          (throw (ex-info (str "An error in find-irregulars that shouldn't have happened. `output`=" output) {}))))) 
    ; run `core/find-irregulars`
    (async/go
      (message :progress)                                       ; open dialog
      (swap! *state assoc :output {:text "", :tooltip ""})      ; wipe `output-view`
      (core/find-irregulars functions                           ; actually run the thing
                            (-> @*state :project :data-filtered)
                            cancel-ch
                            output-ch)
      (async/close! output-ch))))                               ; clean up

; ---------------------------------------------------------------------------------------------- }}} -
; - grow-tree ---------------------------------------------------------------------------------- {{{ -

;; <a id="gui/grow-tree"></a>

(defn- grow-tree
  "Convenience wrapper around `core/grow-tree`, for use by other functions in the gui namespace."
  []
  (let [fns (get-active-functions)
        val (-> (get-active-data) first)]
    (core/grow-tree fns val)))

; ---------------------------------------------------------------------------------------------- }}} -
; - load-project ------------------------------------------------------------------------------- {{{ -

;; <a id="gui/load-project"></a>

; - chooser-dialog - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - {{{ -

(defn- chooser-dialog
  "Opens a file chooser dialog."
  [event]
  (let [window  (-> event :fx/event .getTarget .getParentPopup .getOwnerWindow) 
        chooser (FileChooser.)]
    (.addAll (.getExtensionFilters chooser)
             [(FileChooser$ExtensionFilter. "Clojure files (*.clj)" ["*.clj"])
              (FileChooser$ExtensionFilter. "All files (*.*)" ["*.*"])])
    (when-let [selected-file (.showOpenDialog chooser window)]
      (.getAbsolutePath selected-file))))

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -

(defn- load-project
  "Wrapper around `core/load-project`."
  [event
   filename]     ; open dialog if nil
  ; get the filename, from the event handler, or from a dialog
  (let [
        ; fname   (or filename (chooser-dialog event))
        fname   "/home/kamil/devel/clj/screpl/doc/sample-project.clj"
        project (core/load-project fname)]
    ; change the width of the window to accomodate target data
    ; both height and width must be given, and
    ; both must at least simulate change to trigger cljfx's watch on *state
    (swap! *state assoc-in [:window :width] (* column-width (if (:has-target-data? project) 3.25 2)))
    (swap! *state update-in [:window :height] (fnil inc 0))
    ; load data into *state
    (let [scs (map-indexed
                (fn [idx itm]
                  (hash-map :active? true
                            :id idx
                            :item itm))
                (:sound-changes project))
          new-project (assoc project
                             :data-filtered (:data project)
                             :sound-changes scs)]
      (swap! *state assoc :project new-project))))

; ---------------------------------------------------------------------------------------------- }}} -
; - print-leaves ------------------------------------------------------------------------------- {{{ -

;; <a id="gui/print-leaves"></a>

; - format-leaves - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -{{{ -

(defn- format-leaves
  "Format leaves for printing: highlight match with target and add commas."
  [data
   output]
  (let [target (-> data second :display)]
    (->> output
         :output
         (map :display)
         (map #(if (= % target)
                 (str "<span style='color:limegreen'>" % "</span>")
                 %))
         (string/join ", "))))

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -

(defn print-leaves
  "Wrapper around `core/print-leaves`."
  [_]
  (let [data        (get-active-data)
        functions   (get-active-functions)
        counter     (atom 0)
        output-ch   (async/chan 12)]  ; 12 is just to make sure nothing has to wait
    ; listen for output
    (async/go-loop []
      (when-let [output (async/<! output-ch)]
        (case (:status output)
          :cancelled (do
                       (swap! *state assoc :dialog nil)   ; close the dialog
                       (swap! *state update-in [:output :text] str
                              "<span style='color:white;background-color:crimson;'>Cancelled.</span>"))
          :completed (do
                       (swap! *state assoc :dialog nil)     ; close the dialog
                       (swap! *state assoc-in [:output :text]
                              (str (-> data first :display) " ≫ " (format-leaves data output))))
          :progress  (do
                       (swap! counter inc)
                       (swap! *state assoc-in [:dialog :progress]
                              (/ @counter (count functions)))
                       (recur))
          (throw (ex-info (str "An error in print-leaves that shouldn't have happened. `output`=" output) {}))))) 
    ; run `core/find-paths
    (async/go
      (message :progress)                                     ; open dialog
      (swap! *state assoc :output {:text "", :tooltip ""})    ; wipe `output-view`
      (core/print-leaves functions                            ; actually run the thing
                         data 
                         cancel-ch
                         output-ch)
      (async/close! output-ch))))                             ; clean up

; ---------------------------------------------------------------------------------------------- }}} -
; - print-paths -------------------------------------------------------------------------------- {{{ -

;; <a id="gui/print-paths"></a>

(defn- print-paths
  "Wrapper around `core/grow-tree` and then `core/find-paths`."
  [_]
  (let [tree      (grow-tree)
        counter   (atom 0)
        output-ch (async/chan 12)]  ; 12 is just to make sure nothing has to wait
    ; listen for output
    (async/go-loop []
      (when-let [output (async/<! output-ch)]
        (case (:status output)
          :cancelled (do
                       (swap! *state assoc :dialog nil)   ; close the dialog
                       (swap! *state update-in [:output :text] str
                              "<span style='color:white;background-color:crimson;'>Cancelled.</span>"))
          :completed (swap! *state assoc :dialog nil)     ; close the dialog
          :progress  (do
                       (swap! counter inc)
                       (when (zero? (mod @counter progress-step))
                         (swap! *state assoc-in [:dialog :message]
                                (str "Checked " (format "%,d" @counter) " leaves…")))
                       (recur))
          :partial   (do
                       (swap! *state update-in [:output :text] str
                              (string/join " > " (:output output)) "<br>")
                       (recur))
          (throw (ex-info (str "An error in print-paths that shouldn't have happened. `output`=" output) {}))))) 
    ; run `core/find-paths`
    (async/go
      (message :progress)                                       ; open dialog
      (swap! *state assoc :output {:text "", :tooltip ""})      ; wipe `output-view`
      (core/find-paths tree                                     ; actually run the thing
                       (re-pattern (-> @*state :paths-view :pattern))
                       (-> @*state :paths-view :intermediate)
                       cancel-ch
                       output-ch)
      (async/close! output-ch))))                               ; clean up

; ---------------------------------------------------------------------------------------------- }}} -
; - print-tree --------------------------------------------------------------------------------- {{{ -

;; <a id="gui/print-tree"></a>

; - format-tree-tooltip - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -{{{ -

(defn- format-tree-tooltip
  "Format a tooltip with basic stats of a tree."
  [counts]
  (let [linebreak "%26%2310%3B"]  ; (java.net.URLEncoder/encode "&#10;")
    (str "A tree from \"" (-> (get-active-data) :source-data :display) "\" through" linebreak
         "  " (count (get-active-functions)) " sound changes, with" linebreak
         "  " (format "%,d" (:nodes counts)) " nodes and" linebreak
         "  " (format "%,d" (:leaves counts)) " leaves.")))

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} -

(defn- print-tree
  "Wrapper around `core/grow-tree` and then `core/print-tree`."
  [_          ; event
   path]      ; path to highlight
  (let [tree      (grow-tree)
        buffer    (atom "")     ; see under :in-progress
        counter   (atom 0)
        output-ch (async/chan 12)]
    ; handle the output from core/print-tree
    (async/go-loop []
      (when-let [output (async/<! output-ch)]
        (case (:status output)
          :cancelled (do
                       (swap! *state assoc :dialog nil)    ; close the dialog
                       (swap! *state update-in [:output :text] str "<span style='color:white;background-color:crimson;'>Cancelled.</span>")
                       (swap! *state assoc-in [:output :tooltip] "Tree printing cancelled."))
          :completed (do
                       (swap! *state assoc :dialog nil)    ; close the dialog
                       (swap! *state assoc-in [:output :tooltip]
                              (->> output :output format-tree-tooltip)))
          :partial   (do
                       ; core/print-tree has no choice but to send lots of tiny updates
                       ; it's cheaper to do several big updates to output-view than loads of tiny ones
                       (swap! buffer str (:output output))
                       (recur))
          :progress  (do
                       (swap! counter inc)
                       (when (zero? (mod @counter progress-step))
                         (swap! *state assoc-in [:dialog :message]
                                (str "Processed " (format "%,d" @counter) " nodes…")))
                       (swap! *state update-in [:output :text] str @buffer)
                       (reset! buffer "")
                       (recur))
          (throw (ex-info (str "An error in print-tree that shouldn't have happened. `output`=" output) {}))))) 
    ; run core/print-tree
    (async/go
      (message :progress)                                   ; open dialog
      (swap! *state assoc :output {:text "", :tooltip ""})  ; wipe `output-view`
      (core/print-tree tree path cancel-ch output-ch)       ; run core/print-tree
      (async/close! output-ch))))                           ; clean up

; ---------------------------------------------------------------------------------------------- }}} -
; - print-tree-paths --------------------------------------------------------------------------- {{{ -

;; <a id="gui/print-tree-paths"></a>

(defn- print-tree-paths
  "Wrapper around `core/grow-tree`, and then `core/find-paths` and `core/print-tree`."
  [_]
  (let [path (core/find-paths (grow-tree)
                              (-> @*state :paths-view :pattern re-pattern)
                              (-> @*state :paths-view :intermediate)
                              cancel-ch
                              nil)]
    (print-tree nil path)))

; ---------------------------------------------------------------------------------------------- }}} -

; - event-handler ------------------------------------------------------------------------------ {{{ -

;; <a id="gui/event-handler"></a>

(defn- event-handler
  "Reacts to user’s actions in the GUI and dispatches work to other functions. At the same time, catches errors and redirects them to `message`."
  [event]
  (try
    (case (:event/type event)

      ; wrappers (must be here so errors are caught)
      ::find-irregulars (find-irregulars nil)
      ::load-project (load-project event nil)
      ::print-leaves (print-leaves nil)
      ::print-paths (print-paths nil)
      ::print-tree (print-tree nil #{})
      ::print-tree-paths (print-tree-paths nil)
      ::reload-project (load-project event (-> @*state :project :filename))

      ; ad-hoc
      ::ad-hoc-data-key-pressed (swap! *state assoc-in [:ad-hoc :data] (:fx/event event))
      ::ad-hoc-sc-key-pressed (swap! *state assoc-in [:ad-hoc :sound-changes] (:fx/event event))

      ; filtering
      ::change-filter-pattern (swap! *state assoc :filter-pattern (:fx/event event))
      ::filter-data (filter-data nil)
      ::filter-key-pressed (case (-> event :fx/event .getCode .getName)
                             "Enter" (event-handler {:event/type ::filter-data})
                             "Esc"   (do
                                       (swap! *state assoc :filter-pattern "")
                                       (event-handler {:event/type ::filter-data})
                                       (-> event :fx/event .getTarget .getParent .requestFocus))
                             nil)

      ; paths-view
      ::change-paths-pattern (swap! *state assoc-in [:paths-view :pattern] (:fx/event event))
      ::close-paths-view (swap! *state assoc-in [:paths-view :showing] false)
      ::close-paths-view-maybe (when (= "Esc" (-> event :fx/event .getCode .getName))
                                 (swap! *state assoc-in [:paths-view :showing] false))
      ::toggle-paths-view (swap! *state update-in [:paths-view :showing] not)

      ; selections
      ::select-datum (swap! *state assoc :selection (:fx/event event))

      ; other
      ::cancel-operation (async/>!! cancel-ch :cancel)
      ::close-app (stop-gui nil)
      ::close-dialog (swap! *state assoc :dialog nil))
    (catch Exception e (message :error e))))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = other ====================================================================================== {{{ =

;; <a id="gui/other"></a>
;; ## Other

;; The more technical parts: managing the GUI, and displaying dialogs.

;; - [filter-data](#gui/filter-data)
;; - [message](#gui/message)
;; - [renderer](#gui/renderer)
;; - [start-gui](#gui/start-gui)
;; - [stop-gui](#gui/stop-gui)

; - filter-data -------------------------------------------------------------------------------- {{{ -

;; <a id="gui/filter-data"></a>

(defn- filter-data
  "Filters the data displayed in `data-view`."
  [_]
  (if (= "" (:filter-pattern @*state))
    (swap! *state assoc-in [:project :data-filtered] (-> @*state :project :data))
    (let [pattern (-> @*state :filter-pattern re-pattern)
          result  (filter
                    #(->> %                              ; ({:id 1, :display "s"} {:id 1, :display "t"})
                         (map vals)                      ; ((1 "s") (1 "t"))
                         flatten                         ; (1 "s" 1 "t")
                         (map str)                       ; ("1" "s" "1" "t")
                         (map (partial re-find pattern)) ; (nil "s" nil nil)
                         (not-every? nil?))              ; true
                    (-> @*state :project :data))]
      (swap! *state assoc-in [:project :data-filtered] result))))

; ---------------------------------------------------------------------------------------------- }}} -
; - message ------------------------------------------------------------------------------------ {{{ -

;; <a id="gui/message"></a>

(defn- message
  "Displays a dialog with a message."
  [type          ; :error, :progress
   & messages]   ; an Exception when :error, string(s) or number(s) otherwise
  (case type
    :error    (let [err      (-> messages first Throwable->map)
                    data     (:data err)
                    location (cond-> []
                               (:filename data) (conj (str " in " (:filename data)))
                               (:index data)    (conj (str " in item " (:index data)))
                               (:display data)  (conj (str " (" (:display data) ")"))
                               (:field data)    (conj (str " in " (:field data))))]
                (swap! *state assoc :dialog
                       {:type :error
                        :message (str "Error" (apply str location) ":\n" (:cause err))}))
    :progress (swap! *state assoc :dialog
                     {:type :progress
                      :message "Processing…"
                      :progress -1})))

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
  [_]
  (async/close! cancel-ch)
  (fx/unmount-renderer *state renderer))
  ; (System/exit 0))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =

(start-gui)
