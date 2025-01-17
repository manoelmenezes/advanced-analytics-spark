package chapter04

import org.apache.spark.ml.classification.{DecisionTreeClassificationModel, DecisionTreeClassifier, RandomForestClassificationModel, RandomForestClassifier}
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.{VectorAssembler, VectorIndexer}
import org.apache.spark.ml.linalg.SparseVector
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.tuning.{ParamGridBuilder, TrainValidationSplit, TrainValidationSplitModel}
import org.apache.spark.ml.{Pipeline, PipelineModel}
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark.mllib.linalg.Matrix
import org.apache.spark.sql._

import scala.util.Random

class ForestCoverTypePrediction(private val spark: SparkSession) {

  import spark.implicits._

  val COV_TYPE_DATA_FILE = "/home/cy64/Downloads/covtype.data"

  private var dataWithoutHeader: DataFrame = null

  private var data: DataFrame = null

  private var trainDataAndTestData: (DataFrame, DataFrame) = null

  private var assembler: VectorAssembler = null

  private var assembledTrainData: DataFrame = null

  private var classifier: DecisionTreeClassifier = null

  private var randomForestClassifier: RandomForestClassifier = null

  private var model: DecisionTreeClassificationModel = null

  private var featureImportances: Array[(Double, String)] = null

  private var evaluator: MulticlassClassificationEvaluator = null

  private var predictions: DataFrame = null

  private var pipeline: Pipeline = null

  private var paramGrid: Array[ParamMap] = null

  private var paramGridFromRainForest: Array[ParamMap] = null

  private var validator: TrainValidationSplit = null

  private var validatorFromRandomForest: TrainValidationSplit = null

  private var validatorModel: TrainValidationSplitModel = null

  private var validatorModelFromRandomForest: TrainValidationSplitModel = null

  private var hyperParamsAndValidationMetrics: Array[(Double, ParamMap)] = null

  private var pipelineDecoded: Pipeline = null

  def getDataWithoutHeader: DataFrame = {
    if (dataWithoutHeader == null) {
      dataWithoutHeader = spark.read
        .option("inferSchema", true)
        .option("header", false)
        .csv(COV_TYPE_DATA_FILE)
    }
    dataWithoutHeader
  }

  def getData: DataFrame = {
    if (data == null) {
      val colNames = Seq("Elevation", "Aspect", "Slope",
        "Horizontal_Distance_To_Hydrology", "Vertical_Distance_To_Hydrology",
        "Horizontal_Distance_To_Roadways", "Hillshade_9am", "Hillshade_Noon",
        "Hillshade_3pm", "Horizontal_Distance_To_Fire_Points"
      ) ++ (
        (0 until 4).map(i => s"Wilderness_Area_$i")
        ) ++ (
        (0 until 40).map(i => s"Soil_Type_$i")
        ) ++ Seq("Cover_Type")

      data = getDataWithoutHeader.toDF(colNames:_*)
        .withColumn("Cover_Type", $"Cover_Type".cast("double"))
    }

    data
  }

  def getTrainDataAndTestData: (DataFrame, DataFrame) = {
    if (trainDataAndTestData == null) {
      val data = getData

      val Array(trainData, testData) = data.randomSplit(Array(0.9, 0.1))

      trainData.cache()
      testData.cache()

      trainDataAndTestData = (trainData, testData)
    }

    trainDataAndTestData
  }

  def getAssembler: VectorAssembler = {
    if (assembler == null) {
      val trainData = getTrainDataAndTestData._1
      val inputCols = trainData.columns.filter(_ != "Cover_Type")

      assembler = new VectorAssembler()
        .setInputCols(inputCols)
        .setOutputCol("featureVector")
    }

    assembler
  }

  def getAssembledTrainData: DataFrame = {
    if (assembledTrainData == null) {
      val trainData = getTrainDataAndTestData._1

      assembledTrainData = getAssembler.transform(trainData)
    }

    assembledTrainData
  }

  def getAssembledTrainDataColumn(colName: String): DataFrame = {
    getAssembledTrainData.select(colName)
  }

  def getAssembledTrainDataFeatureVector: DataFrame = {
    getAssembledTrainDataColumn("featureVector")
  }

  def getClassifier: DecisionTreeClassifier = {
    if (classifier == null) {
      classifier = new DecisionTreeClassifier()
        .setSeed(Random.nextLong())
        .setLabelCol("Cover_Type")
        .setFeaturesCol("featureVector")
        .setPredictionCol("prediction")
    }

    classifier
  }

