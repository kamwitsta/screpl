;; The main library.

;; Functions defined here are made available to the user via [screpl.cli](#screpl.cli).

;; 1. [General utility](#core/general)
;; 2. [Data specs](#core/specs)
;; 3. [Data upload](#core/data)
;; 4. [Single item operations](#core/single)
;; 5. [Batch operations](#core/batch)

; ^:export's don't really do anything in this case, they're just documentation

(ns screpl.core
  (:gen-class)
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as string]
            [fast-edn.core :as edn]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [sci.core :as sci])
  (:import  [java.util.concurrent ForkJoinPool]))


; = general ================================================================================== {{{ =

;; <a id="core/general"></a>
;; ## General utility functions

;; - [attach-to-path](#core/attach-to-path)
;; - [duplicates](#core/duplicates)
;; - [index-coll](#core/index-coll)
;; - [make-reporter](#core/make-reporter)
;; - [map-equal?](#core/map-equal?)
;; - [pmap-daemon](#core/pmap-daemon)

; - attach-to-path ----------------------------------------------------------------------------- {{{ -

;; <a id="core/attach-to-path"></a>

(defn attach-to-path
  "Join a path to the parent of another. Example: ../new.clj + /home/me/source.clj => /home/new.clj"
  [source  ; attach to this path
   new]    ; attach this path
  (-> source
      (java.nio.file.Paths/get (make-array String 0))
      (.getParent)
      (.resolve new)
      (.normalize)
      (str)))

; ---------------------------------------------------------------------------------------------- }}} -
; - duplicates --------------------------------------------------------------------------------- {{{ -

;; <a id="core/duplicates"></a>

(defn duplicates
  "Returns a list of elements that appear at least n times in a collections."
  
  ; by default, n=2
  ([coll] (duplicates coll 2))
  
  ; multiplicates, for n>2
  ([coll n]
   (for [[x f] (->> coll (remove nil?) frequencies)
         :when (>= f n)] x)))

; ---------------------------------------------------------------------------------------------- }}} -
; - index-coll --------------------------------------------------------------------------------- {{{ -

;; <a id="core/index-coll"></a>

(defn index-coll [coll & [key]]
  "Convert a collection of maps to an indexed collection. If `key` is not given, uses `:id`."
  (let [key (or key :id)]
    (persistent! 
      (reduce (fn [acc item] 
                (assoc! acc (get item key) item))
              (transient {})
              coll))))

; ---------------------------------------------------------------------------------------------- }}} -
; - make-reporter ------------------------------------------------------------------------------ {{{ -

;; <a id="core/make-reporter"></a>

(defn make-reporter
  "Generates a function that reports to a channel."
  [output-ch]   ; each core function receives it independently from the gui
  (fn [status & [output]]
    (when output-ch
      (async/>!! output-ch
                 {:status status
                  :output output}))))

; ---------------------------------------------------------------------------------------------- }}} -
; - map-equal? --------------------------------------------------------------------------------- {{{ -

;; <a id="core/map-equal?"></a>

(defn map-equal?
  "Checks whether two hash maps have the same values under selected keys. Warning: if both maps are missing all of the keys, `map-equal?` will return `true`."
  [x   ; compare this map
   y   ; to this map
   k]  ; on these keys
  (= (select-keys x k)
     (select-keys y k)))

; ---------------------------------------------------------------------------------------------- }}} -
; - pmap-daemon -------------------------------------------------------------------------------- {{{ -

;; <a id="core/pmap-daemon"></a>

; On daemon vs non-daemon threads, see https://claude.ai/chat/f96046ad-294e-4a6d-a015-50f687a65be9.

(defn pmap-daemon
  "An alternative to `pmap` which uses daemon threads (managed by JVM)."
  ;; Standard `pmap` uses non-daemon threads which causes a 60-second delay between when the calculations complete, and when the control is given back to the user. Non-daemon threads make sure the program doesn’t quit before the job has been completed, but this is not a threat in the case of SCRepl, and the delay would be highly impractical.
  [f coll]
  (if (empty? coll)
    []
    (let [common-pool (ForkJoinPool/commonPool)
          futures     (mapv #(.submit common-pool ^Callable (fn [] (f %))) coll)]
      (mapv #(.get %) futures))))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = specs ====================================================================================== {{{ =

;; <a id="core/specs"></a>
;; ## Specifications for data provided by the user

;; - [Data: SourceDatum, TargetDatum](#core/source-target-datum)
;; - [Sound changes: SCItem, =>SCFun](#core/scfun)
;; - [Project: ProjectFile, =>GetDataFn](#core/projectfile)

; - source and target data --------------------------------------------------------------------- {{{ -

;; <a id="core/source-target-datum"></a>

(def DataId
  ;; This repeats in Source- and TargetDatum
  [:or int? keyword? string?])

(def SourceDatum
  ;; A single item in the source data.
  [:map
   [:display string?]
   [:id {:optional true} #'DataId]])

(def TargetDatum
  ;; A single item in the target data.
  [:map
   [:display string?]
   [:id #'DataId]])

; ---------------------------------------------------------------------------------------------- }}} -
; - sound change functions --------------------------------------------------------------------- {{{ -

;; <a id="core/scfun"></a>

(def =>SCFun
  ;; A single sound change function, deref’ed.
  (m/schema
    [:=> [:cat #'SourceDatum] [:vector #'SourceDatum]]
    {::m/function-checker mg/function-checker}))

(def SCItem
  ;; A single sound change functions, as loaded from a file (a sci.lang.Var).
  [:and
   [:fn {:error/message "Not a SCI var. Did you forget to prepend `#'`?"} #(instance? sci.lang.Var %)]
   [:fn {:error/message "Function must conform to =>SCFun schema."} #(m/validate =>SCFun (deref %))]])

; -----------------------------==--------------------------------------------------------------- }}} -
; - project file ------------------------------------------------------------------------------- {{{ -

;; <a id="core/projectfile"></a>

(def =>GetDataFn
  ; A function that retrieves data from a database and converts them to regular Clojure hash maps. Takes no arguments and returns data as vectors wrapped in a map: `{:source-data [SourceDatum]}` or `{:source-data [SourceDatum], :target-data [TargetDatum]}`.
  (m/schema
    [:=>
     :cat
     [:map [:source-data [:vector #'SourceDatum]]
           [:target-data {:optional true} [:vector #'TargetDatum]]]]
    {::m/function-checker mg/function-checker}))

(def ProjectFile
  ;; Spec for the file containing paths to other files in the project.
  [:or
   ;; Data can either be loaded from files…
   [:map
    [:sound-changes string?]
    [:source-data string?]
    [:data-target {:optional true} string?]]
   ;; … or from a database.
   [:map
    [:sound-changes string?]
    [:get-data-fn #'=>GetDataFn]]])

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = data upload ================================================================================ {{{ =

;; <a id="core/data"></a>
;; ## Loading users’ code and data

;; The only function that is directly exposed via the UI is [load-project](#core/load-project), which calls all the other functions. The reason for this is this: 1) the `:id` key is only required from source data when target data are also present, and can be omitted otherwise; 2) the ID’s in source data must match the ID’s in target data. Validating the former and ensuring the latter can only be done when all the data are loaded together in one go.
;; Indirectly, the UI also calls [load-fns](#core/load-fns); this is why it's been separated from [load-scs](#core/load-scs).

;; - [load-project](#core/load-project)
;; - [load-projectfile](#core/load-projectfile)
;; - [load-data](#core/load-data)
;; - [load-fns](#core/load-fns)
;; - [load-scs](#core/load-scs)
;; - [check-ids](#core/check-ids)

; - check-ids ---------------------------------------------------------------------------------- {{{ -

;; <a id="core/check-ids"></a>

(defn check-ids
  "Makes sure ids in source and target data are present, unique, and matching."
  [data]     ; hash-map with `:source-data` and `:target-data`

  (let [src-ids (->> data :source-data (map :id))
        trg-ids (->> data :target-data (map :id))]

    ; check unique
    (when-let [dups (seq (duplicates src-ids))]
      (throw (ex-info (str "Duplicate ids: " dups) {:filename "source data"})))
    (when-let [dups (seq (duplicates trg-ids))]
      (throw (ex-info (str "Duplicate ids: " dups) {:filename "target data"})))

    ; check missing ids
    (when-let [miss (->> (map #(when (nil? %1) %2) src-ids (->> data :source-data (map :display)))
                         (remove nil?)
                         (seq))]
      (throw (ex-info (str "Missing ids: " miss) {:filename "source data"})))
    (when-let [miss (->> (map #(when (nil? %1) %2) trg-ids (->> data :target-data (map :display)))
                         (remove nil?)
                         (seq))]
      (throw (ex-info (str "Missing ids: " miss) {:filename "target data"})))

    ; check unmatched ids
    (when-let [unm (->> (set/difference (set src-ids) (set trg-ids))
                        (remove nil?)
                        (seq))]
      (throw (ex-info (str "Unmatched ids: " unm) {:filename "source data"})))
    (when-let [unm (->> (set/difference (set trg-ids) (set src-ids))
                        (remove nil?)
                        (seq))]
      (throw (ex-info (str "Unmatched ids: " unm) {:filename "target data"})))))

; ---------------------------------------------------------------------------------------------- }}} -
; - load-data ---------------------------------------------------------------------------------- {{{ -

;; <a id="core/load-data"></a>

(defn load-data
  "Reads and validates source and target data. Returns a hash map with `:source-data` and `target-data`."
  [project]
  (letfn
    [(loader [key]
      (->> project
           key
           (attach-to-path (:project-file project))
           (slurp)
           (edn/read-string)))

     ; {:source-data [s]} -> [[s] [s] …]
     ; {:source-data [s], :target-data [t]} -> [[s t] [s t] …]
     (pair-maker [{:keys [source-data target-data]}]
        (if target-data
          (let [target-indexed (index-coll target-data)]
            (for [s source-data]
              [s (-> s :id target-indexed)]))
          (map vector source-data)))
     
     (validator [spec idx itm]
       (try
          (m/assert spec itm)
          (catch Exception e
            (let [data (-> e ex-data :data :explain)]
              (throw (ex-info (-> data me/humanize str)
                              {:display (-> data :value :display)
                               :field (-> data :errors first :in first)
                               :index (inc idx)
                               :filename (if (= spec SourceDatum) "source data" "target data")}))))))]

    (let [data (if (:get-data-fn project)
                 ; load from a database
                 ((:get-data-fn project))
                 ; load from files
                 (cond-> {:source-data (loader :source-data)}
                   (:target-data project)
                   (assoc :target-data (loader :target-data))))]
      (doall (map-indexed (partial validator SourceDatum) (:source-data data)))
      (when (:target-data data)
        (doall (map-indexed (partial validator TargetDatum) (:target-data data)))
        (check-ids data))
      (pair-maker data))))

; ---------------------------------------------------------------------------------------------- }}} -
; - load-fns ----------------------------------------------------------------------------------- {{{ -

;; <a id="core/load-fns"></a>

(defn load-fns
  "Evals and validates sound changes. Returns a vector of functions."
  [string]  ; eval this
  (->> string
      (sci/eval-string)
      (map-indexed (fn [idx itm]
                     (try
                       (m/assert SCItem itm)
                       (catch Exception e
                         (let [data (-> e ex-data :data :explain)]
                           (throw (ex-info (-> data me/humanize str)
                                           {:display (-> itm meta :name)
                                            :index   (inc idx)})))))))
      (doall)))   ; because map

; ---------------------------------------------------------------------------------------------- }}} -
; - load-scs ----------------------------------------------------------------------------------- {{{ -

;; <a id="core/load-scs"></a>

(defn load-scs
  "Reads sound changes and sends them for evaluation and validation. Returns a vector of functions."
  [project]     ; get scs from this
  (->> project
      :sound-changes
      (attach-to-path (:project-file project))
      (slurp)
      (load-fns)))

; ---------------------------------------------------------------------------------------------- }}} -
; - load-projectfile --------------------------------------------------------------------------- {{{ -

;; <a id="core/load-projectfile"></a>

(defn load-projectfile
  "Reads, evals, and validates a project file. Returns the hash map returned by the project file + `:project-file` containing the path to the project file."
  [filename]
  (try
    (->> filename
         (slurp)
         (sci/eval-string)
         (m/assert ProjectFile)
         (merge {:project-file filename}))
    ; probably either sci or malli
    (catch clojure.lang.ExceptionInfo e
      (if (= (-> e ex-data :type) :malli.core/coercion)
        ; malli has specific errors
        (let [data (-> e ex-data :data :explain)]
          (throw (ex-info (-> data me/humanize str)
                          {:display (-> data :value :display)
                           :field (-> data :errors first :in first)})))
        ; sci is more sensible
        (throw (ex-info (ex-message e)
                        (merge (ex-data e) {:filename filename})))))
    ; probably java.io.FileNotFoundException
    (catch Throwable e
      (throw (ex-info (ex-message e)
                      (merge (ex-data e) {:filename filename}))))))

; ---------------------------------------------------------------------------------------------- }}} -

; - load-project ------------------------------------------------------------------------------- {{{ -

;; <a id="core/load-project"></a>

(defn ^:export load-project
  "Load an entire project based on a project file."
  [filename]    ; a project file
  (let [project (load-projectfile filename)
        scs     (load-scs project)
        data    (load-data project)]
    {:filename filename
     :data data
     :has-target-data? (-> data first count (= 2))
     :sound-changes scs}))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = single ===================================================================================== {{{ =

;; <a id="core/single"></a>
;; ## Operations on individual items

;; - [grow-tree](#core/grow-tree)
;; - [find-paths](#core/find-paths)
;; - [print-leaves](#core/print-leaves)
;; - [print-tree](#core/print-tree)

; - grow-tree ---------------------------------------------------------------------------------- {{{ -

;; <a id="cor/grow-tree"></a>

(defn ^:export grow-tree
  "Pipelines a value through a series of functions while keeping the intermediate results, in effect growing a tree. Functions that do not change the previous value are skipped. Returns a lazy sequence of hash-map with the keys `:id`, `:label`, `:value`, `:fname` and `:children` where `:value` is the given node, `:fname` is the name of the function that produces `:children` from it, and `:label` is a convenience copy of `-> :value :display`. Note that besides `:id` there might also be a second, unrelated `:id` inside `:value`."
  [functions     ; the pipeline
   value]        ; the value to be pipelined
  (let [id-counter (atom -1)]
   (letfn [(grow-tree-hlp [fns x]
             (swap! id-counter inc)
             (if-let [f (first fns)]
               ; node
               (let [x' (f x)]
                 (if (= [x] x')                     ; see if applying f makes a difference
                   (grow-tree-hlp (next fns) x)     ; skip it if it doesn't
                   (hash-map :id @id-counter
                            :label (:display x)
                            :value x
                            :fname (-> f meta :name)
                            :children (map (partial grow-tree-hlp (next fns)) x'))))
               ; leaf
               {:id @id-counter
                :label (:display x)
                :value x
                :children []}))]
     (grow-tree-hlp functions value))))
      
; ---------------------------------------------------------------------------------------------- }}} -
; - find-paths --------------------------------------------------------------------------------- {{{ -

;; <a id="core/find-paths"></a>

(defn ^:export find-paths
  "Finds paths in a tree, from the root to leaves matching a regex. Outputs results to a channel, and returns a set of nodes."
  [tree            ; search this tree
   re              ; for leaves matching this regex
   intermediate?   ; if false, search only leaves
   cancel-ch       ; listening for cancellation on this channel
   output-ch]      ; and outputting to this channel (can be `nil`)
  (let [report! (make-reporter output-ch)
        path    (atom #{})]    ; only collects ids on path, for printing path in tree
    (letfn [(search-node [curr-path-id curr-path-label node]
              (if (some? (async/poll! cancel-ch))
                (report! :cancelled)
                ; if not cancelled
                (do
                  (report! :progress)
                  ; search children
                  (let [full-path-id    (conj curr-path-id (:id node))
                        full-path-label (conj curr-path-label (:label node))
                        matches?        (re-find re (:label node))]
                    (if (seq (:children node))
                      ; node
                      (do
                        (when (and intermediate? matches?)
                          (swap! path into full-path-id)
                          (report! :partial (conj full-path-label "…")))
                        (mapv (partial search-node full-path-id full-path-label) (:children node)))
                      ; leaf
                      (when matches?
                        (swap! path into full-path-id)
                        (report! :partial full-path-label)))))))]

      (search-node [] [] tree)
      (report! :completed)
      @path)))
      

; ---------------------------------------------------------------------------------------------- }}} -
; - print-leaves ------------------------------------------------------------------------------- {{{ -

;; <a id="core/print-leaves"></a>

;; Not exactly a tree operation. Trees are stored as lazy sequences, so in order to collect the leaves, they have to be first realized. Simply pipelining a value requires the application of all the same sound changes, and saves the overhead of storing the intermediate values.
(defn print-leaves
  "Print the products of pipelining a value through a series of functions."
  [functions  ; the pipeline
   value      ; the value to be pipelined
   cancel-ch  ; listen for cancellation
   output-ch] ; report partial results
  (let [report! (make-reporter output-ch)]
    (letfn [(pipeline [x f]
              (if (some? (async/poll! cancel-ch))
                (report! :cancelled)
                (do
                  (report! :progress)
                  (mapcat f x))))]
      (report! :completed (set (reduce pipeline value functions))))))

; ---------------------------------------------------------------------------------------------- }}} -
; - print-tree --------------------------------------------------------------------------------- {{{ -

;; <a id="core/print-tree"></a>

(defn print-tree
  "Converts a tree to HTML, putting partial results on a channel. Returns the count of leaves and nodes."
  [tree       ; print this tree
   path       ; while highlighting the path to the points in this set
   cancel-ch  ; listening for cancellation on this channel
   output-ch] ; and outputting to this channel
  (let [report! (make-reporter output-ch)
        counts  (atom {:nodes 0, :leaves 0})]
    (letfn [(print-node [node class]
              (if (some? (async/poll! cancel-ch))
                (async/>!! output-ch {:status :cancelled})
                (do
                  ; print the node (1/2)
                  (report! :partial (cond-> "<li"
                                     class         (str " class=\"" class "\"")
                                     true          (str ">"(:label node))
                                     (:fname node) (str " <span class=\"fname\">" (:fname node) "</span>")))
                  ; go through the children
                  (if (seq (:children node))
                    ; if node
                    (do
                      (swap! counts update :nodes inc)
                      (report! :partial "<ul>")
                      (loop [children (:children node)]
                        (let [curr-child           (first children)
                              curr-child-on-path   (-> curr-child :id path)
                              other-childr         (rest children)
                              other-childr-on-path (->> other-childr (map :id) (some path))
                              curr-class           (cond-> nil
                                                     curr-child-on-path (str "on-path")
                                                     other-childr-on-path (str " path-passes"))
                              curr-node            (print-node curr-child curr-class)])
                        (when (seq (rest children))
                          (recur (rest children))))
                      (report! :partial "</ul>"))
                    ; if leaf
                    (swap! counts update :leaves inc))
                  ; print the node (2/2)
                  (report! :partial "</li>")
                  ; report progress
                  (report! :progress))))]

      (report! :partial "<ul class=\"tree\">")
      (print-node tree (when (path (:id tree)) "on-path"))
      (report! :partial "</ul>")
      (report! :completed @counts))))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = batch ====================================================================================== {{{ =

;; <a id="core/batch"></a>
;; ## Operations on multiple items

;; - [find-irregulars](#core/find-irregulars)

; - find-irregulars ---------------------------------------------------------------------------- {{{ -

;; <a id="core/find-irregulars"></a>

(defn ^:export find-irregulars
  "Batch pipelines values through a list of functions, and reports those items that do not produce the expected result. `data` is a vector of vectors, each containing a source map and a target map."
  [functions
   data
   cancel-ch
   output-ch]
  (let [report! (make-reporter output-ch)]
    (letfn [(check-item [item]
              (if (some? (async/poll! cancel-ch))
                (report! :cancelled)
                (let [leaves   (reduce (fn [x f] (mapcat f x))    ; do the pipeline
                                       [(first item)]
                                       functions)
                      result   (set (map :display leaves))
                      expected (-> item second :display)]
                  (report! :progress)
                  (when (not-any? result [expected])
                    (report! :partial {:item item, :result result})))))]
      (dorun (pmap check-item data))
      (report! :completed))))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
