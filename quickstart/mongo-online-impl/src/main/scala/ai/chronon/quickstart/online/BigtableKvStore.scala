package ai.chronon.quickstart.online

import ai.chronon.online.KVStore
import ai.chronon.online.KVStore._
import com.google.cloud.bigtable.admin.v2.BigtableTableAdminClient
import com.google.cloud.bigtable.admin.v2.models.CreateTableRequest
import com.google.cloud.bigtable.admin.v2.models.GCRules
import com.google.cloud.bigtable.data.v2.BigtableDataClient
import com.google.cloud.bigtable.data.v2.models.{Row, RowMutation, TableId}
import com.google.cloud.bigtable.data.v2.models.Query
import com.google.protobuf.ByteString

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import java.util.Base64
import java.nio.charset.StandardCharsets

/**
 * A KVStore implementation backed by Google Cloud Bigtable.
 */
class BigtableKvStore(projectId: String, instanceId: String) extends KVStore {

  @transient lazy val adminClient: BigtableTableAdminClient = BigtableTableAdminClient.create(projectId, instanceId)

  @transient lazy val dataClient: BigtableDataClient = BigtableDataClient.create(projectId, instanceId)

  override def create(dataset: String): Unit = {
    if (!adminClient.exists(dataset)) {
      val createTableRequest = CreateTableRequest.of(dataset)
        .addFamily(Constants.bigtableColumnFamily, GCRules.GCRULES.maxVersions(1))

      adminClient.createTable(createTableRequest)
    } else {
      throw new IllegalArgumentException(s"Table $dataset already exists")
    }
  }

  override def multiGet(requests: Seq[GetRequest]): Future[Seq[GetResponse]] = {
    val futures = requests.map { request =>
      Future {
        val rowKey = ByteString.copyFrom(request.keyBytes)
        val row: Row = dataClient.readRow(TableId.of(request.dataset), rowKey)
        if (row == null) {
          GetResponse(request, Failure(new NoSuchElementException("Key not found")))
        } else {
          val value = row.getCells(Constants.bigtableColumnFamily, Constants.bigtableValue).get(0).getValue.toByteArray
          GetResponse(request, Try(Seq(TimedValue(value, System.currentTimeMillis()))))
        }
      }
    }
    Future.sequence(futures)
  }

  override def multiPut(putRequests: Seq[PutRequest]): Future[Seq[Boolean]] = {
    val futures = putRequests.map { putRequest =>
      Future {
        val rowKey = new String(putRequest.keyBytes, StandardCharsets.UTF_8)
        val mutation = RowMutation.create(putRequest.dataset, rowKey)
          .setCell(Constants.bigtableColumnFamily, Constants.bigtableValue, new String(putRequest.valueBytes, StandardCharsets.UTF_8))
          .setCell(Constants.bigtableColumnFamily, Constants.bigtableTs, putRequest.tsMillis.getOrElse(0L))

        dataClient.mutateRow(mutation)
        true
      }.recover { case _ => false }
    }
    Future.sequence(futures)
  }

  override def bulkPut(sourceOfflineTable: String, destinationOnlineDataSet: String, partition: String): Unit = {
    // Implementation depends on the bulk insert logic and is not covered here.
  }
}
