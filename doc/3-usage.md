# 3. Usage
----------

It is assumed here that, having read [the previous chapter](2-data-preparation.md), the reader is familiar with the basics of Clojure and, having read the one before it, the basics of SCRepl. SCRepl’s command-line interface is basically a limited version of Clojure REPL. It is possible to define variables and functions in it, and essentially do anything one could in `clj` – except system operations. This is a safeguard against malicious code masquerading as sound changes that the user might obtain from a third party and be tricked into running on their own machine. No guarantee is given, however, as to the efficacy of this safeguard, so let us reiterate here that one should

**WARNING!** Never run sound changes from an untrusted source.

What is below is effectively a user’s guide. Technical documentation can be found in [the following chapter](4-documentation.html).


## 3.1. Loading data
--------------------

SCRepl projects can be loaded using `load-project`, e.g.

    ; Example 3.1-1

    => (load-project "doc/sample-project.edn")
    [ ✓ ] Loaded project doc/sample-project.edn.
    [ 🛈 ] Project doc/sample-project.edn
          Contains 3 sound change functions, 3 source data items, and 3 target data items.

Note that the separator is a slash, also on Windows. The information displayed after a project has been loaded can be printed again by running `project-info`. Only one project can be loaded at one time, and once loaded, it cannot be changed from inside SCRepl. If a change has been made to the project after it had been loaded, in theory, one could run multiple instances of SCRepl simultaneously, each with a different version of the same project loaded into it, but a more efficient and less confusing way to achieve the same goal is to use `reload-project`.


## 3.2. Tree operations
-----------------------

One way to investigate sound changes is to examine the chain of outputs they produce. This is relatively straightforward when each change only returns one output, but quickly becomes less so when the changes yield multiple results. This can happen when the changes are intended to reconstruct previous forms. For example, applying changes that return 1, 2, and 3 forms, yields the following tree:

    ; Example 3.2-1

    => (grow-tree {:display "a"})
    [ 🛈 ] A tree from "a" through 3 sound changes.
    a fn1
    └─ a1 fn2
       ├─ a12a fn3
       │  ├─ a12a3a 
       │  ├─ a12a3b 
       │  └─ a12a3c 
       └─ a12b fn3
          ├─ a12b3a 
          ├─ a12b3b 
          └─ a12b3c 

and in reverse,

    ; Example 3.2-2

    => (grow-tree [fn3 fn2 fn1] {:display "a"})
    [ 🛈 ] A tree from "a" through 3 sound changes.
    a 
    ├─ a3a 
    │  ├─ a3a2a 
    │  │  └─ a3a2a1 
    │  └─ a3a2b 
    │     └─ a3a2b1 
    ├─ a3b 
    │  ├─ a3b2a 
    │  │  └─ a3b2a1 
    │  └─ a3b2b 
    │     └─ a3b2b1 
    └─ a3c 
       ├─ a3c2a 
       │  └─ a3c2a1 
       └─ a3c2b 
          └─ a3c2b1 

The sound change `revert-length-loss` from [the previous chapter](2-data-preparation.md), which reconstructs forms from before the loss of length on *ā* and *ē*, generates a tree with two final results for a word with a single *a* or *e* in it, four leaves for a word with two *a*’s or *e*’s, eight for three, etc.

    ; Example 3.2-3

    => (grow-tree {:display "babeba"})
    [ 🛈 ] A tree from "babeba" through 1 sound changes.
    babeba revert-length-loss
    ├─ babeba 
    ├─ babebā 
    ├─ babēba 
    ├─ babēbā 
    ├─ bābeba 
    ├─ bābebā 
    ├─ bābēba 
    └─ bābēbā 

This means that trees can get very large very quickly. A series of ten functions, yielding on average two outputs, can be expected to produce a tree with 2^10^ leaves and 2^9^ + 2^8^ + … + 2^0^ nodes, a total of more than 2000 elements. The function `count-tree` checks just how big a tree is (using the sample project from example 3.2-1 above):

    ; Example 3.2-4

    => (def sample-tree (grow-tree {:display "a"}))
    #'user/sample-tree 
    
    => (count-tree sample-tree)
    [ 🛈 ] A tree from "a" with 4 nodes and 6 leaves.

☞ By convention, the original form (“a”) is callet the *root*, the intermediate forms (“a1”, “a12a”, “a12b”) are called *nodes*, and the final forms (“a12a3a”, etc.) *leaves*.

The function `print-tree` prints the tree to the terminal. If the tree has been assigned to a variable, the same can be achieved by simply typing the name of the variable: 

    ; Example 3.2-5

    => (print-tree sample-tree)
    [ 🛈 ] A tree from "a" through 3 sound changes.
    a fn1
    └─ a1 fn2
       ...
    
    => sample-tree
    [ 🛈 ] A tree from "a" through 3 sound changes.
    a fn1
    └─ a1 fn2
       ...

When a tree is so large that displaying it in the terminal is impractical, `print-tree` can take a filename as an additional argument, and print the tree to a file instead. See also `print-products` in section 3.4. below.

