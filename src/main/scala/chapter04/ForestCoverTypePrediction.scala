package chapter04

import org.apache.spark.ml.classification.{DecisionTreeClassificationModel, DecisionTreeClassifier}
import org.apache.spark.ml.evaluation.MulticlassClassificationEvaluator
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark.mllib.linalg.Matrix
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}

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

  private var model: DecisionTreeClassificationModel = null

  private var featureImportances: Array[(Double, String)] = null

  private var evaluator: MulticlassClassificationEvaluator = null

  private var predictions: DataFrame = null

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
}