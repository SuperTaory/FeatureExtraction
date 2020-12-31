# FeatureExtraction
## How to run?
Before you run, you need to build the application to get the `FeatureExtraction.jar` in your IDE. 
Then, you can choose to run the application `locally` or on a `cluster`.
1. Run application locally

`$SPARK_HOME/bin/spark-submit --class extractFeatures --master local path/to/FeatureExtraction.jar args_0 args_1 args_2`

2. Run on a YARN cluster

`$SPARK_HOME/bin/spark-submit --class extractFeatures --master yarn path/to/FeatureExtraction.jar args_0 args_1 args_2`

### Args
- `args_0`: The file path of stations information.
- `args_1`: The file path of travel data.
- `args_2`: The file path to save extracted features.

## Requirements
- Spark
- Scala

If you run on a Yarn cluster, `Hadoop` is required. 
