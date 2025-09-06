# 5. Common errors
------------------

The errors displayed by SCRepl are not always immediately obvious. Listed below are some of the particularly cryptic ones together with the likely cause. The types they refer to are as follows:

- `=>GetDataFn`: a function that takes no arguments and returns data as vectors wrapped in a map: `{:source-data [SourceDatum]}` or `{:source-data [SourceDatum], :target-data [TargetDatum]}`. Presumably, the data are imported from a database, though other sources are also perfectly valid.
- `SourceDatum`: a map containing the field `:display string` and, if target data are also present `:id int/keyword/string`.
- `TargetDatum`: a map containing the fields `:display string` and `:id int/keyword/string`.


## Error ["invalid type" "invalid type"]
----------------------------------------
The format of the project file is incorrect. The correct format is either `{:sound-changes string, :source-data string}` plus optionally `:target-data string`, or `{:sound-changes string, :get-data-fn =>GetDataFn}` where `string`s represent paths to files, and `=>GetDataFn` is a function of type `=>GetDataFn` (see above).


## Error {:source-data ["missing required key"], :get-data-fn ["should be a valid function" "invalid type"]} 
------------------------------------------------------------------------------------------------------------
The function that fetches data from a database does not conform to the spec `=>GetDataFn` (see above; most likely the cause is the type of the return value). This causes the spec for the entire project file to fail, and this in turn causes the first, misleading part of the error message.
