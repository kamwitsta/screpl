(ns screpl.core
  (:gen-class)
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.string :as string]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [sci.core :as sci]
            [toucan2.core :as db])
  (:import  [java.util.concurrent ForkJoinPool]))


; = general ================================================================================== {{{ =

; - attach-to-path ----------------------------------------------------------------------------- {{{ -

(defn attach-to-path
  "Join a path to the parent of another. Example: ../new.clj + /home/me/source.clj => /home/new.clj"
  [new      ; attach this path
   source]  ; attach to this path
  (-> source
      (java.nio.file.Paths/get (make-array String 0))
      (.getParent)
      (.resolve new)
      (.normalize)
      (str)))

; ---------------------------------------------------------------------------------------------- }}} -
; - duplicates --------------------------------------------------------------------------------- {{{ -

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

(defn index-coll [coll & [key]]
  "Convert a collection of maps to an indexed collection. If `key` is not given, uses `:link`."
  (let [key (or key :link)]
    (persistent! 
      (reduce (fn [acc item] 
                (assoc! acc (get item key) item))
              (transient {})
              coll))))

; ---------------------------------------------------------------------------------------------- }}} -
; - make-reporter ------------------------------------------------------------------------------ {{{ -

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

(defn map-equal?
  "Checks whether two hash maps have the same values under selected keys. Warning: if both maps are missing all of the keys, `map-equal?` will return `true`."
  [x   ; compare this map
   y   ; to this map
   k]  ; on these keys
  (= (select-keys x k)
     (select-keys y k)))

; ---------------------------------------------------------------------------------------------- }}} -
; - pmap-daemon -------------------------------------------------------------------------------- {{{ -

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
; = specs ==================================================================================== {{{ =

; - source and target data ------------------------------------------------------------------- {{{ -

(def Datum
  ; A single item in the source data.
  [:map
   [:display string?]
   [:link {:optional true} [:or int? keyword? string?]]])

; -------------------------------------------------------------------------------------------- }}} -
; - sound change functions ------------------------------------------------------------------- {{{ -

(def =>SCFun
  ; A single sound change function, deref’ed.
  (m/schema
    [:=> [:cat #'Datum] [:vector #'Datum]]
    {::m/function-checker mg/function-checker}))

(def SCItem
  ; A single sound change functions, as loaded from a file (a sci.lang.Var).
  [:and
   [:fn {:error/message "Not a SCI var. Did you forget to prepend `#'`?"} #(instance? sci.lang.Var %)]
   [:fn {:error/message "Function must conform to =>SCFun schema."} #(m/validate =>SCFun (deref %))]])

; -----------------------------==------------------------------------------------------------- }}} -
; - project file ----------------------------------------------------------------------------- {{{ -

(def ProjectFile
  ; Spec for the file containing pointers to the components of a project.
  [:map
   [:sound-changes string?]
   [:source-data [:or string? [:vector #'Datum]]]
   [:target-data {:optional true} [:or string? [:vector #'Datum]]]])

; -------------------------------------------------------------------------------------------- }}} -

; ============================================================================================ }}} =
; = data upload ============================================================================== {{{ =

; - sci-loader ------------------------------------------------------------------------------- {{{ - 

(defn- sci-loader
  "Evaluates a string in SCI. Returns either the result of the evaluation or a hash-map with `:error`."

  ([string key]
   (sci-loader string key nil))

  ([string key opts]
   (try
     (sci/eval-string string opts)
     (catch Throwable e
       {:error (str "Error in "
                    (case key
                      :project-file "project file"
                      :sound-changes "sound changes"
                      :source-data "source data"
                      :target-data "target data")
                    ": " (ex-message e))}))))

; -------------------------------------------------------------------------------------------- }}} - 
; - spec-validator --------------------------------------------------------------------------- {{{ - 

(defn- spec-validator
  "Validates data against specs. Returns either the same dataset it got, or a hash-map with `:error`."
  [data key]
  (if (:error data)
    data
    (let [type (case key
                 :sound-changes {:where "sound changes", :display #(-> %1 meta :name), :spec SCItem}
                 :source-data {:where "source data", :display :display, :spec Datum}
                 :target-data {:where "target data", :display :display, :spec Datum})]
      (loop [items data
             idx   1]
        (if (empty? items)
          data
          (let [item   (first items)
                issues (m/explain (:spec type) item)]
            (if (nil? issues)
              (recur (rest items) (inc idx))
              {:error (str "Error in " (:where type)
                         " in item " idx
                         " (" ((:display type) item) ")"
                         ": " (me/humanize issues))})))))))

; -------------------------------------------------------------------------------------------- }}} - 
; - combine-data ----------------------------------------------------------------------------- {{{ -

; - pair-maker - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - {{{ - 

(defn- pair-maker
  "Combines source and target data into pairs:
    {:source-data [s]} -> [[s] [s] …]
    {:source-data [s], :target-data [t]} -> [[s t] [s t] …]"
  [dataset]
  (if (:error dataset)
    dataset
    (let [src  (:source-data dataset)
          trg  (:target-data dataset)
          data (if trg
                 (let [trg-idxd (index-coll trg)]
                   (for [s src]
                     [s (-> s :link trg-idxd)]))
                 (map vector src))]
      (if-let [warnings (:warnings dataset)]
        {:data data, :warnings warnings}
        {:data data}))))
 
; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} - 
; - link-validator - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - {{{ - 

; - filter-missing                                                                             {{{ - 

(defn- filter-missing
  "Finds items without a `:link`. Returns a hash-map with a vector of items that do have a `:link`, and a warning with a list of those that didn't."
  [dataset where]
  (loop [data dataset
         acc  []
         rmvd []
         idx  1]
    (if (empty? data)
      ; return result
      (cond-> {:data acc}
        (not-empty rmvd) (assoc :warnings [(str "Missing links. Removed items from " where " data: " (string/join ", " rmvd) ".")]))
      ; another round
      (let [item (first data)]
        (if (:link item)
          (recur (rest data)
                 (conj acc item)
                 rmvd
                 (inc idx))
          (recur (rest data)
                 acc
                 (conj rmvd (str idx " (" (:display item) ")"))
                 (inc idx)))))))

; -                                                                                            }}} - 
; - filter-unmatched                                                                           {{{ - 

(defn- filter-unmatched
  "Finds items without a matching `:link` in the corresponding dataset. Returns a hash-map with `:source-data`, `:target-data`, and `:warnings` about removed items."
  [src trg]
  (let [links-src (->> src (map :link) set)
        links-trg (->> trg (map :link) set)
        inter     (set/intersection links-src links-trg)
        src'      (filter #(-> %1 :link inter) src)
        trg'      (filter #(-> %1 :link inter) trg)
        diff-src  (set/difference links-src links-trg)
        diff-trg  (set/difference links-trg links-src)]
    (cond-> {:source-data src', :target-data trg'}
      (not-empty diff-src) (update :warnings conj (str "Unmatched links. Removed items from source data: " (string/join ", " diff-src) "."))
      (not-empty diff-trg) (update :warnings conj (str "Unmatched links. Removed items from target data: " (string/join ", " diff-trg) ".")))))

; -                                                                                            }}} - 
  
(defn- link-validator
  "Makes sure `:link`s are present, unique, and matching. Returns a hash-map with `:source-data`, `:target-data`, and `:warnings` about removed items."
  [src trg] ; both can be either a vector or a hash-map with `:error`
  (cond
    ; a poor man's monad for the threading in `load-data`
    (:error src) src
    (:error trg) trg
    ; skip if target data missing
    (nil? trg) {:source-data src}
    ; do validate
    :else 
    (let [; duplicate links
          dups-src  (duplicates (map :link src))
          dups-trg  (duplicates (map :link trg))
          ; missing links
          miss-src  (filter-missing src "source")
          miss-trg  (filter-missing trg "target")
          ; unmatched links
          unmatched (filter-unmatched (:data miss-src) (:data miss-trg))]
      (cond
        (not-empty dups-src) {:error (str "Duplicate links in source data: " dups-src)}
        (not-empty dups-trg) {:error (str "Duplicate links in target data: " dups-trg)}
        :else {:source-data (:source-data unmatched)
               :target-data (:target-data unmatched)
               :warnings (concat (:warnings miss-src)
                                 (:warnings miss-trg)
                                 (:warnings unmatched))}))))

; - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -}}} - 

(defn- combine-data
  "Takes source and target data, validates the `:link`s, and combines them into a vector of pairs."
  [source target]
  (let [validated (link-validator source target)
        paired    (pair-maker validated)]
    paired))

; -------------------------------------------------------------------------------------------- }}} -
; - load-component --------------------------------------------------------------------------- {{{ -

(defn- load-component
  "Loads and validates a single component from a project."
  [project key]
  (let [unpacked  (key project)
        loaded    (cond
                    (nil? unpacked)  nil          ; target data might be missing
                    (coll? unpacked) unpacked     ; data might be pre-loaded by the project file
                    :else (-> unpacked
                              (attach-to-path (:filename project))
                              (slurp)
                              (sci-loader key)))
        validated (spec-validator loaded key)]
    validated))

; -------------------------------------------------------------------------------------------- }}} -
; - load-projectfile ------------------------------------------------------------------------- {{{ -

(defn- load-projectfile
  "Reads, evals, and validates a project file. Returns either the resulting hash map + `:filename` containing the path to the project file, or a hash map with `:error`."
  [filename]
  (let [sci-opts {:namespaces {'toucan2.core (sci/copy-ns toucan2.core (sci/create-ns 'toucan2.core))}}
        loaded   (-> filename slurp (sci-loader :project-file sci-opts))
        issues   (m/explain ProjectFile loaded)]
    (if (some? issues)
      {:error (str "Error in project file: " (me/humanize issues))}
      (assoc loaded :filename filename))))

; -------------------------------------------------------------------------------------------- }}} -

; - load-project ----------------------------------------------------------------------------- {{{ -

(defn ^:export load-project
  "Load an entire project based on a project file."
  [filename]    ; a project file
  (let [project (load-projectfile filename)
        scs     (load-component project :sound-changes)
        source  (load-component project :source-data)
        target  (load-component project :target-data)
        data    (combine-data source target)]
    (cond
      (:error data)    data
      (:error project) project
      (:error scs)     scs
      :else            {:filename filename
                        :sound-changes scs
                        :data data
                        :has-target-data? (-> data :data first count (= 2))
                        :warnings (:warnings data)})))

; -------------------------------------------------------------------------------------------- }}} -

; ============================================================================================ }}} =
; = single =================================================================================== {{{ =

; - grow-tree -------------------------------------------------------------------------------- {{{ -

(defn ^:export grow-tree
  "Pipelines a value through a series of functions while keeping the intermediate results, in effect growing a tree. Functions that do not change the previous value are skipped. Returns a lazy sequence of hash-map with the keys `:id`, `:label`, `:value`, `:fname` and `:children` where `:value` is the given node, `:fname` is the name of the function that produces `:children` from it, and `:label` is a convenience copy of `-> :value :display`."
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
      
; -------------------------------------------------------------------------------------------- }}} -
; - find-paths ------------------------------------------------------------------------------- {{{ -

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
      

; -------------------------------------------------------------------------------------------- }}} -
; - print-leaves ----------------------------------------------------------------------------- {{{ -

; Not exactly a tree operation. Trees are stored as lazy sequences, so in order to collect the leaves, they have to be first realized. Simply pipelining a value requires the application of all the same sound changes, and saves the overhead of storing the intermediate values.
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

; -------------------------------------------------------------------------------------------- }}} -
; - print-tree ------------------------------------------------------------------------------- {{{ -

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

; -------------------------------------------------------------------------------------------- }}} -

; ============================================================================================ }}} =
; = batch ==================================================================================== {{{ =

; - find-irregulars -------------------------------------------------------------------------- {{{ -

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

; -------------------------------------------------------------------------------------------- }}} -

; ============================================================================================ }}} =

(println
  (load-project "/home/kamil/devel/clj/screpl/doc/sample-project.clj"))
