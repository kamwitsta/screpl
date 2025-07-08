(defn fn1 [x] [(update x :display #(str % "1"))])

(defn fn2 [x] [(update x :display #(str % "2a"))
               (update x :display #(str % "2b"))])

(defn fn3 [x] [(update x :display #(str % "3a"))
               (update x :display #(str % "3b"))
               (update x :display #(str % "3c"))])

[#'fn1 #'fn2 #'fn3]
