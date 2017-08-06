import Dependencies._
import sbt.Keys.libraryDependencies

val sparkVersion = "2.0.0"

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.11.8",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "sparkRedshiftRedash",
    libraryDependencies += scalaTest % Test,
    libraryDependencies += "org.apache.spark" %% "spark-core" % sparkVersion,
    libraryDependencies += "org.apache.spark" %% "spark-streaming" % sparkVersion,
    libraryDependencies += "org.apache.spark" %% "spark-sql" % sparkVersion,// % "provided",
    libraryDependencies += "org.apache.spark" %% "spark-streaming-kafka-0-8" % sparkVersion,
    libraryDependencies += "org.apache.avro"  %  "avro"  %  "1.7.7",
    libraryDependencies += "com.databricks" %% "spark-redshift" % "3.0.0-preview1",
    libraryDependencies += "com.amazonaws" % "aws-java-sdk-core" % "1.11.163",
    libraryDependencies += "com.amazonaws" % "aws-java-sdk-s3" % "1.11.166",
    libraryDependencies += "com.typesafe" % "config" % "1.3.1"
  )

assemblyMergeStrategy in assembly := {
  case PathList("org","aopalliance", xs @ _*) => MergeStrategy.last
  case PathList("javax", "inject", xs @ _*) => MergeStrategy.last
  case PathList("javax", "servlet", xs @ _*) => MergeStrategy.last
  case PathList("javax", "activation", xs @ _*) => MergeStrategy.last
  case PathList("org", "apache", xs @ _*) => MergeStrategy.last
  case PathList("com", "google", xs @ _*) => MergeStrategy.last
  case PathList("com", "esotericsoftware", xs @ _*) => MergeStrategy.last
  case PathList("com", "codahale", xs @ _*) => MergeStrategy.last
  case PathList("com", "yammer", xs @ _*) => MergeStrategy.last
  case "about.html" => MergeStrategy.rename
  case "META-INF/ECLIPSEF.RSA" => MergeStrategy.last
  case "META-INF/mailcap" => MergeStrategy.last
  case "META-INF/mimetypes.default" => MergeStrategy.last
  case "plugin.properties" => MergeStrategy.last
  case "log4j.properties" => MergeStrategy.last
  case "application.conf" => MergeStrategy.concat
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

// define entry class
mainClass in assembly := Some("consumer.MyKafkaConsumer")

assemblyJarName in assembly := "consumer.jar"

cancelable in Global := true