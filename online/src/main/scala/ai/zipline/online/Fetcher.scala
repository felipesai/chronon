package ai.zipline.online

import ai.zipline.aggregator.row.ColumnAggregator
import ai.zipline.aggregator.windowing.{FinalBatchIr, SawtoothOnlineAggregator}
import ai.zipline.api.Constants.ZiplineMetadataKey
import ai.zipline.api.Extensions.JoinOps
import ai.zipline.api.{Row, _}
import ai.zipline.online.CompatParColls.Converters._
import ai.zipline.online.Fetcher._
import ai.zipline.online.KVStore.{GetRequest, GetResponse, TimedValue}
import com.google.gson.Gson
import org.apache.avro.generic.GenericRecord

import java.io.{PrintWriter, StringWriter}
import java.util
import java.util.function.Consumer
import scala.collection.JavaConverters.asScalaIteratorConverter
import scala.collection.mutable
import scala.collection.parallel.ExecutionContextTaskSupport
import scala.concurrent.Future
import scala.util.hashing.MurmurHash3
import scala.util.{Failure, Success, Try}

object Fetcher {
  case class Request(name: String, keys: Map[String, AnyRef], atMillis: Option[Long] = None)
  case class Response(request: Request, values: Try[Map[String, AnyRef]])
}

class BaseFetcher(kvStore: KVStore,
                  metaDataSet: String = ZiplineMetadataKey,
                  timeoutMillis: Long = 10000,
                  debug: Boolean = false)
    extends MetadataStore(kvStore, metaDataSet, timeoutMillis) {

  private case class GroupByRequestMeta(
      groupByServingInfoParsed: GroupByServingInfoParsed,
      batchRequest: GetRequest,
      streamingRequestOpt: Option[GetRequest],
      endTs: Option[Long]
  )

  // a groupBy request is split into batchRequest and optionally a streamingRequest
  private def constructGroupByResponse(batchResponse: Try[TimedValue],
                                       streamingResponsesOpt: Option[Seq[TimedValue]],
                                       servingInfo: GroupByServingInfoParsed,
                                       queryTimeMs: Long,
                                       startTimeMs: Long,
                                       context: Metrics.Context): Map[String, AnyRef] = {
    val groupByContext = FetcherMetrics.getGroupByContext(servingInfo, Some(context))
    val batchContext = groupByContext.asBatch
    // batch request has only one value per key.
    batchResponse.foreach { value =>
      FetcherMetrics.reportDataFreshness(value.millis, batchContext)
      FetcherMetrics.reportResponseBytesSize(value.bytes.length, batchContext)
    }
    FetcherMetrics.reportResponseNumRows(batchResponse.map(_ => 1).getOrElse(0), batchContext)

    // bulk upload didn't remove an older batch value - so we manually discard
    val batchBytes: Array[Byte] = batchResponse
      .filter(_.millis >= servingInfo.batchEndTsMillis)
      .map(_.bytes)
      .getOrElse(null)
    val responseMap: Map[String, AnyRef] = if (servingInfo.groupBy.aggregations == null) { // no-agg
      servingInfo.selectedCodec.decodeMap(batchBytes)
    } else if (streamingResponsesOpt.isEmpty) { // snapshot accurate
      servingInfo.outputCodec.decodeMap(batchBytes)
    } else { // temporal accurate
      val streamingResponses = streamingResponsesOpt.get
      val mutations: Boolean = servingInfo.groupByOps.dataModel == DataModel.Entities
      val aggregator: SawtoothOnlineAggregator = servingInfo.aggregator
      val selectedCodec = servingInfo.groupByOps.dataModel match {
        case DataModel.Events   => servingInfo.valueAvroCodec
        case DataModel.Entities => servingInfo.mutationValueAvroCodec
      }
      val streamingRows: Iterator[Row] = streamingResponses.iterator
        .filter(tVal => tVal.millis >= servingInfo.batchEndTsMillis)
        .map(tVal => selectedCodec.decodeRow(tVal.bytes, tVal.millis, mutations))
      if (streamingResponses.nonEmpty) {
        val streamingContext = groupByContext.asStreaming
        // report streaming metrics.
        FetcherMetrics.reportDataFreshness(streamingResponses.maxBy(_.millis).millis - startTimeMs, streamingContext)
        FetcherMetrics.reportResponseBytesSize(streamingResponses.iterator.map(_.bytes.length).sum, streamingContext)
        FetcherMetrics.reportResponseNumRows(streamingResponses.length, streamingContext)
      }
      val batchIr = toBatchIr(batchBytes, servingInfo)
      val output = aggregator.lambdaAggregateFinalized(batchIr, streamingRows, queryTimeMs, mutations)
      servingInfo.outputCodec.fieldNames.zip(output.map(_.asInstanceOf[AnyRef])).toMap
    }
    FetcherMetrics.reportLatency(System.currentTimeMillis() - startTimeMs, groupByContext)
    responseMap
  }

  private def updateServingInfo(batchEndTs: Option[Long],
                                groupByServingInfo: GroupByServingInfoParsed): GroupByServingInfoParsed = {
    val name = groupByServingInfo.groupBy.metaData.name
    if (batchEndTs.exists(_ > groupByServingInfo.batchEndTsMillis)) {
      println(s"""$name's value's batch timestamp of ${batchEndTs.get} is
           |ahead of schema timestamp of ${groupByServingInfo.batchEndTsMillis}.
           |Forcing an update of schema.""".stripMargin)
      getGroupByServingInfo
        .force(name)
        .recover {
          case ex: Throwable =>
            println(
              s"Couldn't update GroupByServingInfo of $name due to ${ex.getMessage}. Proceeding with the old one.")
            ex.printStackTrace()
            groupByServingInfo
        }
        .get
    } else {
      groupByServingInfo
    }
  }

  // 1. fetches GroupByServingInfo
  // 2. encodes keys as keyAvroSchema
  // 3. Based on accuracy, fetches streaming + batch data and aggregates further.
  // 4. Finally converted to outputSchema
  def fetchGroupBys(requests: scala.collection.Seq[Request],
                    contextOption: Option[Metrics.Context] = None): Future[scala.collection.Seq[Response]] = {
    val context = contextOption.getOrElse(
      if (requests.iterator.toSet.size == 1) {
        Metrics.Context(method = "fetchGroupBys").withGroupBy(requests.head.name)
      } else {
        Metrics.Context(method = "fetchGroupBys")
      })
    val startTimeMs = System.currentTimeMillis()
    // split a groupBy level request into its kvStore level requests
    val groupByRequestToKvRequest: Seq[(Request, Try[GroupByRequestMeta])] = requests.iterator.map { request =>
      val groupByName = request.name
      val groupByRequestMetaTry: Try[GroupByRequestMeta] = getGroupByServingInfo(groupByName)
        .map { groupByServingInfo =>
          var keyBytes: Array[Byte] = null
          try {
            keyBytes = groupByServingInfo.keyCodec.encode(request.keys)
          } catch {
            case ex: Exception =>
              val castedKeys = groupByServingInfo.keyZiplineSchema.fields.map {
                case StructField(name, typ) => name -> ColumnAggregator.castTo(request.keys.getOrElse(name, null), typ)
              }.toMap
              try {
                keyBytes = groupByServingInfo.keyCodec.encode(castedKeys)
              } catch {
                case exInner: Exception =>
                  exInner.addSuppressed(ex)
                  throw new RuntimeException("Couldn't encode request keys or casted keys", exInner)
              }
          }
          val batchRequest = GetRequest(keyBytes, groupByServingInfo.groupByOps.batchDataset)
          val streamingRequestOpt = groupByServingInfo.groupByOps.inferredAccuracy match {
            // fetch batch(ir) and streaming(input) and aggregate
            case Accuracy.TEMPORAL =>
              Some(
                GetRequest(keyBytes,
                           groupByServingInfo.groupByOps.streamingDataset,
                           Some(groupByServingInfo.batchEndTsMillis)))
            // no further aggregation is required - the value in KvStore is good as is
            case Accuracy.SNAPSHOT => None
          }
          GroupByRequestMeta(groupByServingInfo, batchRequest, streamingRequestOpt, request.atMillis)
        }
      request -> groupByRequestMetaTry
    }.toSeq
    val allRequests: Seq[GetRequest] = groupByRequestToKvRequest.flatMap {
      case (_, Success(GroupByRequestMeta(_, batchRequest, streamingRequestOpt, _))) =>
        Some(batchRequest) ++ streamingRequestOpt
      case _ => Seq.empty
    }

    val kvStartMs = System.currentTimeMillis()
    val kvResponseFuture: Future[Seq[GetResponse]] = kvStore.multiGet(allRequests)

    val validContext = context.join != null || context.groupBy != null
    if (validContext) FetcherMetrics.reportRequest(context)
    // map all the kv store responses back to groupBy level responses
    kvResponseFuture
      .map { responsesFuture: Seq[GetResponse] =>
        if (validContext) FetcherMetrics.reportKvLatency(System.currentTimeMillis() - kvStartMs, context)
        val responsesMap: Map[GetRequest, Try[Seq[TimedValue]]] = responsesFuture.iterator.map { response =>
          response.request -> response.values
        }.toMap
        if (validContext) FetcherMetrics.reportRequestBatchSize(responsesMap.keys.size, context)
        if (validContext) FetcherMetrics.reportResponseBytesSize(Option(responsesMap.values.iterator.filter(_.isSuccess).flatMap(_.get.map(_.bytes.length.toLong)).sum).getOrElse(0L), context)
        // Heaviest compute is decoding bytes and merging them - so we parallelize
        val requestParFanout = groupByRequestToKvRequest.par
        requestParFanout.tasksupport = new ExecutionContextTaskSupport(executionContext)
        val responses: Seq[Response] = requestParFanout.map {
          case (request, requestMetaTry) =>
            val responseMapTry = requestMetaTry.map { requestMeta =>
              val GroupByRequestMeta(groupByServingInfo, batchRequest, streamingRequestOpt, _) = requestMeta
              // pick the batch version with highest timestamp
              val batchResponseTry = responsesMap
                .getOrElse(batchRequest,
                           Failure(new IllegalStateException(
                             s"Couldn't find corresponding response for $batchRequest in responseMap")))
                .map(_.maxBy(_.millis))
              val batchEndTs = batchResponseTry.map { timedVal => Some(timedVal.millis) }.getOrElse(None)
              val streamingResponsesOpt =
                streamingRequestOpt.map(responsesMap.getOrElse(_, Success(Seq.empty)).getOrElse(Seq.empty))
              val queryTs = request.atMillis.getOrElse(System.currentTimeMillis())
              try {
                constructGroupByResponse(batchResponseTry,
                                         streamingResponsesOpt,
                                         updateServingInfo(batchEndTs, groupByServingInfo),
                                         queryTs,
                                         startTimeMs,
                                         context)
              } catch {
                case ex: Exception =>
                  ex.printStackTrace()
                  throw ex
              }
            }
            responseMapTry.failed.map(ex => reportFailure(requests, context.withGroupBy, ex))
            Response(request, responseMapTry)
        }.toList
        responses
      }
  }

  def toBatchIr(bytes: Array[Byte], gbInfo: GroupByServingInfoParsed): FinalBatchIr = {
    if (bytes == null) return null
    val batchRecord =
      AvroConversions
        .toZiplineRow(gbInfo.irCodec.decode(bytes), gbInfo.irZiplineSchema)
        .asInstanceOf[Array[Any]]
    val collapsed = gbInfo.aggregator.windowedAggregator.denormalize(batchRecord(0).asInstanceOf[Array[Any]])
    val tailHops = batchRecord(1)
      .asInstanceOf[util.ArrayList[Any]]
      .iterator()
      .asScala
      .map(
        _.asInstanceOf[util.ArrayList[Any]]
          .iterator()
          .asScala
          .map(hop => gbInfo.aggregator.baseAggregator.denormalizeInPlace(hop.asInstanceOf[Array[Any]]))
          .toArray)
      .toArray
    FinalBatchIr(collapsed, tailHops)
  }

  def tuplesToMap[K, V](tuples: Seq[(K, V)]): Map[K, Seq[V]] =
    tuples.groupBy(_._1).map { case (k, v) => (k, v.map(_._2)) }.toMap

  private case class PrefixedRequest(prefix: String, request: Request)

  def fetchJoin(requests: scala.collection.Seq[Request]): Future[scala.collection.Seq[Response]] = {
    val context = Metrics.Context(method = "fetchJoin")
    val startTimeMs = System.currentTimeMillis()
    // convert join requests to groupBy requests
    val joinDecomposed: Seq[(Request, Try[(Seq[PrefixedRequest], Metrics.Context)])] =
      requests.iterator.map { request =>
        val joinTry = getJoinConf(request.name)
        val decomposedTry = joinTry.map { join =>
          val prefixedRequests = join.joinPartOps.map { part =>
            val groupByName = part.groupBy.getMetaData.getName
            val rightKeys = part.leftToRight.map { case (leftKey, rightKey) => rightKey -> request.keys(leftKey) }
            PrefixedRequest(part.fullPrefix, Request(groupByName, rightKeys, request.atMillis))
          }
          val joinContext = context.withJoin(request.name).withProduction(join.isProduction).withTeam(join.team)
          (prefixedRequests, joinContext)
        }
        request -> decomposedTry
      }.toSeq

    // dedup duplicate requests
    val uniqueRequests = joinDecomposed.iterator.flatMap(_._2.map(_._1).getOrElse(Seq.empty)).map(_.request).toSet
    val groupByResponsesFuture = if (requests.iterator.toSet.size == 1) {
      val joinContext = context.withJoin(requests.head.name)
      FetcherMetrics.reportRequest(joinContext)
      fetchGroupBys(uniqueRequests.toSeq, Some(joinContext))
    } else {
      // we are not count qps here
      fetchGroupBys(uniqueRequests.toSeq)
    }

    // re-attach groupBy responses to join
    groupByResponsesFuture
      .map { groupByResponses =>
        val responseMap = groupByResponses.iterator.map { response => response.request -> response.values }.toMap
        val responses = joinDecomposed.iterator.map {
          case (joinRequest, decomposedRequestsTry) =>
            val joinValuesTry = decomposedRequestsTry.map {
              case (groupByRequestsWithPrefix, joinContext) =>
                val result = groupByRequestsWithPrefix.iterator.flatMap {
                  case PrefixedRequest(prefix, groupByRequest) =>
                    responseMap
                      .getOrElse(groupByRequest,
                                 Failure(new IllegalStateException(
                                   s"Couldn't find a groupBy response for $groupByRequest in response map")))
                      .map { valueMap =>
                        if (valueMap != null) {
                          valueMap.map { case (aggName, aggValue) => prefix + "_" + aggName -> aggValue }
                        } else {
                          Map.empty[String, AnyRef]
                        }
                      } // prefix feature names
                      .recover { // capture exception as a key
                        case ex: Throwable =>
                          val stringWriter = new StringWriter()
                          val printWriter = new PrintWriter(stringWriter)
                          ex.printStackTrace(printWriter)
                          val trace = stringWriter.toString
                          if (debug || Math.random() < 0.001) {
                            println(s"Failed to fetch $groupByRequest with \n$trace")
                          }
                          Map(groupByRequest.name + "_exception" -> trace)
                      }
                      .get
                }.toMap
                FetcherMetrics.reportLatency(System.currentTimeMillis() - startTimeMs, joinContext)
                result
            }
            Response(joinRequest, joinValuesTry)
        }.toSeq
        // report latency of each join as the maximum of the latency of the joins in the request batch.
        responses.foreach { resp =>
          FetcherMetrics.reportFinalLatency(System.currentTimeMillis() - startTimeMs,
                                            context.withJoin(resp.request.name))
        }
        responses
      }
      .recover {
        case e: Exception =>
          reportFailure(requests, context.withJoin, e)
          throw e
      }
  }

  private def reportFailure(requests: scala.collection.Seq[Request],
                            withTag: String => Metrics.Context,
                            e: Throwable): Unit = {
    requests.foreach { req =>
      val context = withTag(req.name)
      FetcherMetrics.reportFailure(e, context)
    }
  }
}

