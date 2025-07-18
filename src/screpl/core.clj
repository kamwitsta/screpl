;; The main library.

;; Functions defined here are made available to the user via [screpl.cli](#screpl.cli).

;; 1. [General utility](#core/general)
;; 2. [Data specs](#core/specs)
;; 3. [Data upload](#core/data)
;; 4. [Tree operations](#core/tree)
;; 5. [Source : target](#core/target)

; ^:export's don't really do anything in this case, they're just documentation

(ns screpl.core
  (:gen-class)
  (:require [clansi :as clansi]
            [clojure.core.async :as async]
            [clojure.set :as set]
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

;; The only function that is exposed via the UI is [load-project](#core/load-project), which calls all the other functions. The reason for this is this: 1) the `:id` key is only required from source data when target data are also present, and can be omitted otherwise; 2) the ID’s in source data must match the ID’s in target data. Validating the former and ensuring the latter can only be done when all the data are loaded together in one go.

;; - [load-project](#core/load-project)
;; - [load-projectfile](#core/load-projectfile)
;; - [load-data](#core/load-data)
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
  (letfn [(validator [spec idx itm]
            (try
              (m/assert spec itm)
              (catch Exception e
                (let [data (-> e ex-data :data :explain)]
                  (throw (ex-info (-> data me/humanize str)
                                  {:display (-> data :value :display)
                                   :field (-> data :errors first :in first)
                                   :index (inc idx)
                                   :filename (if (= spec SourceDatum)
                                               "source data"
                                               "target data")}))))))
          (loader [key]
            (->> project
                 key
                 (attach-to-path (:project-file project))
                 (slurp)
                 (edn/read-string)))]
    (let [data (if (:get-data-fn project)
                 ; load from a database
                 ((:get-data-fn project))
                 ; load from files
                 (let [res {:source-data (loader :source-data)}] 
                   (if (:target-data project)
                     (merge res {:target-data (loader :target-data)})
                     res)))]
      (doall (map-indexed (partial validator SourceDatum) (:source-data data)))
      (when (:target-data data)
        (doall (map-indexed (partial validator TargetDatum) (:target-data data)))
        (check-ids data))
      data)))

; ---------------------------------------------------------------------------------------------- }}} -
; - load-scs ----------------------------------------------------------------------------------- {{{ -

;; <a id="core/load-scs"></a>

(defn load-scs
  "Reads, evals, and validates sound changes. Returns a vector of functions."
  [project]     ; get scs from this
  (->> project
      :sound-changes
      (attach-to-path (:project-file project))
      (slurp)
      (sci/eval-string)
      (map-indexed (fn [idx itm]
                     (try
                       (m/assert SCItem itm)
                       (catch Exception e
                         (let [data (-> e ex-data :data :explain)]
                           (throw (ex-info (-> data me/humanize str)
                                           {:display (-> itm meta :name)
                                            :index   (inc idx)})))))))
      (doall)))

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
    (merge data {:project-file filename     ; data might be both source and target or just source
                 :sound-changes scs})))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = tree operations ============================================================================ {{{ =

;; <a id="core/tree"></a>
;; ## Extract information from core/Tree’s

;; - [Tree](#core/tree)
;; - [children](#core/children)
;; - [node?](#core/node?)
;; - [count-tree](#core/count-tree)
;; - [find-paths](#core/find-paths)
;; - [grow-tree](#core/grow-tree)
;; - [print-tree](#core/print-tree)

; - tree --------------------------------------------------------------------------------------- {{{ -

;; <a id="core/tree"></a>

;; Tree as produced by `grow-tree`.
(defrecord ^:export Tree [tree-fn fn-count])
;; - `tree-fn` is a tree-building function; see the comment under [grow-tree](#core/grow-tree);
;; - `fn-count` is the number of functions used in the building of the tree; a substite for the number of nodes, as those can take very long to count

;; An explicit type is needed in order to define a custom print-method in [screpl.cli](#screpl.cli).

; ---------------------------------------------------------------------------------------------- }}} -
; - children ----------------------------------------------------------------------------------- {{{ -

;; <a id="core/children"></a>

(defn children
  "Extracts children from a node of a `screpl.core/Tree`."
  [x]
  (drop 2 x))

; ---------------------------------------------------------------------------------------------- }}} -
; - node? -------------------------------------------------------------------------------------- {{{ -

;; <a id="core/node?"></a>

(defn node?
  "Checks if `x` is a node in a `screpl.core/Tree`."
  [x]
  (< 1 (count x)))

; ---------------------------------------------------------------------------------------------- }}} -

; - count-tree --------------------------------------------------------------------------------- {{{ -

;; <a id="core/count-tree"></a>

(defn ^:export count-tree
  "Counts nodes and leaves in a `screpl.core/Tree`; see the comments there."
  [^Tree tree    ; count this tree
   progressbar]  ; display progress through this fn
  (let [progress! (progressbar "leaves" 10000)
        tree'     ((:tree-fn tree))]
    (reduce (fn [acc x]
              (if (node? x)
                (update acc :nodes inc)
                (do
                  (progress!)
                  (update acc :leaves inc))))
            {:nodes 0, :leaves 0}
            (tree-seq node? children tree'))))

; ---------------------------------------------------------------------------------------------- }}} -
; - find-paths --------------------------------------------------------------------------------- {{{ -

;; <a id="core/find-paths"></a>

(defn ^:export find-paths
  "Finds paths, in a `screpl.core/Tree`, from the root to leaves matching a regular expression."
  [^Tree tree    ; find in this tree
   re            ; paths to leaves that match this regexp
   progressbar]  ; display progress through this fn
  (let [progress! (progressbar "leaves" 10000)
        tree'     ((:tree-fn tree))]
    (letfn [(find-paths-hlp [elem path]
              (let [value (-> elem first :display)]
                (lazy-seq
                  (if (node? elem)
                    (mapcat #(find-paths-hlp % (conj path value))
                           (children elem))
                    (do
                      (progress!)
                      (when (re-find re value)
                        [(conj path value)]))))))]
      (find-paths-hlp tree' []))))

; ---------------------------------------------------------------------------------------------- }}} -
; - grow-tree ---------------------------------------------------------------------------------- {{{ -

;; <a id="core/grow-tree"></a>

; For more on why the tree is stored as a function rather than a simple lazy sequence, see https://app.slack.com/client/T03RZGPFR/C053AK3F9, and https://claude.ai/chat/5e800009-c430-4656-90d8-82ed6ad7f205.

(defn ^:export grow-tree
  "Pipelines a value through a series of functions while keeping the intermediate results, in effect growing a tree. Functions that do not change the previous value are skipped. Returns a record of type `screpl.core.Tree` containing: under `:tree-fn` a function that builds a lazy sequence, and under `:size` counts as returned by `count-tree`. Nodes have the format `[node-value next-fn-name [child1] [child2] …]`, leaves `[leaf-value]`."

  ;; The tree can, and quite easily at that, grow larger than the available memory. Constructing it as a lazy sequence can prevent this but the condition is that the sequence can never be realized in full. `tree-seq`, `reduce`, etc. can do that because they do not keep the reference to the head of the sequence during their operation. However, when a lazy sequence is assigned to a global variable, that variable keeps the reference to the head, so even when only lazy-friendly functions are used, the program still ends up realizing the entire tree – and crashing with an `OutOfMemoryError`. One solution is to keep the tree as a function, and only asssign it to a local variable inside a processing function such as [count-tree](#core/count-tree), as this is a situation that the garbage collector can deal with.

  ;; This means that the tree is generated anew every time it has its nodes counted, leaves picked, etc. There is no harm to the performance, however, because the tree can never be realized in full at any one time anyway since it does not fit in the memory.

  ;; After due deliberation, I decided that the user of SCRepl does not need to know all of that. The whole function-not-sequence-shenanigan is going to handled internally, and to the user a simpler interface will be presented allowing them to say `(def a grow-tree fns val) (count-tree a)` instead of `(def a #(grow-tree fns val) (count-tree (a))`.

  [functions     ; the pipeline
   value]        ; the value to be pipelined
  (letfn [(grow-tree-fn [fns x]
            (if-let [f (first fns)]
              ; if node (more functions in the pipeline)
              (let [x' (f x)]
                (if (= [x] x')        ; see if applying f makes a difference
                  (grow-tree-fn (next fns) x)     ; skip it if it doesn't
                  (concat [x (-> f meta :name)]
                          (map (partial grow-tree-fn (next fns)) x'))))
              ; if leaf
              [x]))]
    (let [tree     (fn [] (grow-tree-fn functions value))
          fn-count (count functions)]
      (->Tree tree fn-count))))
      

; ---------------------------------------------------------------------------------------------- }}} -
; - print-tree --------------------------------------------------------------------------------- {{{ -

;; <a id="core/print-tree"></a>

(defn ^:export print-tree
  "Pretty prints a `screpl.core/Tree`."
  [^Tree tree       ; print this tree
   cancel-ch        ; listen for cancellation here
   output-ch]       ; put output on this channel
  (let [tree'      ((:tree-fn tree))
        root       (first tree')
        fname      (second tree')
        children   (drop 2 tree')]
    (letfn [(print-node [[value fname & children] prefix last?]
              (if (some? (async/poll! cancel-ch))
                (async/>!! output-ch {:type :cancelled})
                (let [connector     (if last? "└─ " "├─ ")
                      prefix'       (str prefix (if last? "   " "│  "))   ; accumulates the indent level
                      head-children (butlast children)
                      last-child    (last children)]      ; special case: different connector and prefix
                  ; print the current node/leaf
                  (async/>!! output-ch
                             {:type :working
                              :output (str prefix connector (:display value) " " fname "\n")})
                  ; repeat for all but last children
                  (doseq [child head-children]
                    (print-node child prefix' false))
                  ; repeat for the last child (needs a different connector)
                  (when last-child
                      (print-node last-child prefix' true)))))]
      (when (seq tree')
        ; print the root withouth any connector
        (async/>!! output-ch
                   {:type :working
                    :output (str (:display root) " " fname "\n")})
        ; print the immediate children except the last
        (doseq [child (butlast children)]
          (print-node child "" false))
        ; the last child has a different connector
        (print-node (last children) "" true)
        ; report completion
        (async/>!! output-ch {:type :finished})))))

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
; = source : target ============================================================================ {{{ =

;; <a id="core/target"></a>
;; ## Relations between source and target data

;; Functions that do not care about the nodes, only about the leaves. It is faster to generate just the leaves than to extract them from a [core/Tree](#core/tree).

;; - [find-irregulars](#core/find-irregulars)
;; - [print-products](#core/print-products)
;; - [produces-target?](#core/produces-target?)

; - print-products ----------------------------------------------------------------------------- {{{ -

;; <a id="core/print-products"></a>

(defn print-products
  "Applies a series of `functions` to the `source` and prints the results. `source` can be a single hash map or a vector."
  [functions    ; apply these functions (must be a vector)
   source       ; to this map / these maps
   output-fn]   ; and print using this function
  (letfn [(print-one [src]
            (let [products (reduce (fn [x f] (mapcat f x))
                                   [src]
                                   functions)]
              (output-fn (str (:display src) " → "))
              (loop [prod products]
                (output-fn (-> prod first :display))
                (when (next prod)
                  (output-fn ", ")
                  (recur (next prod))))
              (output-fn "\n")))]
    (mapv print-one (if (map? source)
                      (vector source)
                      source))))

; ---------------------------------------------------------------------------------------------- }}} -
; - produces-target? --------------------------------------------------------------------------- {{{ -

;; <a id="core/produces-target?"></a>

(defn ^:export produces-target?
  "Applies a series of `functions` to the `source` hash map and checks whether any of the results is equal to `target` on selected `keys`."
  [functions    ; through these functions
   source       ; pipeline this
   target       ; and check if any equal to this
   keys         ; on these keys
   progressbar] ; while using this to show progress
  (let [progress! (when progressbar (progressbar "leaves" 1000))
        ; generate final results (lazy)
        leaves    (reduce (fn [x f] (mapcat f x))
                          [source]
                          functions)
        ; check if any is equal to target
        result    (loop [leaf leaves]
                    (if (empty? leaf)
                      false
                      (do
                        (when progressbar (progress!))
                        (if (map-equal? (first leaf) target keys)
                          true
                          (recur (next leaf))))))]
    (true? result)))

; ---------------------------------------------------------------------------------------------- }}} -
; - find-irregulars ---------------------------------------------------------------------------- {{{ -

;; <a id="core/find-irregulars"></a>

(defn ^:export find-irregulars
  "Find items in `source` that do not produce their corresponding items in `target`."
  [functions     ; apply these functions
   source        ; to these data
   target        ; and compare with these data
   keys          ; on these keys
   progressbar]  ; while using this to show the progress
  (let [progress! (progressbar (count source))
        ; find item in target that has the same :id as the current source item
        ; (source and target aren’t necessarily in the same order)
        find-trg  (fn [src]
                    (loop [trg target]
                      (if (= (:id src) (:id (first trg)))
                        (first trg)
                        (recur (next trg)))))]
    (->> source
         (pmap-daemon (fn [src]
                        (progress!)
                        (when-not (produces-target? functions src (find-trg src) keys nil)
                          src)))
         (keep identity))))     ; (filter some?) could realize the entire sequence

; ---------------------------------------------------------------------------------------------- }}} -

; ============================================================================================== }}} =
