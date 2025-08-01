# 2. Data preparation
---------------------

A SCRepl dataset consists of three to four files:

1. [a project file](#project-file),
2. [a file with source data](#source-data-file),
3. optionally [a file with target data](#target-data-file), and
4. [a file with sound changes](#sound-changes-file).

The project file and the data file(s) need to be in the [edn](https://github.com/edn-format/edn) format, the sound changes file is [Clojure](https://clojure.org/) code. It is not necessary to master Clojure in order to use SCrepl. Much can be accomplished by simply substituting data in the examples below; it is only with particularly complex sound changes that some command of Clojure will be required. That said, it will be helpful to introduce several conventions:

- Square brackets (`[]`) represent a *vector*: an ordered collection. SCRepl data files may contain many items in them but they must be all enclosed in a single vector, that is to say a data file must begin with a `[` and end with a `]`.
- Curly brackets (`{}`) represent a *hash map*: a collection of key–value pairs. Every word in SCRepl must be represented as a map.
- Quotes (`""`) represent a *string*: a piece a of text.
- Colon (`:`) in front of a word represents a *keyword*: a data type used by SCRepl for keys in maps.
- Semicolon (`;`) represents a comment: anything that follows it, till the end of the line, will be ignored by SCRepl.

The examples below illustrate how these conventions come together.


## 2.1. Project file
--------------------

Data and sound change files cannot be loaded into SCRepl independently, they must come as a single bundle. The reason for this is that data are validated upon loading. If the user wished to switch to a different project mid-session, it is possible that e.g. new target data would get validated against the old source data, resulting in an unjustified failure.

Luckily, a SCRepl project file is very simple. It is a single hash map with the paths (absolute or relative) to all the other files.

    ; sample-project-file.clj
    
    {:source-data "source-data-file.edn"
     :sound-changes "sound-changes-file.clj"
     :target-data "target-data-file.edn"}       ; optional

Curly braces mark the beginning and end of a hash map; inside the map, keys are keywords (phrases without spaces, prefixed with a colon), and values are strings (phrases that may include spaces, and are enclosed in quotation marks). Semicolon marks the beginning of a comment, i.e. a piece of information meant for humans rather than SCRepl. (Inside a string, however, a semicolon is just a semicolon, and a colon is just a colon.) Commas between key-value pairs in a map are optional, and whitespace is not significant.

The `:target-data` field, being optional, can be omitted and its value can be set to `nil`. In theory, the map can also include other keys (they will be ignored) but it is not recommended.

It is also possible to make the project file quite complex, however. When data are loaded from files, the assumption is that these files have been prepared specifically for SCRepl, and are therefore internally organized in a way that SCRepl can understand. On the other hand, a database might serve multiple purposes and therefore its organization might be very different. Only the user knows what it is, and so it is left to the user to provide a function that reorganizes data fetched from a database into a shape that SCRepl can interpret.

When loading data from a database, the fields `:source-data` and `:target-data` are omitted, and in their place, the field `:get-data-fn` is required, which points to a function that takes no arguments, and returns a hash map with the two omitted fields. To connect to the database, the [Toucan2](https://github.com/camsaul/toucan2) library can be used. One caveat connected with it is that the data it fetches come wrapped in `toucan2.instance/instance`. It may not always be necessary but it is certainly good practice to unwrap them before passing them to SCRepl. It is necessary, on the other hand, to make sure that they are enclosed in a vector rather than a list.

    ; database-based-project.clj

    (require '[toucan2.core :as db])

    (defn get-data []
      (let [db-config   {:dbtype           "mariadb"
                         :dbname           "my_database"
                         :host             "localhost"
                         :port             3306
                         :user             "username"
                         :password         "1234"
                         :sessionVariables "sql_mode='ANSI_QUOTES'"}
            source-data (->> (db/select :conn db-config "WORDS" :language "Proto-Slavic")
                             (map #(into {} %))      ; unwrap from toucan2.instance/instance
                             (vec))                  ; ensure vector, not list
            target-data (->> (db/select :conn db-config "WORDS" :language "Polish")
                              (map #(into {} %))
                              (vec))
        {:source-data source-data
         :target-data target-data}))

    {:sound-changes "sample-sound-changes.clj"
     :get-data-fn get-data}


## 2.2. Source data file
------------------------

Like the project file, the source data file also needs to be in the edn format. It consists of a single vector (everything after it will be ignored) which contains multiple hash maps, each holding one word. The only obligatory field is `:display` which tells SCRepl how to display the given word. The value of `:display` must be a string.

    ; Example 2.2-1
    
    [{:display "pudding"
      :phonology "pʊdɪŋ"}]

Typically, keys will be keywords, values will be strings, and each pair will represent a different “tier”. The user is, however, free to deviate from this model and use arbitrary data types with arbitrary semantics. Different words might even have different sets of fields. The order of fields is also not significant.

    ; Example 2.2-2
    
    [{:id 1
      :language :english
      :phonology "sʌndeɪ"
      :syllables ["sʌn" "deɪ"]
      :display "sundae"}
    
     {:display "chocolate"
      :phnl "tʃɒklət"
      :syllable-breaks [4]
      :phnl-us "tʃɑːklət"
      :syllable-breaks-us [5]}
    
     {:phnl "vənɪlə"
      :syllable-breaks [2 4]}
      :display "vanilla"}]

That said, confused jumble is not the recommended format for data storage. Besides the `:display` field, and enclosing all maps in a vector, the only restriction is that each item must be compatible with the [sound change functions](#sound-changes-file).

The examples above were designed to be universally understandable. In actual specialist work, however, it is recommended to use a transcription where each character represents a single phoneme and each phoneme is represented by a single character, i.e. virtually any transcription but the IPA. This will greatly facilitate the preparation of sound changes, as well as any later modifications and attempts at reusing the data.


## 2.3. Target data file
------------------------

The only difference between the target data file and the source data file, is that besides the `:display` field, the former also requires and `:id` field. Importantly, however, when a target data file is specified, the same requirement also applies to the source data file.

The `:id` field links items from the source data to items from the target data. For this reason, `:id`’s cannot repeat, and both source and target data must share the same set of `:id`’s. The `:id` field can be an integer, a keyword, or a string and, though this is not recommended, all three types can be freely mixed between different items. The order in the source data does not need to be the same as in the target data.
 
    ; Example 2.3-1

    ; Source data
    [{:id 1
      :display "apple"}

     {:id "pear"
      :display "pear"}

     {:id :quince
      :display "quince"}]

    ; Target data
    [{:id "pear"
      :display "cardamom"}

     {:id 1
      :display "cinnamon"}

     {:id :quince
      :display "cloves"}]


## 2.4. Sound changes file
--------------------------

Sound changes are the most demanding part of data preparation because they are simply regular Clojure functions. SCRepl runs them in a sandbox so they should not be able to do any actual harm but it must be nevertheless emphasized that one should

**WARNING!** Never run sound changes from an untrusted source.

A sound change function takes one argument, a hash map, and returns a vector of like hash maps. Both must be in the same format as the source data, but because this format is defined almost entirely by the user, SCRepl’s ability to automatically validate the correctness of sound change functions is severely limited. The functions may contain docstrings, as well as arbitrary additional metadata.

The sound changes file must end with a vector of functions that are to be loaded, in the order in which they are to be applied. (This can be changed later.) The functions need to be given as vars, i.e. prefixed with `#'`.

    ; Example 2.4-1

    ;; Import the `clojure.string` library, and with it, the `replace`
    ;; function which implements regular expression substitution.
    (require '[clojure.string :as str])

    ;; Define a function with the name `a>e`.
    ;; (Clojure allows selected special characters in function names.)
    (defn a>e
      ;; A description of the function.
      "A simple regular expression that changes all a’s to e’s."
      ;; A vector of arguments. Sound change functions can only take one
      ;; argument, but it does not need to be called `x`.
      [x]
      ;; Here is where the actual substitution takes place.
      ;; The `update` function takes the original hash map (`x`), and returns
      ;; one just like it but with the `:display` field modified by applying
      ;; to it the function `str/replace`.
      ;; `str/replace`, in turn, takes two arguments:
      ;; `#"a"` – the regular expression to find and substitute, and
      ;; `"e"` – the string with which to substitute it.
      ;; The whole is enclosed in square brackets because a sound change
      ;; function is expected to return a vector.
      [(update x :display str/replace #"a" "e")])

    ;; This is the vector of functions that will be exported to SCRepl.
    ;; Note that each function name must be prefixed with `#'`.
    [#'a>e]

This is much to unpack, and far beyond the scope of this tutorial. The examples given here can be reused by simply replacing the specific data, and they cover some of the most common needs, but for deeper understanding and more involved sound changes, the user is referred to one of the many Clojure tutorials that are available, e.g. [Clojure – learn Clojure](https://www.clojure.org/guides/learn/clojure), [Try Clojure](https://tryclojure.org), [Clojure guides](https://clojure-doc.org/articles/tutorials/introduction), Eric Normand’s [Clojure tutorial](https://ericnormand.me/guide/clojure-tutorial), Anthony Galea’s [Notes on Clojure](https://github.clerk.garden/anthonygalea/notes-on-clojure), as well as the excellent documentation of specific functions at [ClojureDocs](https://clojuredocs.org). Large language models such as [ChatGPT](https://chatgpt.com) or [Google AI Studio](https://aistudio.google.com) can also prove very helpful.

Sound change functions will often rely on a construct called *regular expressions* (also referred to as *regexp* or *regex*). Regular expressions are a way of representing not so much text itself, as patterns in text. For example, the expression `b[ae]b` stands for ‘*b* followed by either *a* or *e*, and then another *b*’, that is it simultaneously represents both *bab* and *beb*’, `[^b]a` for ‘*a* preceded by anything but *b*’, `\d+` for ‘one or more digits’, etc. Regular expressions are a very powerful tool but, again, a topic that far exceeds our scope here, so the user is referred to one of the many tutorials, such as [RegexOne](https://regexone.com), [Regex Learn](https://regexlearn.com), [regex101](https://regex101.com), or [Regexper](https://regexper.com). One just needs to bear in mind that there exist various implementations of regular expressions which differ in some details. The specification of the particular version available in Clojure and, by extension, in SCRepl can be found in the [Java documentation](https://docs.oracle.com/en/java/javase/15/docs/api/java.base/java/util/regex/Pattern.html). See also the [documentation](https://clojuredocs.org/clojure.string/replace) for Clojure’s `string/replace` function for some inspiring examples.

It is not necessary to complete a full course of Clojure and regular expressions in order to write sound change functions for SCRepl. Below are several more examples illustrating certain patterns that are most likely to be useful in this task. With any luck, all the required sound changes can be written by simply modifying the specific substitutions in them and without the need to adjust their logic at all.

    ; Example 2.4-2

    ;; This function showcases the threading macro `->`.
    ;; Sample output:
    ;; {:display "bab"} => [{:display "bab"}]
    ;; {:display "baba"} => [{:display "bab"}]
    (defn drop-final-vowel
      "If the last character is a vowel, drop it."
      [x]
      ;; A local function definition.
      ;; The sound changes file may also define helper functions such as this
      ;; one globally, and simply not include them in the vector that the
      ;; sound changes file returns to SCRepl.Unicode characters are allowed.
      (letfn [(vowel? [c] (contains? #{\a \e \i \o \ö \u \ü} c))]
        ;; A simple example of the threading macro. This is equivalent to
        ;; `(vowel? (last (:display x)))`.
        (if (-> x :display last vowel?)
          ;; This line is executed when the `if` above is `true`.
          ;; `assoc` takes a hash map (in this case, `x`), and returns one
          ;; like it but with one of the values changed to the value given as
          ;; the last argument. Here, the value under the key `:display` is
          ;; changed to the result of another threading macro, which takes the
          ;; original hash map `x`, extracts from it the value under the key
          ;; `:display`, drops its last character, and joins the disconnected
          ;; characters `drop-last` returns back into a single string.
          [(assoc x :display (-> x :display drop-last str/join))]
          ;; This line is execute when the `if` above is `false`. The original
          ;; hash map `x` is enclosed in square brackets because sound change
          ;; functions must return a vector.
          [x])))


    ;; This function operates on more than one field at the same time.
    ;; It is more complex version of the `drop-final-vowel` function which
    ;; also updates syllable breakpoints (encoded as a vector of integers
    ;; representing positions in the string, before which the break occurs,
    ;; counting from zero).
    ;; Sample output:
    ;; {:display "bab" :syllables [0]} => [{:display "bab" :syllables [0]}]
    ;; {:display "baba" :syllables [0 2]} => [{:display "bab" :syllables [0]}]
    (defn drop-final-vowel-2
      "If the last character is a vowel, drop it, and update syllable breakpoints."
      [x]
      (letfn [(vowel? [c] (contains? #{\a \e \i \o \ö \u \ü} c))]
        (if (-> x :display last vowel?)
          ;; `assoc` can do multiple substitutions simultaneously.
          [(assoc x :display   (-> x :display butlast str/join)
                    :syllables (-> x :syllables butlast))]
          [x])))


    ;; This function returns multiple results.
    ;; This is sometimes necessary when replicating sound changes in reverse,
    ;; e.g. here we have a language in which long *ā* and *ē* merged with
    ;; short *a* and *e*, and we want to find all the possible proto-forms.
    ;; Sample output:
    ;; {:display "babebi"} => [{:display "babebi"}
    ;;                         {:display "babēbi"}
    ;;                         {:display "bābebi"}
    ;;                         {:display "bābēbi"}]
    ;; This function may appear intimidating at first but it is in fact not
    ;; as difficult to understand as it seems. This is the most advanced
    ;; function discussed here.
    (defn revert-length-loss
      [x]
      ;; We begin by defining two local functions and one local variable.
      ;; `expand` takes a character and returns a vector of substitutions
      ;; for it. For characters that do not need to be modified, it returns
      ;; a vector with just this character.
      (let [expand       (fn [char]
                           (case char
                             \a  [\a \ā]
                             \e  [\e \ē]
                             [char]))
            ;; `combinations` takes a vector of strings and a character, and
            ;; returns a vector of all of their combinations. The actual
            ;; magic happens in `for`; see e.g. [Notes on Clojure](https://github.clerk.garden/anthonygalea/notes-on-clojure/commit/f54b416adb5b0193759e07516889bf2b6d91bda9/notebooks/sequences/#for),
            ;; and [ClojureDocs](https://clojuredocs.org/clojure.core/for).
            combinations (fn [beginnings next-char]
                           (for [b beginnings
                                 c (expand next-char)]
                             (str b c)))
            ;; `reduce` is a function of many faces. Here, it can be thought of
            ;; as a loop that goes through `(x :display)` character by character
            ;; and calls `combinations` at every iteration, while acccumulating
            ;; the result. See the links mentioned above for a more detailed
            ;; explanation and examples.
            display'     (reduce combinations [""] (x :display))]
        ;; The new values have already been produced at this point, and are
        ;; stored in `display'`, but the function must return a vector of
        ;; hash maps that the next sound change function will be able to
        ;; process. We use `assoc` to wrap a value in a hash map, and `for`
        ;; to have this done for every new value produced.
        (vec
          (for [d' display']
            (assoc x :display d'))))

Error reporting is unfortunately not one of Clojure’s fortes. Especially with more demanding sound changes, therefore, it is easier to write and test them in isolation before loading them into SCRepl. Probably the best tool for that are code editors but unfortunately the most popular ones (see e.g. [Clojure – editors](https://www.clojure.org/guides/editors)) tend to have a steeper learning curve, which is why beginners might prefer to start with an online editor such as [myCompiler](https://www.mycompiler.io/new/clojure), [Replit](https://replit.com), etc.
