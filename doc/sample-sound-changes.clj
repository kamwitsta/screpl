(defn fndhfsdfsdhfjlhdsfjk1
  "Sample sound change nr 1. Returns one word, with '1' appended to it."
  [x]
  [(update x :display #(str % "1"))])

(defn fn2
  "Sample sound change nr 2. Returns two words, with '2a', and '2b' appended to them."
  [x]
  [(update x :display #(str % "2a"))
   (update x :display #(str % "2b"))])

(defn fn3
  "Sample sound change nr 3. Returns three words, with '3a', '3b', and '3c' appended to them."
  [x]
  [(update x :display #(str % "3a"))
   (update x :display #(str % "3b"))
   (update x :display #(str % "3c"))])

[#'fn1 #'fn2 #'fn3]
