; - tokenize --------------------------------------------------------------------------------- {{{ -

(defn- find-positions
  "Finds the ranges of all the occurrences of a regular expression in a string."
  [re s]
  (let [matcher (re-matcher re s)
        matches (atom [])]
    (while (.find matcher)
      (swap! matches conj [(.start matcher) (.end matcher)]))
    @matches))

(defn- merge-intervals
  "Takes a sorted list of intervals and: 1. merges overlapping intervals, and 2. adds intervals to fill the gaps. Filler intervals have meta `:gap true`. E.g. [[0 1] [0 2] [1 3] [6 8] [7 8]] -> [[0 3] [3 6] [6 8]], where [3 6] has meta `:gap true`."
  [intervals]
  (letfn
    [(fuse [x y] [(first x) (second y)])
     (overlap? [x y] (< (dec (first x)) (first y) (second x)))]
    (loop [[i1 i2 :as ivals] intervals
           acc []]
      (if (< (count ivals) 2)
        (concat acc ivals)
        (if (overlap? i1 i2)
          (recur
            (cons (fuse i1 i2) (drop 2 ivals))
            acc)
          (recur
            (rest ivals)
            (conj
              acc
              i1
              (with-meta [(second i1) (first i2)] {:gap true}))))))))

; Takes a vector of potentially overlapping regular expressions, and a string. [#".ː" #".ʷ" #"ˈ."], ˈkʷaksoː
; |> Looks for the expressions in the string: [[6 8] [1 3] [0 2]]
; |> Adds the beginning and end of the string: [[7 8] [0 1] [6 8] [1 3] [0 2]]
; |> Sorts the ranges: [[0 1] [0 2] [1 3] [6 8] [7 8]]
; |> Merges overlapping ranges: [[0 3] [3 6] [6 8]]
; |> Extracts substrings for every range, and if the range has meta :gap true, splits it into characters.
(defn tokenize
  "Split a string into tokens defined by regular expressions, e.g. ˈkʷaksoː -> [ˈkʷ a k s oː]."
  [multigraphs  ; vector of regular expressions, e.g. [#".ː" #".ʷ" #"ˈ."]
   input]       ; string to split
  (let [intervals (reduce #(concat %1 (find-positions %2 input)) [] multigraphs)
        filled    (let [cnt (count input)] (conj intervals [0 1] [(dec cnt) cnt]))
        sorted    (sort filled)
        merged    (merge-intervals sorted)]
    (println sorted merged)
    (loop [ivals merged
           acc []]
      (if (empty? ivals)
        acc
        (let [curr   (first ivals)
              tokens (subs input (first curr) (second curr))]
          (recur
            (rest ivals)
            (if (-> curr meta :gap)
              (into acc (->> tokens seq (map str)))
              (conj acc tokens))))))))

; -------------------------------------------------------------------------------------------- }}} -

