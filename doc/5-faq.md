# 5. Frequently asked questions
-------------------------------


## Error {:source-data ["missing required key"], :get-data-fn ["should be a valid function" "invalid type"]} 
------------------------------------------------------------------------------------------------------------
The return value of the function that fetches data from a database does not conform to the spec, which is: `{:source-data [source-datum-s], :target-data [target-datum-s]}` where `target-datum` is a map containing `:display` (a string) and `:id` (an integer, a keyword, or a string), and `source-datum` is a map containing `:display` and, if target data is also present, `:id`. This causes the spec for the entire project file to fail, and this in turn also causes the first, misleading, part of the error message.
