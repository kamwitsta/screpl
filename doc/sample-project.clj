(require '[toucan2.core :as db])

(def db-config
  {:dbtype           "mariadb"
   :dbname           "balklat"
   :host             "localhost"
   :user             "kamil"
   :password         "c"
   :sessionVariables "sql_mode='ANSI_QUOTES'"})

(defn- fetch-data [which]
  (->> (db/select :conn db-config "words" :language (case which :source "Latin" :target "Romanian"))
       (map #(into {} %))
       (vec)))

{
 :source-data "sample-source-data.edn"
 :target-data "sample-target-data.edn"
 :sound-changes "sample-sound-changes.clj"}
 ; :source-data (fetch-data :source)
 ; :target-data (fetch-data :target)}
