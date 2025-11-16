(require '[clojure.set :as set]
         '[toucan2.core :as db])

(def db-config
  {:dbtype           "mariadb"
   :dbname           "balklat"
   :host             "localhost"
   :user             "kamil"
   :password         "c"
   :sessionVariables "sql_mode='ANSI_QUOTES'"})

(defn- fetch-data [which]
  (let [data (db/select :conn db-config "words" :language (case which :source "Latin" :target "Romanian"))
        categories #{"NomSg" "VocSg" "AccSg" "GenSg" "DatSg" "NomPl" "VocPl" "AccPl" "GenPl" "DatPl"}
        filter-category #(contains? categories (:category %))
        map-link #(assoc % :link (str (:entry_id %) "-" (:category %)))
        map-into #(into {} %)]
    (into []
          (comp
            (map map-link)
            (map map-into)
            (filter filter-category))
          data)))

{
 ; :source-data "sample-source-data.edn"
 ; :target-data "sample-source-data.edn"
 :sound-changes "sample-sound-changes.clj"
 :source-data (fetch-data :source)
 :target-data (fetch-data :target)}
