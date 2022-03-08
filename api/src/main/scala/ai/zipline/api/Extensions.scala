package ai.zipline.api

import ai.zipline.api.DataModel._
import ai.zipline.api.Operation._

import scala.jdk.CollectionConverters._
import scala.collection.mutable

object Extensions {

  implicit class TimeUnitOps(timeUnit: TimeUnit) {
    def str: String =
      timeUnit match {
        case TimeUnit.HOURS => "h"
        case TimeUnit.DAYS  => "d"
      }

    def millis: Long =
      timeUnit match {
        case TimeUnit.HOURS => 3600 * 1000
        case TimeUnit.DAYS  => 24 * 3600 * 1000
      }
  }

  implicit class OperationOps(operation: Operation) {
    def isSimple: Boolean =
      operation match {
        case Operation.FIRST | Operation.LAST | Operation.LAST_K | Operation.FIRST_K => false
        case _                                                                       => true
      }
  }

  implicit class WindowOps(window: Window) {
    private def unbounded: Boolean = window.length == Int.MaxValue || window.length < 0

    def str: String =
      if (unbounded) "unbounded" else s"${window.length}${window.timeUnit.str}"

    def suffix: String =
      if (unbounded) "" else s"_${window.length}${window.timeUnit.str}"

    def millis: Long = window.length.toLong * window.timeUnit.millis
  }

  object WindowUtils {
    val Unbounded: Window = new Window(Int.MaxValue, TimeUnit.DAYS)
    val Hour: Window = new Window(1, TimeUnit.HOURS)
    val Day: Window = new Window(1, TimeUnit.DAYS)
    private val SecondMillis: Long = 1000
    private val Minute: Long = 60 * SecondMillis
    val FiveMinutes: Long = 5 * Minute

    def millisToString(millis: Long): String = {
      if (millis % Day.millis == 0) {
        new Window((millis / Day.millis).toInt, TimeUnit.DAYS).str
      } else if (millis % Hour.millis == 0) {
        new Window((millis / Hour.millis).toInt, TimeUnit.HOURS).str
      } else if (millis % Minute == 0) {
        s"${millis / Minute}mins"
      } else if (millis % SecondMillis == 0) {
        s"${millis / SecondMillis}secs"
      } else {
        s"${millis}ms"
      }
    }
  }

  implicit class MetadataOps(metaData: MetaData) {
    def cleanName: String = metaData.name.sanitize

    def copyForVersioningComparison: MetaData = {
      // Changing name results in column rename, therefore schema change, other metadata changes don't effect output table
      val newMetaData = new MetaData()
      newMetaData.setName(metaData.name)
      newMetaData
    }

    def tableProps: Map[String, String] = Option(metaData.tableProperties).map(_.asScala.toMap).orNull

    def nameToFilePath: String = metaData.name.replaceFirst("\\.", "/")
  }

  // one per output column - so single window
  // not exposed to users
  implicit class AggregationPartOps(aggregationPart: AggregationPart) {

    def getInt(arg: String): Int = {
      val argOpt = Option(aggregationPart.argMap).flatMap(_.asScala.get(arg))
      require(
        argOpt.isDefined,
        s"$arg needs to be specified in the `argMap` for ${aggregationPart.operation} type"
      )
      argOpt.get.toInt
    }

    private def opSuffix =
      aggregationPart.operation match {
        case LAST_K   => s"last${getInt("k")}"
        case FIRST_K  => s"first${getInt("k")}"
        case TOP_K    => s"top${getInt("k")}"
        case BOTTOM_K => s"bottom${getInt("k")}"
        case other    => other.toString.toLowerCase
      }

    private def bucketSuffix = Option(aggregationPart.bucket).map("_by_" + _).getOrElse("")

    def outputColumnName =
      s"${aggregationPart.inputColumn}_$opSuffix${aggregationPart.window.suffix}${bucketSuffix}"
  }

