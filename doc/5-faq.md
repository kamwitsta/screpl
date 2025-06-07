# Frequently asked questions


## Error: ["should be a valid function" "invalid type"]
One of the sound change functions has the wrong return type. A sound change function must return a vector of hash-maps such as are found in source data (the only obligatory key is `:display`). See the chapter on [data preparation](2-data-preparation.md).
