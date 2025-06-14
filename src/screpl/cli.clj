;; A REPL-based user interface.

;; Exposes to the user functions from [screpl.core](#screpl.core).

;; 1. [Global variables](#cli/globals)
;; 2. [CLI-related functions](#cli/cli)
;; 3. [Wrappers for core](#cli/wrappers)

(ns screpl.cli
  (:require [clojure.math :as math]
            [sci.core :as sci]
            [screpl.core :as core]
            [toucan2.core :as db]))


; = globals ==================================================================================== {{{ =

;; <a id="cli/globals"></a>
;; ## Global variables

;; Some functions need to know about [cli/*sci-ctx*](#cli/*sci-ctx*), but the variable itself can only be initiated with references to other functions. Declaring [cli/*sci-ctx*](#cli/*sci-ctx*) first and defining functions later does work, the reverse doesn’t.
(declare ^:dynamic *sci-ctx*)

(def ^:dynamic *project*
  "Global variable, holds the currently loaded project."
  (atom nil))

; ============================================================================================== }}} =
; = cli ======================================================================================== {{{ =

;; <a id="cli/cli"></a>
;; ## Functions to do with the CLI

;; - [message](#cli/message)
;; - [eval-sci-string](#cli/eval-sci-string)
;; - [get-from-](#cli/get-from-)
;; - [progressbar](#cli/progressbar)
;; - [project-info](#cli/project-info)
;; - [reload-project](#cli/reload-project)
;; - [start-repl](#cli/start-repl)

; - message ------------------------------------------------------------------------------------ {{{ -

;; <a id="cli/message"></a>

(defn message
  "Displays a custom message."
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
             (println (str "[ ✗ ] Error" (apply str bits) ":\n      " (err :cause) "\n")))
    :info  (println (str "[ 🛈 ] " (apply str messages)))
    :ok    (println (str "[ ✓ ] " (apply str messages)))
    :quest (let [options (second messages)]
             (loop []
               (println (str (first messages) " (" (apply str (interpose "/" options)) ") "))
               (let [input (read-line)]
                 (if (contains? (set options) input)
                   input
                   (recur)))))))
    ; :warn  (println (str "[ ⚠ ] " (apply str messages)))))

; ---------------------------------------------------------------------------------------------- }}} -
; - eval-sci-string ---------------------------------------------------------------------------- {{{ -

;; <a id="cli/eval-sci-string"></a>

; needs to be separated from start-repl so that ../../dev/dev.clj can access it

(defn eval-sci-string
  "Evaluate a string within *sci-ctx*."
  [input]
  (try (let [result (sci/eval-string* *sci-ctx* input)]
         (if (nil? result)
           (println)
           (println result "\n")))
       (catch Exception e (message :error e))))

; ---------------------------------------------------------------------------------------------- }}} -
; - get-from-source, -target ------------------------------------------------------------------- {{{ -

;; <a id="cli/get-from-"></a>

(defn get-from-
  "Helper for `get-from-source` and `get-from-target`."
  [where    ; :source-data or :target-data
   key      ; look under this key
   value]   ; for this value
  ; make sure project loaded
  (if (nil? @*project*)
    (message :error (ex-info "No project has been loaded." {}))
    ; make sure project has targe data
    (if (and (= where :target-data)
             (nil? (:target-data @*project*)))
      (message :error (ex-info "The current project does not have target data." {}))
      ; everything fine
      (let [result  (filter #(= (% key) value) (@*project* where))]
        (condp = (count result)
          0  (message :error (ex-info "None found." {}))
          1  (first result)
          result)))))

(defn get-from-source
  "Selects an item from source data by `:id` or some other key."
  ([value]
   (get-from- :source-data :id value))

  ([key value]
   (get-from- :source-data key value)))

(defn get-from-target
  "Selects an item from target data by `:id` or some other key."
  ([value]
   (get-from- :target-data :id value))

  ([key value]
   (get-from- :target-data key value)))
 
; ---------------------------------------------------------------------------------------------- }}} -
; - progressbar -------------------------------------------------------------------------------- {{{ -

;; <a id="cli/progressbar"></a>

(defn progressbar
  "Displays the progress of an operation either as a percentage or as a specified unit."
  ([max]     ; display as a percentage of that
   (let [counter (atom 0)]
     (fn []
       (swap! counter inc)
       (let [percent (-> @counter (/ max) (* 100) (math/round))]
         (print (str "\rProcessing… " percent "%."))
         (flush)))))

  ([unit     ; displays this after the number
    step]    ; at every `step`
   (let [counter (atom 0)]
     (fn []
       (swap! counter inc)
       (when (= 0 (mod @counter step))
         (print (str "\rProcessing… " (format "%,d" @counter) " " unit "."))
         (flush))))))

; ---------------------------------------------------------------------------------------------- }}} -
; - project-info ------------------------------------------------------------------------------- {{{ -

;; <a id="cli/project-info"></a>

(defn project-info
  "Display basic information about the currently loaded project."
  []
  (let [fname (:project-file @*project*)
        scs   (count (:sound-changes @*project*))
        src   (count (:source-data @*project*))
        trg   (count (:target-data @*project*))]
    (message :info
             "Project " fname "\n      "
             "Contains " scs " sound change functions, " src " source data items, and "
             (if (= 0 trg)
               "no target data."
               (str trg " target data items.")))))
 
; ---------------------------------------------------------------------------------------------- }}} -
; - reload-project ----------------------------------------------------------------------------- {{{ -

;; <a id="cli/reload-project"></a>

(declare load-project)  ; I prefer to have `load-project` later in the file

(defn reload-project
  "Reload the currently loaded project, presumably because the files have changed."
  []
  (load-project (:project-file @*project*)))

; ---------------------------------------------------------------------------------------------- }}} -
; - start-repl --------------------------------------------------------------------------------- {{{ -

;; <a id="cli/start-repl"></a>

(defn start-repl
  "Run a REPL as the single point of entry in the interface."
  []
  (println "\nSCRepl 0.2.0")
  (println "Type :quit or press ctrl-d to exit.\n")

  (loop []
    (print "=> ")
    (flush)
    (let [input (read-line)]
      (when (and input                    ; without this ctrl-d doesn't work
                 (not= input ":quit"))
        (eval-sci-string input)           ; see the comment at eval-sci-string
        (recur)))))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = wrappers =================================================================================== {{{ =

;; <a id="cli/wrappers"></a>
;; ## Wrappers around screpl.core functions

;; Wrappers provide a link between [screpl.core](#screpl.core) functions and the REPL environment stored in [*sci-ctx*](#cli/*sci-ctx*) (see e.g. [cli/load-project](#cli/load-project)). They also provide additional variants with different arities, and perform a degree of error management, allowing [screpl.core](#screpl.core) to focus on the happy path.

;; - [count-tree](#cli/count-tree)
;; - [find-irregulars](#cli/find-irregulars)
;; - [find-paths](#cli/find-paths)
;; - [grow-tree](#cli/grow-tree)
;; - [load-project](#cli/load-project)
;; - [print-tree](#cli/print-tree)
;; - [produces-target?](#cli/produces-target?)

; - count-tree --------------------------------------------------------------------------------- {{{ -

;; <a id="cli/count-tree"></a>

(defn count-tree
  "Wrapper around `core/count-tree`."
  [tree]
  ; make sure object is a tree
  (if (not= (type tree) screpl.core.Tree)
    (message :error (ex-info "`tree` must be a Tree, as produced by `grow-tree`." {}))
    ; do the counting
    (let [result (core/count-tree tree progressbar)]
      (print "\r")      ; core/count-tree has a progress bar
      (message :info
               "A tree from \"" (-> ((:tree-fn tree)) first :display) "\" with "
               (format "%,d" (:nodes result)) " nodes and "
               (format "%,d" (:leaves result)) " leaves."))))
             

; ---------------------------------------------------------------------------------------------- }}} -
; - find-irregulars ---------------------------------------------------------------------------- {{{ -

;; <a id="cli/find-irregulars"></a>

(defn find-irregulars
  "Wrapper around `core/find-irregulars`. If `keys` are not given, `:display` is used."
  ([]
   (find-irregulars [:display]))
  
  ([keys]
   (if @*project*
     (let [result (core/find-irregulars (->> @*project* :sound-changes (filter :active) (map :fn))
                                      (->> @*project* :source-data)
                                      (->> @*project* :target-data)
                                      keys
                                      progressbar)]
       (print "\r")        ; core/find-irregulars has a progress bar
       result)
     (message :error (ex-info "No project has been loaded." {})))))

; ---------------------------------------------------------------------------------------------- }}} -
; - find-paths --------------------------------------------------------------------------------- {{{ -

;; <a id="cli/find-paths"></a>

(defn find-paths
  "Wrapper around `core/find-paths`."
  [tree          ; find in this tree
   re]           ; paths to leaves that match this regexp
  ; check args
  (if (not= (type tree) screpl.core.Tree)
    (message :error (ex-info "`tree` must be a Tree, as produced by `grow-tree`." {}))
    (if (not= (type re) java.util.regex.Pattern)
      (message :error (ex-info "`re` must be a regular expression." {}))
      ; do the finding
      (let [result (core/find-paths tree re progressbar)
            plural (condp >= (count result)
                     0  "no paths"
                     1  "1 path"
                     (str (count result) " paths"))]
        (print "\r")        ; core/find-paths has a progress bar
        (message :info "Found " plural " from \"" (-> ((:tree-fn tree)) first :display) "\" to \"" re "\".")
        result))))

; ---------------------------------------------------------------------------------------------- }}} -
; - grow-tree ---------------------------------------------------------------------------------- {{{ -

;; <a id="cli/grow-tree"></a>

(defn grow-tree
  "Wrapper around `core/grow-tree`. The arity 1 version uses all active functions in *project*, the arity 2 version only the specified ones. Returns a function that grows a tree. See the comments under `core/grow-tree`."

  ; arity 1
  ([x]
   (if x
     (if @*project*
       (grow-tree (->> @*project* :sound-changes (filter :active) (map :fn)) x)
       (message :error (ex-info "No project has been loaded." {})))
     (message :error (ex-info "No argument given to `grow-tree`." {}))))

  ; arity 2  
  ([functions
    x]
   (core/grow-tree functions x)))

; ---------------------------------------------------------------------------------------------- }}} -
; - load-project ------------------------------------------------------------------------------- {{{ -

;; <a id="cli/load-project"></a>

(defn load-project
  "Wrapper around `core/load-project`."
  [filename]    ; the file to process
  (let [result (core/load-project *sci-ctx* filename)]
    (reset! *project* (update result :sound-changes
                             (fn [x] (map #(hash-map :active true :fn %) x))))
    (message :ok "Loaded project " filename ".")
    (project-info)))

; ---------------------------------------------------------------------------------------------- }}} -
; - print-tree---------------------------------------------------------------------------------- {{{ -

;; <a id="cli/print-tree"></a>

(defn print-tree
  "Wrapper around `core/print-tree`. Arity 1 prints to stdout, arity 2 to a file."

  ; arity 1
  ([x]
   (print-tree x nil))     ; redirect to arity 2

  ; arity 2
  ([x                      ; print this tree
    filename]              ; to this file – or stdout if redirected from arity 2
   ; make sure x is a tree
   (if (not= (type x) screpl.core.Tree)
     (message :error (ex-info "`tree` must be a Tree, as produced by `grow-tree`." {}))
     ; display basic info about the tree (print-tree is also the print method for core/Tree)
     (message :info
              "A tree from \"" (-> ((:tree-fn x)) first :display) "\" through "
              (:fn-count x) " sound changes."))
   ; if small, just print it; otherwise ask
   (when (or (< (:fn-count x) 10)
             (= "y" (message :quest "Do you want to print the entire tree?" ["y" "n"])))
     (if filename
       (core/print-tree x #(spit filename % :append true) false)
       (core/print-tree x print true)))))


; When the user runs `grow-tree` without assigning it to a variable, the whole tree would get printed out. It can get very large, so this ensures the user is asked first.
(defmethod print-method screpl.core.Tree
  [tree ^java.io.Writer _]
  (print-tree tree))

; ---------------------------------------------------------------------------------------------- }}} -
; - produces-target? --------------------------------------------------------------------------- {{{ -

;; <a id="cli/produces-target?"></a>

(defn produces-target?
  "Applies a series of `functions` to the `source` hash map and checks whether any of the results is equal to `target` on selected `keys`. If `functions` are not given, all the sound changes in `*project*` are used. If `target` is not given, it is taken from target data based on `:id`. If `keys` are not given, `:display` is used."
  ([source]       ; pipeline this
   (if @*project*
     (produces-target? (->> @*project* :sound-changes (filter :active) (map :fn))
                       source
                       (get-from-target (:id source))
                       [:display])
     (message :error (ex-info "No project has been loaded." {}))))

  ([source       ; pipeline this
    keys]        ; on these keys
   (if @*project*
     (produces-target? (->> @*project* :sound-changes (filter :active) (map :fn))
                       source
                       (get-from-target (:id source))
                       keys)
     (message :error (ex-info "No project has been loaded." {}))))

  ([source       ; pipeline this
    target       ; and check if any equal to this
    keys]        ; on these keys
   (if @*project*
     (produces-target? (->> @*project* :sound-changes (filter :active) (map :fn))
                       source
                       target
                       keys)
     (message :error (ex-info "No project has been loaded." {}))))

  ([functions    ; through these functions
    source       ; pipeline this
    target       ; and check if any equal to this
    keys]        ; on these keys
   (core/produces-target? functions
                          source
                          target
                          keys
                          progressbar)))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =

;; <a id="cli/*sci-ctx*"></a>

(def ^:dynamic *sci-ctx*
  "Global variable, holds the context for SCI. Must be defined after all the functions it exposes."
  (sci/init {:namespaces {'clojure.set (sci/copy-ns clojure.set (sci/create-ns 'clojure.set))
                          'toucan2.core (sci/copy-ns toucan2.core (sci/create-ns 'toucan2.core))
                          'user {; project-related
                                 'load-project     load-project
                                 'project-info     project-info
                                 'reload-project   reload-project

                                 ; data extraction
                                 'get-from-source  get-from-source
                                 'get-from-target  get-from-target

                                 ; tree operations
                                 'count-tree       count-tree
                                 'find-paths       find-paths
                                 'grow-tree        grow-tree
                                 'print-tree       print-tree
                                 
                                 ; source : target
                                 'find-irregulars  find-irregulars
                                 'produces-target? produces-target?}}}))
