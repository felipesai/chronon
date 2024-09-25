package ai.chronon.quickstart.online

import ai.chronon.online.{Api, ExternalSourceRegistry, GroupByServingInfoParsed, KVStore, LoggableResponse, StreamDecoder}
import com.google.cloud.bigtable.data.v2.BigtableDataClient
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.{ExecutionContext, Future}
import com.google.cloud.bigtable.data.v2.models.RowMutation

import scala.util.{Failure, Success}
import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext.Implicits.global

class ChrononBigtableOnlineImpl(userConf: Map[String, String]) extends Api(userConf) {

  @transient lazy val registry: ExternalSourceRegistry = new ExternalSourceRegistry()

  @transient val logger: Logger = LoggerFactory.getLogger("ChrononBigtableOnlineImpl")

  // Initialize Bigtable admin and data clients using projectId and instanceId from user configuration.
  @transient lazy val projectId: String = userConf("project_id")
  @transient lazy val instanceId: String = userConf("instance_id")

  @transient lazy val bigtableDataClient: BigtableDataClient = BigtableDataClient.create(projectId, instanceId)

  // Override the method to instantiate BigtableKvStore instead of MongoKvStore
  override def genKvStore: KVStore = new BigtableKvStore(projectId, instanceId)

  // Override streamDecoder method as in original implementation
  override def streamDecoder(groupByServingInfoParsed: GroupByServingInfoParsed): StreamDecoder =
    new QuickstartMutationDecoder(groupByServingInfoParsed)

  // Override the logResponse method to log responses in Bigtable instead of MongoDB
  override def logResponse(resp: LoggableResponse): Unit = {
    val mutation = RowMutation.create(Constants.bigtableLogTable, resp.joinName)
      .setCell("log_data", Constants.bigtableKey, new String(resp.keyBytes, StandardCharsets.UTF_8))
      .setCell("log_data", "schemaHash", Option(resp.schemaHash).getOrElse("SCHEMA_PUBLISHED"))
      .setCell("log_data", Constants.bigtableValue, new String(resp.valueBytes, StandardCharsets.UTF_8))
      .setCell("log_data", "atMillis", resp.tsMillis)
      .setCell("log_data", "ts", System.currentTimeMillis())

    val future = Future {
      bigtableDataClient.mutateRow(mutation)
      logger.info(s"Logged response for joinName ${resp.joinName} into Bigtable")
    }
    future.onComplete {
      case Success(_) => logger.info("Log inserted successfully into Bigtable")
      case Failure(e) => logger.error(s"Failed to log response into Bigtable: ${e.getMessage}")
    }
  }

  override def externalRegistry: ExternalSourceRegistry = registry
}
