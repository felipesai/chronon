package ai.chronon.spark.stats

import ai.chronon.aggregator.row.RowAggregator
import ai.chronon.aggregator.row.{RowAggregator, StatsGenerator}
import ai.chronon.spark.Extensions._
import ai.chronon.api.Extensions._
import ai.chronon.api
import ai.chronon.api.Extensions._
import ai.chronon.online.SparkConversions
import ai.chronon.spark.Extensions._
import ai.chronon.spark.KvRdd
import com.yahoo.memory.Memory
import com.yahoo.sketches.kll.KllFloatsSketch
import org.apache.spark.sql.functions.{col, lit}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{Column, DataFrame, functions}

import java.util
import scala.util.{ScalaVersionSpecificCollectionsConverter, Try}

import ai.chronon.spark.{KvRdd, TimedKvRdd}
import com.yahoo.memory.Memory
import com.yahoo.sketches.kll.KllFloatsSketch
import org.apache.spark.sql.types._
import org.apache.spark.sql.functions.{col, lit}
import org.apache.spark.sql.{Column, DataFrame, functions}

import scala.util.Try

class StatsCompute(inputDf: DataFrame, keys: Seq[String], name: String) extends Serializable {

  private val noKeysDf: DataFrame = inputDf.select(
    inputDf.columns
      .filter(colName => !keys.contains(colName))
      .map(colName => new Column(colName)): _*)

  val timeColumns =
    if (inputDf.columns.contains(api.Constants.TimeColumn)) Seq(api.Constants.TimeColumn, api.Constants.PartitionColumn)
    else Seq(api.Constants.PartitionColumn)
  val metrics = StatsGenerator.buildMetrics(SparkConversions.toChrononSchema(noKeysDf.schema))
  lazy val selectedDf: DataFrame = noKeysDf
    .select(timeColumns.map(col) ++ metrics.map(m => m.expression match {
      case StatsGenerator.InputTransform.IsNull => functions.col(m.name).isNull
      case StatsGenerator.InputTransform.Raw => functions.col(m.name)
      case StatsGenerator.InputTransform.One => functions.lit(true)
    }): _*)
    .toDF(timeColumns ++ metrics.map(m => s"${m.prefix}${m.name}"): _*)

  /** Given a summary Dataframe that computed the stats. Add derived data (example: null rate, median, etc) */
  def addDerivedMetrics(df: DataFrame, aggregator: RowAggregator): DataFrame = {
    val nullColumns = df.columns.filter(p => p.startsWith(StatsGenerator.nullPrefix))
    val withNullRatesDF = nullColumns.foldLeft(df) { (tmpDf, column) =>
      tmpDf.withColumn(
        s"${StatsGenerator.nullRatePrefix}${column.stripPrefix(StatsGenerator.nullPrefix)}",
        tmpDf.col(column) / tmpDf.col(Seq(StatsGenerator.totalColumn, api.Operation.COUNT).mkString("_"))
      )
    }

    val percentiles = aggregator.aggregationParts.filter(_.operation == api.Operation.APPROX_PERCENTILE)
    val percentileColumns = percentiles.map(_.outputColumnName)
    import org.apache.spark.sql.functions.udf
    val percentileFinalizerUdf = udf((s: Array[Byte]) =>
      Try(
        KllFloatsSketch
          .heapify(Memory.wrap(s))
          .getQuantiles(StatsGenerator.finalizedPercentiles.toArray)
          .zip(StatsGenerator.finalizedPercentiles)
          .map(f => f._2.toString -> f._1.toString)
          .toMap).toOption)
    val addedPercentilesDf = percentileColumns.foldLeft(withNullRatesDF) { (tmpDf, column) =>
      tmpDf.withColumn(s"${column}_finalized", percentileFinalizerUdf(col(column)))
    }
    if (selectedDf.columns.contains(api.Constants.TimeColumn)) {
      addedPercentilesDf.withTimeBasedColumn(api.Constants.PartitionColumn)
    } else {
      addedPercentilesDf
    }
  }

  /** Navigate the dataframe and compute statistics partitioned by date stamp
    *
    * Partitioned by day version of the normalized summary. Useful for scheduling a job that computes daily stats.
    * Returns a KvRdd to be able to be pushed into a KvStore for fetching and merging. As well as a dataframe for
    * storing in hive.
    *
    * For entity on the left we use daily partition as the key. For events we bucket by timeBucketMinutes (def. 1 hr)
    * Since the stats are mergeable coarser granularities can be obtained through fetcher merging.
    */
  def dailySummary(aggregator: RowAggregator, sample: Double = 1.0, timeBucketMinutes: Long = 60): TimedKvRdd = {
    val partitionIdx = selectedDf.schema.fieldIndex(api.Constants.PartitionColumn)
    val bucketMs = timeBucketMinutes * 1000 * 60
    val tsIdx =
      if (selectedDf.columns.contains(api.Constants.TimeColumn)) selectedDf.schema.fieldIndex(api.Constants.TimeColumn)
      else -1
    val hourlyCompute = tsIdx >= 0 && timeBucketMinutes > 0
    val keyName: Any = name
    val result = selectedDf
      .sample(sample)
      .rdd
      .map(SparkConversions.toChrononRow(_, tsIdx))
      .keyBy(row => if (hourlyCompute) ((row.ts / bucketMs) * bucketMs) else api.Constants.Partition.epochMillis(row.getAs[String](partitionIdx)))
      .aggregateByKey(aggregator.init)(seqOp = aggregator.updateWithReturn, combOp = aggregator.merge)
      .mapValues(aggregator.normalize(_))
      .map { case (k, v) => (Array(keyName), v, k) } // To use KvRdd
    implicit val sparkSession = inputDf.sparkSession
    TimedKvRdd(
      result,
      SparkConversions.fromChrononSchema(api.Constants.StatsKeySchema),
      SparkConversions.fromChrononSchema(aggregator.irSchema)
    )
  }
}
