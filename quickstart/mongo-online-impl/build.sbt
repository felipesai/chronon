ThisBuild / scalaVersion     := "2.12.12"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "ai.chronon"
ThisBuild / organizationName := "Chronon"

lazy val root = (project in file("."))
  .settings(
    name := "mongo-online-impl",
    libraryDependencies ++= Seq(
      "ai.chronon" %% "api" % "0.0.57",
      "ai.chronon" %% "online" % "0.0.57",
      "org.mongodb.spark" %% "mongo-spark-connector" % "10.2.1", // Batch upload + structured streaming
      "org.mongodb.scala" %% "mongo-scala-driver" % "4.8.1",    // Fetching
          "ch.qos.logback" % "logback-classic" % "1.2.3",
          "org.slf4j" % "slf4j-api" % "1.7.32",
      "com.google.cloud" % "google-cloud-bigtable" % "2.43.0"
    ),
    dependencyOverrides ++= Seq(
      "com.fasterxml.jackson.module" % "jackson-module-scala_2.13" % "2.14.2",
    ),
  )

ThisBuild / assemblyMergeStrategy := {
  case PathList("META-INF", "MANIFEST.MF") => MergeStrategy.discard
  case PathList("META-INF", _ @_*)         => MergeStrategy.filterDistinctLines
  case "plugin.xml"                        => MergeStrategy.last
  case PathList("com", "fasterxml", _ @_*) => MergeStrategy.last
  case PathList("com", "google", _ @_*)    => MergeStrategy.last
  case _                                   => MergeStrategy.first
}
exportJars := true
