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
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as string]
            [fast-edn.core :as edn]
            [hiccup2.core :as hiccup]
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
;; ## Build and print trees

;; - [grow-tree](#core/grow-tree)
;; - [print-tree](#core/print-tree)

; - grow-tree ---------------------------------------------------------------------------------- {{{ -

;; <a id="core/grow-tree"></a>

(defn ^:export grow-tree
  "Pipelines a value through a series of functions while keeping the intermediate results, in effect growing a tree. Functions that do not change the previous value are skipped. Returns a hash-map with the keys `:id`, `:label`, `:value`, `:fname` and `:children` where `:value` is the given node, `:fname` is the name of the function that produces `:children` from it, and `:label` is a convenience copy of `-> :value :display`. Note that besides `:id` there might also be a second, unrelated `:id` inside `:value`."
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
; - print-tree --------------------------------------------------------------------------------- {{{ -

;; <a id="core/print-tree"></a>

(defn print-tree
  "Prints a tree while counting the nodes and leaves, and reporting progress."
  [tree       ; print this tree
   path       ; while highlighting the path to the points in this set
   cancel-ch  ; listening for cancellation on this channel
   output-ch] ; and outputting to this channel
  (let [counts (atom {:nodes 0, :leaves 0})]
    (letfn [(print-node [node class]
              ; check for cancellation
              (if (some? (async/poll! cancel-ch))
                (async/>!! output-ch {:status :cancelled})
                (do
                  ; report progress
                  (async/>!! output-ch {:status :in-progress})
                  ; count the node/leaf
                  (if (seq (:children node))
                    (swap! counts update :nodes inc)
                    (swap! counts update :leaves inc))
                  ; generate the node
                  [:li
                   (when class {:class class})
                   (:label node)
                   (when (:fname node) [:span {:class "fname"} " " (:fname node)])
                   ; loop through the children
                   ; `loop` does side effects but it won't collect the results, hence `acc`
                   ; must have `loop` because classes depend on successive siblings
                   (loop [children (:children node)
                          acc      []]
                     (if (seq children)
                       (let [curr-child           (first children)
                             curr-child-on-path   (->> curr-child :id path)
                             other-childr         (rest children)
                             other-childr-on-path (->> other-childr (map :id) (some path))
                             curr-class           (cond-> nil
                                                    curr-child-on-path   (str "on-path")
                                                    other-childr-on-path (str " path-passes"))
                             curr-node            (print-node curr-child curr-class)]
                         (recur other-childr (conj acc curr-node)))
                       ; return the result of the loop as [:ul [:li 1] [:li 2]]
                       (into [:ul] acc)))])))]
      (let [root-class (when (path (:id tree)) "on-path")
            result     [:ul {:class "tree"} (print-node tree root-class)]]
        (async/>!! output-ch
                   {:status :completed
                    :result (-> result hiccup/html str)
                    :counts @counts})))))

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

(defn ^:export find-paths [] 1)