case class JoinCodec(conf: JoinOps,
                     keySchema: StructType,
                     valueSchema: StructType,
                     keyCodec: AvroCodec,
                     valueCodec: AvroCodec)
    extends Serializable {
  val keys: Array[String] = keySchema.fields.iterator.map(_.name).toArray
  val values: Array[String] = valueSchema.fields.iterator.map(_.name).toArray

  val keyFields: Array[StructField] = keySchema.fields
  val valueFields: Array[StructField] = valueSchema.fields
  val timeFields: Array[StructField] = Array(
    StructField("ts", LongType),
    StructField("ds", StringType)
  )
  val outputFields: Array[StructField] = keyFields ++ valueFields ++ timeFields
}

// BaseFetcher + Logging
class Fetcher(kvStore: KVStore,
              metaDataSet: String = ZiplineMetadataKey,
              timeoutMillis: Long = 10000,
              logFunc: Consumer[LoggableResponse] = null,
              debug: Boolean = false)
    extends BaseFetcher(kvStore, metaDataSet, timeoutMillis, debug) {

  // key and value schemas
  lazy val getJoinCodecs = new TTLCache[String, Try[JoinCodec]]({ joinName: String =>
    val joinConfTry = getJoinConf(joinName)
    val keyFields = new mutable.ListBuffer[StructField]
    val valueFields = new mutable.ListBuffer[StructField]
    joinConfTry.map {
      joinConf =>
        joinConf.joinPartOps.foreach {
          joinPart =>
            val servingInfoTry = getGroupByServingInfo(joinPart.groupBy.metaData.getName)
            servingInfoTry
              .map {
                servingInfo =>
                  val keySchema = servingInfo.keyCodec.ziplineSchema.asInstanceOf[StructType]
                  joinPart.leftToRight
                    .mapValues(right => keySchema.fields.find(_.name == right).get.fieldType)
                    .foreach {
                      case (name, dType) =>
                        val keyField = StructField(name, dType)
                        if (!keyFields.contains(keyField)) {
                          keyFields.append(keyField)
                        }
                    }

                  val baseValueSchema = if (joinPart.groupBy.aggregations == null) {
                    servingInfo.selectedZiplineSchema
                  } else {
                    servingInfo.outputZiplineSchema
                  }
                  baseValueSchema.fields.foreach { sf =>
                    valueFields.append(StructField(joinPart.fullPrefix + "_" + sf.name, sf.fieldType))
                  }
              }
        }

        val keySchema = StructType(s"${joinName}_key", keyFields.toArray)
        val keyCodec = AvroCodec.of(AvroConversions.fromZiplineSchema(keySchema).toString)
        val valueSchema = StructType(s"${joinName}_value", valueFields.toArray)
        val valueCodec = AvroCodec.of(AvroConversions.fromZiplineSchema(valueSchema).toString)
        JoinCodec(joinConf, keySchema, valueSchema, keyCodec, valueCodec)
    }
  })

  override def fetchJoin(requests: scala.collection.Seq[Request]): Future[scala.collection.Seq[Response]] = {
    val ts = System.currentTimeMillis()
    super
      .fetchJoin(requests)
      .map(_.map { resp =>
        val joinCodecTry = getJoinCodecs(resp.request.name)
        val loggingTry = joinCodecTry.map {
          enc =>
            val metaData = enc.conf.join.metaData
            val samplePercent = if (metaData.isSetSamplePercent) metaData.getSamplePercent else 0
            val hash = if (samplePercent > 0) Math.abs(MurmurHash3.orderedHash(resp.request.keys.values)) else -1
            if ((hash > 0) && ((hash % (100 * 1000)) <= (samplePercent * 1000))) {
              val joinName = resp.request.name
              if (debug) {
                println(s"Passed ${resp.request.keys} : $hash : ${hash % 100000}: $samplePercent")
                val gson = new Gson()
                println(s"""Sampled join fetch
                     |Key Map: ${resp.request.keys}
                     |Value Map: [${resp.values.map {
                  _.map { case (k, v) => s"$k -> ${gson.toJson(v)}" }.mkString(", ")
                }}]
                     |""".stripMargin)
              }
              val keyArr = enc.keys.map(resp.request.keys.getOrElse(_, null))
              val keys = AvroConversions.fromZiplineRow(keyArr, enc.keySchema).asInstanceOf[GenericRecord]
              val keyBytes = enc.keyCodec.encodeBinary(keys)
              val valueBytes = resp.values
                .map { valueMap =>
                  val valueArr = enc.values.map(valueMap.getOrElse(_, null))
                  val valueRecord =
                    AvroConversions.fromZiplineRow(valueArr, enc.valueSchema).asInstanceOf[GenericRecord]
                  enc.valueCodec.encodeBinary(valueRecord)
                }
                .getOrElse(null)
              val loggableResponse =
                LoggableResponse(keyBytes, valueBytes, joinName, resp.request.atMillis.getOrElse(ts))
              if (logFunc != null)
                logFunc.accept(loggableResponse)
            }
        }
        if (loggingTry.isFailure && (debug || Math.random() < 0.01)) {
          loggingTry.failed.get.printStackTrace()
        }
        resp
      })
  }

  override def fetchGroupBys(requests: scala.collection.Seq[Request],
                             contextOption: Option[Metrics.Context]): Future[scala.collection.Seq[Response]] = {
    super.fetchGroupBys(requests, contextOption)
  }
}