  def getRandomForestClassifier: RandomForestClassifier = {
    if (randomForestClassifier == null) {
      randomForestClassifier = new RandomForestClassifier()
          .setSeed(Random.nextLong())
          .setLabelCol("Cover_Type")
          .setFeaturesCol("indexedVector")
          .setPredictionCol("prediction")
    }

    randomForestClassifier
  }

  def getModel: DecisionTreeClassificationModel = {
    if (model == null) {
      model = getClassifier.fit(getAssembledTrainData)
    }

    model
  }

  def getFeatureImportances: Array[(Double, String)] = {
    if (featureImportances == null) {
      val trainData = getTrainDataAndTestData._1
      val inputCols = trainData.columns.filter(_ != "Cover_Type")

      featureImportances = getModel.featureImportances
        .toArray
        .zip(inputCols)
        .sorted
        .reverse
    }

    featureImportances
  }

  def getPredictionsOnTrainingData: DataFrame = {
    if (predictions == null) {
      predictions = getModel.transform(getAssembledTrainData)
    }

    predictions
  }

  def getEvaluator: MulticlassClassificationEvaluator = {
    if (evaluator == null) {
      evaluator = new MulticlassClassificationEvaluator()
        .setLabelCol("Cover_Type")
        .setPredictionCol("prediction")
        .setMetricName("accuracy")
    }

    evaluator
  }

  def getAccuracy(predictions: DataFrame): Double = {
    getEvaluator.setMetricName("accuracy").evaluate(predictions)
  }

  def getF1Score(predictions: DataFrame): Double = {
    getEvaluator.setMetricName("f1").evaluate(predictions)
  }

  def getConfusionMatrix(predictions: DataFrame): Matrix = {
    val predictionRdd = predictions.select("prediction", "cover_type").as[(Double, Double)].rdd

    val multiclassMetrics = new MulticlassMetrics(predictionRdd)

    multiclassMetrics.confusionMatrix
  }

  def getConfusionMatrixDf(predictions: DataFrame): Dataset[Row] = {
    predictions.groupBy("Cover_Type")
      .pivot("prediction", (1 to 7))
      .count()
      .na.fill(0.0)
      .orderBy("Cover_Type")
  }

  def classProbabilities(data: DataFrame): Array[Double] = {
    val total = data.count()

    data.groupBy("Cover_Type").count()
      .orderBy("Cover_Type")
      .select("count").as[Double]
      .map(_ / total)
      .collect()
  }

  def getAccuracy: Double = {
    val (trainData, testData) = getTrainDataAndTestData
    val trainProbabilities = classProbabilities(trainData)
    val testProbabilities = classProbabilities(testData)

    trainProbabilities.zip(testProbabilities).map {
      case (trainProb, testProb) => trainProb * testProb
    }.sum
  }

  def getPipeline: Pipeline = {
    if (pipeline == null) {
      pipeline = new Pipeline().setStages(Array(getAssembler, getClassifier))
    }

    pipeline
  }

  def getParamGrid: Array[ParamMap] = {
    if (paramGrid == null) {
      val classifier = getClassifier
      paramGrid = new ParamGridBuilder()
        .addGrid(classifier.impurity, Seq("gini", "entropy"))
        .addGrid(classifier.maxDepth, Seq(1, 20))
        .addGrid(classifier.maxBins, Seq(40, 300))
        .addGrid(classifier.minInfoGain, Seq(0.0, 0.05))
        .build()
    }

    paramGrid
  }

  def getParamGridFromRainForest: Array[ParamMap] = {
    if (paramGridFromRainForest == null) {
      val classifier = getRandomForestClassifier
      paramGridFromRainForest = new ParamGridBuilder()
        .addGrid(classifier.impurity, Seq("gini", "entropy"))
        .addGrid(classifier.maxDepth, Seq(1, 20))
        .addGrid(classifier.maxBins, Seq(40, 300))
        .addGrid(classifier.minInfoGain, Seq(0.0, 0.05))
        .build()
    }

    paramGridFromRainForest
  }

  def getValidator: TrainValidationSplit = {
    if (validator == null) {
      validator = new TrainValidationSplit()
        .setSeed(Random.nextLong())
        .setEstimator(getPipeline)
        .setEvaluator(getEvaluator)
        .setEstimatorParamMaps(getParamGrid)
        .setTrainRatio(0.9)
    }

    validator
  }

  def getValidatorFromRandomForest: TrainValidationSplit = {
    if (validatorFromRandomForest == null) {
      validatorFromRandomForest = new TrainValidationSplit()
      .setSeed(Random.nextLong())
      .setEstimator(getPipelineDecoded)
      .setEvaluator(getEvaluator)
      .setEstimatorParamMaps(getParamGridFromRainForest)
      .setTrainRatio(0.9)
    }

    validatorFromRandomForest
  }