In order to test whether a tree contains specific leaves without printing the entire tree. The function `find-paths` returns full paths from the root of the tree to any leaf that satisfies a regular expression:

    ; Example 3.2-6

    => (find-paths sample-tree #"a12")
    [ 🛈 ] Found 6 paths from "a" to "a12".
    ([a a1 a12a a12a3a] [a a1 a12a a12a3b] [a a1 a12a a12a3c] [a a1 a12b a12b3a] [a a1 a12b a12b3b] [a a1 a12b a12b3c]) 

When it is more convenient to search for a value as a whole, rather than just its part as regular expressions are often used for, the search term can be enclosed between `^` and `$` which denote that the value must begin and end with the given character:

    ; Example 3.2-7
    
    => (find-paths sample-tree #"^a12$")
    [ 🛈 ] Found no paths from "a" to "^a12$".
    () 
    
    => (find-paths sample-tree #"^a12a3a$")
    [ 🛈 ] Found 1 path from "a" to "^a12a3a$".
    ([a a1 a12a a12a3a]) 
    
    => (find-paths sample-tree #"3a$")
    [ 🛈 ] Found 2 paths from "a" to "3a$".
    ([a a1 a12a a12a3a] [a a1 a12b a12b3a]) 

See also `produces-target?` in section 3.4. below.


## 3.3. Extracting data
-----------------------

In the examples above, e.g. in 3.2-1, we typed the entire datum as a parameter in a function call. Sometimes, it might be more convenient to use predefined items from source and target data instead. They can be accessed using the functions `get-from-source` and `get-from-target`, respectively:

    ; Example 3.3-1
    
    => (get-from-source 1)
    {:id 1, :display a} 
    
    => (get-from-source :display "b")
    {:id 2, :display b}

When given only one parameter, `get-from-source` and `get-from-target` will search by `:id`. To search by some other key, it can be aded as an additional parameter before the value. `:id`’s are unique, as validated upon loading the project, but other values do not have to be. When multiple items are found, the `get-from-` functions will return the entire collection, potentially leading to an error, e.g.:

    ; Example 3.3-2
    
    ; No errors when searching by a unique `:id`:
    
    => (grow-tree (get-from-source 1))
    [ 🛈 ] A tree from "a" through 3 sound changes.
    a fn1
    └─ a1 fn2
       ...
    
    ; Errors when searching by a non-unique key
    ; (this example does not use the sample project):
    
    => (get-from-source :display "a duplicated value")
    ({:id 3, :display a duplicated value} {:id 4, :display a duplicated value}) 
     
    => (grow-tree (get-from-source :display "a duplicated value"))
    [ ✗ ] Error nil :
          class clojure.lang.LazySeq cannot be cast to class clojure.lang.Associative (clojure.lang.LazySeq and clojure.lang.Associative are in unnamed module of loader 'app') 


## 3.4. Matching against target
-------------------------------

Besides `find-paths` mentioned in section 3.2. above, another way to see if the given form yields the given result when it has the given sound changes applied to it, is through the function `produces-target?`. It takes as many as four parameters but most can be omitted. Using again the sample project from example 3.2-1 above,

    ; Example 3.4-1

    => (produces-target? [#'fn1 #'fn2 #'fn3] (get-from-source 1) (get-from-target 1) [:display])
    true  

    => (produces-target? (get-from-source 1) (get-from-target 2) [:display])
    false
    
    => (produces-target? (get-from-source 1) [:display])
    true 

    => (produces-target? (get-from-source 1))
    true 

The parameters are, in order:

1. sound change functions; when missing, all the functions in the current project are used;
2. source item; this parameter cannot be omitted;
3. target item; when missing, the appropriate item is selected from the target data by `:id`;
4. keys that will used to determine whether the produced result is equal to the target item; when missing, `[:display]` will be used; must be a vector.

There is another, similar function called `print-products`. It lists all the final results of the application of a series of sound changes to a form, and like `produces-target?` it is quite liberal with its arguments:

    ; Example 3.4-2 

    => (print-products [#'fn1 #'fn2 #'fn3] (get-from-source 1) "/home/kamil/Tmp/products.txt")

    => (print-products [#'fn1 #'fn2 #'fn3] {:display "a"})
    a → a12a3a, a12a3b, a12a3c, a12b3a, a12b3b, a12b3c

    => (print-products (get-from-source 1) "/home/kamil/Tmp/products.txt")

    => (print-products [(get-from-source 1) (get-from-source 2)])
    a → a12a3a, a12a3b, a12a3c, a12b3a, a12b3b, a12b3c
    b → b12a3a, b12a3b, b12a3c, b12b3a, b12b3b, b12b3c

That is, the first argument (optional) is a vector of sound change functions to be applied, the second argument (mandatory) is a either a single hash-map or a vector of hash-maps, and the third argument (optional) is the path to a file if the output is to be written to a file instead of the console. (The file is not overwritten, the results are appended to it.)

The function `produces-target?` can also be applied in batch to an entire dataset. This variant, instead of a single boolean, returns a list of items that do not yield their respective targets, i.e. those which would return `false` with `produces-target?`. Because of this small modification, the batch function is available under a different name: `find-irregulars`.

    ; Example 3.4-3
    
    => (find-irregulars)
    [{:id 3, :display c}]

It can also be used with a single parameter which corresponds to `keys` in `produces-target?`.
