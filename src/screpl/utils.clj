; - tokenize --------------------------------------------------------------------------------- {{{ -

(defn- find-positions
  "Finds the ranges of all the occurrences of a regular expression in a string."
  [re s]
  (let [matcher (re-matcher re s)]
    (loop [acc []]
      (if (.find matcher)
        (recur (conj acc [(.start matcher) (.end matcher)]))
        acc))))

(defn- get-subs
  "Takes either an interval or a single integer, and extracts a substring."
  [input x]
  (if (vector? x)
    (subs input (first x) (second x))
    (subs input x (inc x)))) 

(defn- merge-pair
  "Takes two intervals and returns: 1. the two fused if they overlap; 2. the range between them if they don't."
  [x y]
  (if (< (dec (first x)) (first y) (second x))
    [(first x) (second y)]
    (range (second x) (first y))))

(defn- merge-intervals
  "Takes a sorted list of intervals and: 1. merges overlapping intervals, and 2. fills the gaps with loose values. For example, [[0 1] [0 2] [1 3] [6 8]] -> [[0 3] 3 4 5 [6 8]]."
  [intervals]
  (loop [acc []
         [i1 i2 :as ivals] intervals]
    (if (< (count ivals) 2)
      (concat acc ivals)
      (let [merged (merge-pair i1 i2)]
        (if (vector? merged)
          (recur acc (cons merged (drop 2 ivals)))
          (recur (concat acc [i1] merged) (rest ivals)))))))

(defn tokenize
  "Split a string into tokens defined by regular expressions, e.g. ˈkʷaksoː -> [ˈkʷ a k s oː]."
  [multigraphs  ; vector of regular expressions, e.g. [#".ː" #".ʷ" #"ˈ."]
   input]       ; string to split
  (->> multigraphs                                  ; [#".ː" #".ʷ" #"ˈ."]
       (mapcat #(find-positions % input))           ; [[6 8] [1 3] [0 2]]
       (cons [0 1])                                 ; [[0 1] [6 8] [1 3] [0 2]]
       (cons [(dec (count input)) (count input)])   ; [[7 8] [0 1] [6 8] [1 3] [0 2]]
       (sort)                                       ; [[0 1] [0 2] [1 3] [6 8] [7 8]]
       (merge-intervals)                            ; [[0 3] 3 4 5 [6 8]]
       (map (partial get-subs input))))             ; ["ˈkʷ" "a" "k" "s" "oː"]

; -------------------------------------------------------------------------------------------- }}} -

(def words ["Lorem" "ipsum" "dolor" "sit" "amet," "consectetur" "adipiscing" "elit." "Donec" "vitae" "urna" "lorem." "Etiam" "eu" "diam" "sit" "amet" "sem" "tristique" "rutrum" "non" "nec" "massa." "Vivamus" "ut" "elementum" "lacus," "ac" "condimentum" "orci." "Aliquam" "varius" "quam" "egestas" "orci" "pharetra," "vel" "facilisis" "ex" "vestibulum." "Donec" "pulvinar" "eget" "orci" "vel" "feugiat." "Fusce" "eu" "massa" "at" "neque" "fermentum" "interdum" "a" "et" "risus." "Suspendisse" "ultrices" "tempus" "lacus" "sit" "amet" "tincidunt." "Fusce" "rhoncus" "est" "velit," "at" "accumsan" "est" "feugiat" "in." "Quisque" "auctor" "ante" "in" "luctus" "malesuada." "Cras" "non" "nisi" "non" "odio" "sagittis" "rutrum" "eu" "id" "libero." "Phasellus" "in" "convallis" "risus." "In" "ut" "massa" "at" "dolor" "venenatis" "dignissim" "ut" "at" "nisl." "Nam" "viverra" "sit" "amet" "nulla" "sed" "placerat." "Curabitur" "eget" "urna" "massa." "Donec" "nec" "massa" "ultricies," "varius" "diam" "vel," "placerat" "nisi." "Vestibulum" "venenatis" "eros" "massa," "non" "volutpat" "risus" "scelerisque" "in." "Curabitur" "facilisis" "luctus" "mi," "quis" "varius" "odio" "ultrices" "at." "Donec" "et" "tortor" "gravida," "pellentesque" "quam" "eget," "pellentesque" "nisl." "Vestibulum" "viverra" "nulla" "tortor," "a" "malesuada" "ante" "tempor" "et." "Sed" "vitae" "dignissim" "enim." "Nulla" "imperdiet" "lacus" "et" "accumsan" "auctor." "Duis" "quis" "elit" "pulvinar," "maximus" "augue" "ac," "hendrerit" "dolor." "Integer" "aliquet" "hendrerit" "commodo." "Nullam" "quis" "erat" "a" "tortor" "fringilla" "rhoncus" "non" "nec" "ex." "Nullam" "consequat" "orci" "eget" "elit" "auctor" "mattis." "Morbi" "libero" "nunc," "venenatis" "eget" "sem" "in," "sodales" "ornare" "ligula." "Phasellus" "eu" "ex" "in" "augue" "dignissim" "posuere." "Proin" "gravida" "dictum" "libero," "ut" "volutpat" "felis" "convallis" "in." "Nunc" "tincidunt" "mi" "et" "arcu" "efficitur," "eu" "semper" "turpis" "venenatis." "Curabitur" "tempus" "lorem" "non" "eros" "accumsan," "eu" "placerat" "massa" "viverra." "Praesent" "efficitur" "facilisis" "tortor," "in" "rutrum" "sapien" "pellentesque" "ut." "Class" "aptent" "taciti" "sociosqu" "ad" "litora" "torquent" "per" "conubia" "nostra," "per" "inceptos" "himenaeos." "Ut" "porttitor" "dolor" "id" "lobortis" "pharetra." "Etiam" "ac" "sodales" "libero." "Pellentesque" "in" "ante" "ante." "Suspendisse" "euismod," "mauris" "id" "semper" "feugiat," "libero" "neque" "cursus" "ipsum," "at" "aliquam" "elit" "turpis" "a" "arcu." "Vestibulum" "porttitor" "fermentum" "risus" "sit" "amet" "hendrerit." "Nam" "non" "libero" "eget" "lacus" "condimentum" "molestie" "ac" "aliquet" "purus." "Aenean" "vehicula" "quam" "in" "arcu" "sollicitudin," "a" "dictum" "arcu" "tempor." "Aenean" "sapien" "metus," "aliquam" "quis" "rutrum" "et," "vehicula" "non" "velit." "Etiam" "viverra" "vehicula" "nibh" "ac" "condimentum." "Aliquam" "vitae" "semper" "tellus," "ut" "bibendum" "nulla." "Sed" "dignissim" "nec" "risus" "a" "varius." "Curabitur" "tortor" "dui," "rhoncus" "et" "sagittis" "ac," "maximus" "a" "nibh." "Suspendisse" "potenti." "Aliquam" "facilisis" "varius" "gravida." "Maecenas" "faucibus" "turpis" "et" "elit" "tempus" "vulputate." "Morbi" "et" "turpis" "est." "Maecenas" "orci" "metus," "molestie" "lobortis" "velit" "nec," "consequat" "sollicitudin" "ex." "Nullam" "tempus" "tempus" "scelerisque." "Maecenas" "nisl" "lorem," "tempus" "eu" "gravida" "eu," "sodales" "quis" "eros." "Proin" "nisi" "tellus," "faucibus" "et" "dui" "quis," "molestie" "ornare" "nulla." "Nam" "convallis" "nulla" "tellus," "eget" "consequat" "dolor" "auctor" "et." "In" "ultrices" "nulla" "in" "sem" "ornare," "at" "mollis" "ex" "finibus." "Maecenas" "gravida" "hendrerit" "convallis." "Maecenas" "dapibus" "maximus" "gravida." "Morbi" "quis" "iaculis" "metus." "Etiam" "aliquam" "convallis" "commodo." "Curabitur" "elit" "velit," "condimentum" "et" "mattis" "nec," "ultricies" "ut" "lectus." "In" "at" "varius" "libero," "et" "bibendum" "ante." "Aliquam" "eget" "neque" "venenatis," "aliquam" "velit" "vel," "efficitur" "ex." "Duis" "tempor" "tincidunt" "quam" "quis" "porttitor." "Interdum" "et" "malesuada" "fames" "ac" "ante" "ipsum" "primis" "in" "faucibus." "Fusce" "venenatis" "lacus" "sed" "tellus" "mattis," "quis" "ornare" "nulla" "tincidunt."])
(def graphs [#"ab" #"ba" #"do" #"st" #"ad" #"it" #"nec" #"ae" #"s.." #".z"])

(time
  (doseq [w (flatten (repeat 10 words))]
    (println (tokenize graphs w))))