  implicit class AggregationOps(aggregation: Aggregation) {

    // one agg part per bucket per window
    // unspecified windows are translated to one unbounded window
    def unpack: Seq[AggregationPart] = {
      val windows = Option(aggregation.windows).map(_.asScala).getOrElse(Seq(WindowUtils.Unbounded)).toSeq
      val buckets = Option(aggregation.buckets).map(_.asScala).getOrElse(Seq(null)).toSeq
      for (bucket <- buckets; window <- windows) yield {
        Builders.AggregationPart(aggregation.operation,
                                 aggregation.inputColumn,
                                 window,
                                 Option(aggregation.argMap).map(_.asScala.toMap).orNull,
                                 bucket)
      }
    }

    // one agg part per bucket
    // ignoring the windowing
    def unWindowed: Seq[AggregationPart] = {
      val buckets = Option(aggregation.buckets).map(_.asScala).getOrElse(Seq(null)).toSeq
      for (bucket <- buckets) yield {
        Builders.AggregationPart(aggregation.operation,
                                 aggregation.inputColumn,
                                 WindowUtils.Unbounded,
                                 Option(aggregation.argMap).map(_.asScala.toMap).orNull,
                                 bucket)
      }
    }
  }

  case class WindowMapping(aggregationPart: AggregationPart, baseIrIndex: Int)
  case class UnpackedAggregations(perBucket: Array[AggregationPart], perWindow: Array[WindowMapping])

  object UnpackedAggregations {
    def from(aggregations: Seq[Aggregation]): UnpackedAggregations = {
      var counter = 0
      val perBucket = new mutable.ArrayBuffer[AggregationPart]
      val perWindow = new mutable.ArrayBuffer[WindowMapping]
      aggregations.foreach { agg =>
        val buckets = Option(agg.buckets).map(_.asScala).getOrElse(Seq(null))
        val windows = Option(agg.windows).map(_.asScala).getOrElse(Seq(WindowUtils.Unbounded))
        for (bucket <- buckets) {
          perBucket += Builders.AggregationPart(agg.operation,
                                                agg.inputColumn,
                                                WindowUtils.Unbounded,
                                                Option(agg.argMap).map(_.asScala.toMap).orNull,
                                                bucket)
          for (window <- windows) {
            perWindow += WindowMapping(Builders.AggregationPart(agg.operation,
                                                                agg.inputColumn,
                                                                window,
                                                                Option(agg.argMap).map(_.asScala.toMap).orNull,
                                                                bucket),
                                       counter)
          }
          counter += 1
        }
      }
      UnpackedAggregations(perBucket = perBucket.toArray, perWindow = perWindow.toArray)
    }
  }

  implicit class AggregationsOps(aggregations: Seq[Aggregation]) {
    def hasTimedAggregations: Boolean =
      aggregations.exists(_.operation match {
        case LAST_K | FIRST_K | LAST | FIRST => true
        case _                               => false
      })

    def hasWindows: Boolean = aggregations.exists(_.windows != null)
    def needsTimestamp: Boolean = hasWindows || hasTimedAggregations
    def allWindowsOpt: Option[Seq[Window]] =
      Option(aggregations).map { aggs =>
        aggs.flatMap { agg =>
          Option(agg.windows).map(_.asScala).getOrElse(Seq(null))
        }
      }
  }

  implicit class SourceOps(source: Source) {
    def dataModel: DataModel = {
      assert(source.isSetEntities || source.isSetEvents, "Source type is not specified")
      if (source.isSetEntities) Entities else Events
    }

    def query: Query = {
      if (source.isSetEntities) source.getEntities.query else source.getEvents.query
    }

    def table: String = {
      if (source.isSetEntities) source.getEntities.getSnapshotTable else source.getEvents.getTable
    }

    def topic: String = {
      if (source.isSetEntities) source.getEntities.getMutationTopic else source.getEvents.getTopic
    }

    /**
      * If the streaming topic has additional args. Parse them to be used by streamingImpl.
      * Example: kafkatopic/schema=deserializationClass/version=2.0/host=host_url/port=9999
      *  -> Map(schema -> deserializationClass, version -> 2.0, host -> host_url, port -> 9999)
      */
    def topicTokens: Map[String, String] = {
      source.topic
        .split("/")
        .drop(1)
        .map { tok =>
          val tokens = tok.split("=", 2)
          tokens(0) -> tokens(1)
        }
        .toMap
    }

    /**
      * Topic without kwargs
      */
    def cleanTopic: String = source.topic.split("/").head

    def copyForVersioningComparison: Source = {
      // Makes a copy of the source and unsets date fields, used to compute equality on sources while ignoring these fields
      val newSource = source.deepCopy()
      val query = newSource.query
      query.unsetEndPartition()
      query.unsetStartPartition()
      newSource
    }
  }

