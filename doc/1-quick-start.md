# 1. Quick start
----------------

*SCRepl* stands for <u>S</u>ound <u>C</u>hange <u>Repl</u>icator. It is a tool to automatize the application of potentially very long series of sound changes to potentially very large sets of words.

**WARNING!** This is a very early version. There is no guarantee that the program will run correctly, or even that it will run at all.


## 1.1. Setup
-------------

SCRepl requires the [Java Runtime Environment](https://www.java.com/en/download/manual.jsp) (version 8 or higher) or its open-source alternative [OpenJDK](https://openjdk.org/projects/jdk/17) (version 17 or higher).

As of the current version, SCRepl’s interface is command-line based. An ANSI-compliant terminal emulator with Unicode support is required, configured to use a font with Unicode support. On Windows, you will need to download and install one. On Mac and Linux you can use the system terminal. All the snippets below are meant to be typed into a terminal.

All the data and configuration are stored in text files. Those can be edited with virtually any editor, though a dedicated code editor will likely prove to be more convenient. There is a broad selection of free open-source editors for all the major operating systems.

If you want to compile SCRepl yourself, you will need [clj](https://clojure.org/releases/downloads) (version 1.12.0 or higher), [Leiningen](https://leiningen.org) (version 2.10.0 or higher), and JRE or OpenJDK mentioned above. Navigate to the main directory and run `lein run` to start SCRepl, or `lein uberjar` to produce a jar file which will be saved in the `target` directory.


## 1.2. Running SCRepl
----------------------

Having downloaded and unzipped SCrepl, navigate to the main folder and run

    java -jar target/uberjar/screpl-0.1.0-standalone.jar

You should see the following welcome message and prompt:

    SCRepl 0.1.0
    Type :quit or press ctrl-d to exit.
    
    =>

If you do not, please contact the author. If you do, type the following:

    => (load-project "doc/sample-project.edn")
    [ ✓ ] Loaded project doc/sample-project.edn.
    [ 🛈 ] Project doc/sample-project.edn
          Contains 3 sound change functions, 3 source data items, and 3 target data items.

The first line of the output is a confirmation that the project has been loaded. In general, whenever you type a command into SCRepl, SCRepl will display its result. In this case, it is basic information about the currently loaded project. You can view the same message any time by running `(project-info)`.

At this point we are finally ready to replicate some sound changes. Type

    => (grow-tree (get-from-source 1))
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

The `(get-from-source 1)` bit takes an item from source data, and `grow-tree` applies to it all the sound changes in the currently loaded project. The changes in the sample project append “1”, “2a”, “2b”, etc. at the end of the original word which, in this case, is simply “a”. They have not been designed to be linguistically sensible.

Now is a good moment to look at the files in the sample project. You might start by opening `sample-sound-changes.clj`, reordering the entries on the last line, running `(reload-project)`, and then `(grow-tree (get-from-source 1))` again. Then you might want to look at `sample-source-data.edn` and `sample-target-data.edn`; you will notice that word with ID 3 (just “c”) is unlikely to yield the expected result “mismatch” when the sound changes are applied to it. To confirm this suspicion, run `(find-irregulars)`.

In order to quit SCRepl, type `:quit` or press Ctrl-D.

The following chapters contain a detailed explanation how to prepare your own data for SCRepl, a more exhaustive guide to all of SCRepl’s capabilities, and a documentation of all of its functions.