  def getValidatorModel: TrainValidationSplitModel = {
    if (validatorModel == null) {
      validatorModel = getValidator.fit(getTrainDataAndTestData._1)
    }

    validatorModel
  }

  def getValidatorModelFromRandomForest: TrainValidationSplitModel = {
    if (validatorModelFromRandomForest == null) {
      val data = decodeOneHot(getTrainDataAndTestData._1)
      validatorModelFromRandomForest = getValidatorFromRandomForest.fit(data)
    }

    validatorModelFromRandomForest
  }

  def extractParamMapFromBestModel: ParamMap = {
    val validatorModel = getValidatorModel

    validatorModel.bestModel.asInstanceOf[PipelineModel].stages.last.extractParamMap
  }

  def getHyperParamsAndValidationMetrics: Array[(Double, ParamMap)] = {
    if (hyperParamsAndValidationMetrics == null) {
      val validatorModel = getValidatorModel

      hyperParamsAndValidationMetrics = validatorModel.validationMetrics
        .zip(validatorModel.getEstimatorParamMaps)
        .sortBy(_._1)
    }

    hyperParamsAndValidationMetrics
  }

  def printHyperParamsAndValidationMetrics: Unit = {
    getHyperParamsAndValidationMetrics.foreach { case (metric, params) =>
      println(metric)
      println(params)
      println()
    }
  }

  def getAccuracyOnCrossValidationData: Double = {
    getValidatorModel.validationMetrics.max
  }

  def getAccuracyOnTestData: Double = {
    val validatorModel = getValidatorModel
    val testData = getTrainDataAndTestData._2

    getEvaluator.evaluate(validatorModel.bestModel.transform(testData))
  }

  def decodeOneHot(data: DataFrame): DataFrame = {
    val wildernessCols = (0 until 4).map(i => s"Wilderness_Area_$i").toArray
    val wildernessAssembler = new VectorAssembler().
      setInputCols(wildernessCols).
      setOutputCol("wilderness")
    val unhotUDF = functions.udf((vec: SparseVector) => vec.toArray.indexOf(1.0).toDouble)
    val withWilderness = wildernessAssembler.transform(data).
      drop(wildernessCols:_*).
      withColumn("wilderness", unhotUDF($"wilderness"))

    val soilCols = (0 until 40).map(i => s"Soil_Type_$i").toArray
    val soilAssembler = new VectorAssembler().
      setInputCols(soilCols).
      setOutputCol("soil")
    soilAssembler.transform(withWilderness).
      drop(soilCols:_*).
      withColumn("soil", unhotUDF($"soil"))
  }

  def decodedTrainData: DataFrame = {
    decodeOneHot(getTrainDataAndTestData._1)
  }

  def decodedTestData: DataFrame = {
    decodeOneHot(getTrainDataAndTestData._2)
  }

  def getPipelineDecoded: Pipeline = {
    if (pipelineDecoded == null) {

      val trainData = getTrainDataAndTestData._1
      val decodedData = decodeOneHot(trainData)
      val inputCols = decodedData.columns.filter(_ != "Cover_Type")

      val assembler = new VectorAssembler()
        .setInputCols(inputCols)
        .setOutputCol("featureVector")

      val indexer = new VectorIndexer().
        setMaxCategories(40).
        setInputCol("featureVector").
        setOutputCol("indexedVector")

      pipelineDecoded = new Pipeline().setStages(Array(assembler, indexer, getRandomForestClassifier))
    }

    pipelineDecoded
  }

  def getBestModelFromRandomForest: PipelineModel = {
    val validatorModel = getValidatorModelFromRandomForest

    validatorModel.bestModel.asInstanceOf[PipelineModel]
  }

  def getForestModel: RandomForestClassificationModel = {
    getBestModelFromRandomForest
      .stages
      .last
      .asInstanceOf[RandomForestClassificationModel]
  }

  def getFeatureImportancesFromForestModel: Array[(Double, String)] = {
    val trainData = getTrainDataAndTestData._1
    val decodedData = decodeOneHot(trainData)
    val inputCols = decodedData.columns.filter(_ != "Cover_Type")

    getForestModel.featureImportances.toArray.zip(inputCols).sorted.reverse
  }

  def getPredictionFromBestModel: DataFrame = {
    getBestModelFromRandomForest.transform(decodedTestData.drop("Cover_Type")).select("prediction")
  }

}