  implicit class GroupByOps(groupBy: GroupBy) extends GroupBy(groupBy) {
    def maxWindow: Option[Window] = {
      val allWindowsOpt = Option(AggregationsOps(groupBy.aggregations.asScala.toSeq)).flatMap(_.allWindowsOpt)
      allWindowsOpt.flatMap { windows =>
        if (windows.contains(null)) None
        else Some(windows.maxBy(_.millis))
      }
    }

    def dataModel: DataModel = {
      val models = groupBy.sources.asScala.map(_.dataModel)
      assert(models.distinct.length == 1,
             s"All source of the groupBy: ${groupBy.metaData.name} " +
               s"should be of the same type. Either 'Events' or 'Entities'")
      models.head
    }

    lazy val inferredAccuracy: Accuracy = inferredAccuracyImpl

    private def inferredAccuracyImpl: Accuracy = {
      // if user specified something - respect it
      if (groupBy.accuracy != null) return groupBy.accuracy
      // if a topic is specified - then treat it as temporally accurate
      val validTopics = groupBy.sources.asScala.map(_.topic).filter(_ != null)
      if (validTopics.nonEmpty) return Accuracy.TEMPORAL
      // the default accuracy for events is temporal and entities is snapshot
      if (groupBy.dataModel == DataModel.Events) Accuracy.TEMPORAL else Accuracy.SNAPSHOT
    }

    def setups: Seq[String] = {
      val sources = groupBy.sources.asScala
      sources.flatMap(_.query.setupsSeq).toSeq.distinct
    }

    def copyForVersioningComparison: GroupBy = {
      val newGroupBy = groupBy.deepCopy()
      newGroupBy.setMetaData(newGroupBy.metaData.copyForVersioningComparison)
      newGroupBy
    }

    lazy val batchDataset: String = s"${groupBy.metaData.cleanName.toUpperCase()}_BATCH"
    lazy val streamingDataset: String = s"${groupBy.metaData.cleanName.toUpperCase()}_STREAMING"
    def kvTable: String = s"${groupBy.metaData.outputNamespace}.${groupBy.metaData.cleanName}_upload"

    def streamingSource: Option[Source] = groupBy.sources.asScala.find(_.topic != null)

    def buildStreamingQuery: String = {
      assert(streamingSource.isDefined, "You should probably define a topic in one of your sources")
      val query = streamingSource.get.query
      val selects = Option(query.selects).map(_.asScala.toMap).orNull
      val timeColumn = Option(query.timeColumn).getOrElse(Constants.TimeColumn)
      val fillIfAbsent = if (selects == null) null else Map(Constants.TimeColumn -> timeColumn)
      val keys = groupBy.getKeyColumns.asScala

      val baseWheres = Option(query.wheres).map(_.asScala).getOrElse(Seq.empty[String])
      val keyWhereOption =
        Option(selects)
          .map { selectsMap =>
            keys
              .map(key => s"(${selectsMap(key)} is NOT NULL)")
              .mkString(" OR ")
          }
      val timeWheres = Seq(s"$timeColumn is NOT NULL")

      QueryUtils.build(
        selects,
        Constants.StreamingInputTable,
        baseWheres.toSeq ++ timeWheres.toSeq ++ keyWhereOption.toSeq,
        fillIfAbsent = fillIfAbsent
      )
    }

    def aggregationInputs: Array[String] =
      groupBy.aggregations.asScala
        .flatMap(agg => Option(agg.buckets).map(_.asScala).getOrElse(Seq.empty) :+ agg.inputColumn)
        .distinct
        .toArray
  }

  implicit class StringOps(string: String) {
    def sanitize: String = Option(string).map(_.replaceAll("[^a-zA-Z0-9_]", "_")).orNull
  }

