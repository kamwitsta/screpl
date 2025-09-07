# 1. Quick start
----------------

*SCRepl* stands for <u>S</u>ound <u>C</u>hange <u>Repl</u>icator. It is a tool to automatize the application of potentially very long series of sound changes to potentially very large sets of words.

**WARNING!** This is a very early version. There is no guarantee that the program will run correctly, or even that it will run at all.


## 1.1. Setup
-------------

SCRepl requires the [Java Runtime Environment](https://www.java.com/en/download/manual.jsp) (version 8 or higher) or its open-source alternative [OpenJDK](https://openjdk.org/projects/jdk/17) (version 17 or higher).

All the data and configuration are stored in text files. Those can be edited with virtually any editor, though a dedicated code editor will likely prove to be more convenient. There is a broad selection of free open-source editors for all the major operating systems.

If you want to compile SCRepl yourself, you will need [clj](https://clojure.org/releases/downloads) (version 1.12.0 or higher), [Leiningen](https://leiningen.org) (version 2.10.0 or higher), and JRE or OpenJDK mentioned above. Navigate to the main directory and run `lein run` to start SCRepl, or `lein uberjar` to produce a jar file which will be saved in the `target` directory.


## 1.2. Running SCRepl
----------------------

Having downloaded and unzipped SCrepl, navigate to the main folder and run

    java -jar target/uberjar/screpl.jar

After a short while, a window should appear. Please contact the author if it does not.

Now press ctrl-o and load the sample project available from [GitHub](https://github.com/kamwitsta/screpl/tree/main/doc) (you will need to download all the `sample-…` files . The interface is small and simple, and can be quickly learned through trial and error; there is no tutorial for it, only documentation for the code.
