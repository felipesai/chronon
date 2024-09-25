package ai.chronon.quickstart.online

import ai.chronon.online.Fetcher.Request

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object Main {
  def main(args: Array[String]): Unit = {
    val myParams: Map[String, String] = Map("project_id" -> "indigo-computer-272415", "instance_id" -> "data-features")
    val api = new ChrononBigtableOnlineImpl(myParams)
    api.setTimeout(10000000)

    val fetcher = api.buildFetcher(debug=true)

//    val req = Request(name = "crn.txns.gby_payment_method", keys = Map("payment_method_type" -> "card"), atMillis = Some(1726531200000L))
    val req = Request(name = "crn.txns.gby_payment_method", keys = Map("payment_method_type" -> "bank"))
    val responseFuture = fetcher.fetchGroupBys(Seq(req))

    responseFuture.onComplete({
      case Success(listInt) => {
        println(listInt)
        listInt.map(response => println(response.values))
      }
      case Failure(exception) => {
        println(exception.getMessage)
      }
    })
    // Set partition specification if needed
    // fetcher.setPartitionSpec("yyyyMMdd")
  }
}