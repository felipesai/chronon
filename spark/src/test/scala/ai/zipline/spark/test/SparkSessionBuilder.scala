package ai.zipline.spark.test

import java.io.File

import org.apache.spark.sql.SparkSession

import scala.reflect.io.Directory

object SparkSessionBuilder {

  val namespace = "test_namespace"

  def build(name: String): SparkSession = {
    val warehouseDir = new File("spark-warehouse")
    val metastore_db = new File("metastore_db")
    cleanUp(warehouseDir)
    cleanUp(metastore_db)

    // remove the old warehouse folder
    val spark = SparkSession
      .builder()
      .master("local[*]")
      .config("spark.sql.session.timeZone", "UTC")
      .config("spark.eventLog.enabled", "false")
      .appName(name)
      .getOrCreate()

    spark.sparkContext.setLogLevel("WARN")
    spark.sql(s"CREATE DATABASE IF NOT EXISTS $namespace")
    spark
  }

  /**
    * delete obsolete directory
    */
  def cleanUp(file: File): Unit = {
    if (file.exists() && file.isDirectory) {
      val directory = new Directory(file)
      directory.deleteRecursively()
    }
  }
}
