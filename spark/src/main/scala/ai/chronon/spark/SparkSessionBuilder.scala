package ai.chronon.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.SPARK_VERSION

import java.io.File
import java.nio.file.Files
import java.util.logging.Logger
import scala.util.Properties

object SparkSessionBuilder {

  val wareHousePathPrefix = "spark-warehouse"
  
  def build(name: String, local: Boolean = false, localWarehouseLocation: Option[String] = None, additionalConfig: Option[Map[String, String]] = None): SparkSession = {
    if (local) {
      //required to run spark locally with hive support enabled - for sbt test
      System.setSecurityManager(null)
    }
    val userName = Properties.userName

    var baseBuilder = SparkSession
      .builder()
      .appName(name)
      .enableHiveSupport()
      .config("spark.sql.session.timeZone", "UTC")
      //otherwise overwrite will delete ALL partitions, not just the ones it touches
      .config("spark.sql.sources.partitionOverwriteMode", "dynamic")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.kryo.registrator", "ai.chronon.spark.ChrononKryoRegistrator")
      .config("spark.kryoserializer.buffer.max", "2000m")
      .config("spark.kryo.referenceTracking", "false")
      .config("hive.exec.dynamic.partition", "true")
      .config("hive.exec.dynamic.partition.mode", "nonstrict")
      .config("spark.sql.catalogImplementation", "hive")
      .config("spark.hadoop.hive.exec.max.dynamic.partitions", 30000)
      .config("spark.sql.legacy.timeParserPolicy", "LEGACY")

    additionalConfig.foreach{configMap =>
      configMap.foreach{config => baseBuilder = baseBuilder.config(config._1, config._2)}
    }



    if (SPARK_VERSION.startsWith("2")) {
      // Otherwise files left from deleting the table with the same name result in test failures
      baseBuilder.config("spark.sql.legacy.allowCreatingManagedTableUsingNonemptyLocation", "true")
    }

    val builder = if (local) {
      val tmpPath = Files.createTempDirectory(wareHousePathPrefix)
      tmpPath.toFile.deleteOnExit()
      baseBuilder
      // use all threads - or the tests will be slow
        .master("local[*]")
        .config("spark.kryo.registrationRequired", s"${localWarehouseLocation.isEmpty}")
        .config("spark.local.dir", s"/tmp/$userName/$name")
        .config("spark.hadoop.javax.jdo.option.ConnectionURL", "jdbc:derby:memory:myInMemDB;create=true")
        .config("spark.sql.warehouse.dir", tmpPath.toAbsolutePath.toString)
    } else {
      // hive jars need to be available on classpath - no needed for local testing
      val warehouseDir = new File(wareHousePathPrefix)
      baseBuilder
        .config("spark.sql.warehouse.dir", warehouseDir.getAbsolutePath)
    }
    val spark = builder.getOrCreate()
    // disable log spam
    spark.sparkContext.setLogLevel("ERROR")
    Logger.getLogger("parquet.hadoop").setLevel(java.util.logging.Level.SEVERE)
    spark
  }

  def buildStreaming(local: Boolean): SparkSession = {
    val userName = Properties.userName
    val baseBuilder = SparkSession
      .builder()
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      .config("spark.kryo.registrator", "ai.chronon.spark.ChrononKryoRegistrator")
      .config("spark.kryoserializer.buffer.max", "2000m")
      .config("spark.kryo.referenceTracking", "false")
      .config("spark.sql.legacy.timeParserPolicy", "LEGACY")

    val builder = if (local) {
      baseBuilder
      // use all threads - or the tests will be slow
        .master("local[*]")
        .config("spark.local.dir", s"/tmp/$userName/chronon-spark-streaming")
        .config("spark.kryo.registrationRequired", "true")
    } else {
      baseBuilder
    }
    val spark = builder.getOrCreate()
    // disable log spam
    spark.sparkContext.setLogLevel("ERROR")
    Logger.getLogger("parquet.hadoop").setLevel(java.util.logging.Level.SEVERE)
    spark
  }
}