  implicit class JoinPartOps(joinPart: JoinPart) extends JoinPart(joinPart) {
    lazy val fullPrefix = (Option(prefix) ++ Some(groupBy.getMetaData.cleanName)).mkString("_")
    lazy val leftToRight: Map[String, String] = rightToLeft.map { case (key, value) => value -> key }

    def rightToLeft: Map[String, String] = {
      val rightToRight = joinPart.groupBy.keyColumns.asScala.map { key => key -> key }.toMap
      Option(joinPart.keyMapping)
        .map { leftToRight =>
          val rToL = leftToRight.asScala.map {
            case (left, right) => right -> left
          }.toMap
          rightToRight ++ rToL
        }
        .getOrElse(rightToRight)
    }

    def copyForVersioningComparison: JoinPart = {
      val newJoinPart = joinPart.deepCopy()
      newJoinPart.setGroupBy(newJoinPart.groupBy.copyForVersioningComparison)
      newJoinPart
    }
  }

  implicit class JoinOps(join: Join) {
    // all keys on left
    def leftKeyCols: Array[String] = {
      join.joinParts.asScala
        .flatMap { _.rightToLeft.values }
        .toSet
        .toArray
    }

    def isProduction: Boolean = {
      join.getMetaData.isProduction
    }

    def team: String = {
      join.getMetaData.getTeam
    }

    private def generateSkewFilterSql(key: String, values: Seq[String]): String = {
      val nulls = Seq("null", "Null", "NULL")
      val nonNullFilters = Some(s"$key NOT IN (${values.filterNot(nulls.contains).mkString(", ")})")
      val nullFilters = if (values.exists(nulls.contains)) Some(s"$key IS NOT NULL") else None
      (nonNullFilters ++ nullFilters).mkString(" AND ")
    }

    // TODO: validate that non keys are not specified in - join.skewKeys
    def skewFilter(keys: Option[Seq[String]] = None, joiner: String = " OR "): Option[String] = {
      Option(join.skewKeys).map { jmap =>
        val result = jmap.asScala
          .filterKeys(key => keys.forall { _.contains(key) })
          .map {
            case (leftKey, values) =>
              assert(
                leftKeyCols.contains(leftKey),
                s"specified skew filter for $leftKey is not used as a key in any join part. " +
                  s"Please specify key columns in skew filters: [${leftKeyCols.mkString(", ")}]"
              )
              generateSkewFilterSql(leftKey, values.asScala.toSeq)
          }
          .filter(_.nonEmpty)
          .mkString(joiner)
        println(s"Generated join left side skew filter:\n    $result")
        result
      }
    }

    def partSkewFilter(joinPart: JoinPart, joiner: String = " OR "): Option[String] = {
      Option(join.skewKeys).map { jmap =>
        val result = jmap.asScala
          .flatMap {
            case (leftKey, values) =>
              val replacedKey = Option(joinPart.keyMapping)
                .map { _.asScala.getOrElse(leftKey, leftKey) }
                .getOrElse(leftKey)
              if (joinPart.groupBy.keyColumns.contains(replacedKey))
                Some(generateSkewFilterSql(replacedKey, values.asScala.toSeq))
              else None
          }
          .filter(_.nonEmpty)
          .mkString(joiner)
        println(s"Generated join part skew filter for ${joinPart.groupBy.metaData.name}:\n    $result")
        result
      }
    }

    def setups: Seq[String] = (join.left.query.setupsSeq ++ join.joinParts.asScala.flatMap(_.groupBy.setups)).distinct

    def copyForVersioningComparison(): Join = {
      // When we compare previous-run join to current join to detect changes requiring table migration
      // these are the fields that should be checked to not have accidental recomputes
      val newJoin = join.deepCopy()
      newJoin.setLeft(newJoin.left.copyForVersioningComparison)
      newJoin.unsetJoinParts()
      // Opting not to use metaData.copyForVersioningComparison here because if somehow a name change results
      // in a table existing for the new name (with no other metadata change), it is more than likely intentional
      newJoin.unsetMetaData()
      newJoin
    }

    lazy val joinPartOps: Seq[JoinPartOps] = join.joinParts.asScala.toSeq.map(new JoinPartOps(_))
  }

  implicit class StringsOps(strs: Iterable[String]) {
    def pretty: String = {
      if (strs.nonEmpty)
        "\n    " + strs.mkString(",\n    ") + "\n"
      else
        ""
    }
  }

  implicit class QueryOps(query: Query) {
    def setupsSeq: Seq[String] = {
      Option(query.setups).map(_.asScala.toSeq).getOrElse(Seq.empty)
    }
  }
}
