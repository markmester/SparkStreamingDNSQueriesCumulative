package consumer

import kafka.serializer.{DefaultDecoder, StringDecoder}
import org.apache.spark.streaming._
import org.apache.spark.streaming.kafka._
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.log4j.{Level, Logger}

import scala.io.Source
import org.apache.avro.Schema
import org.apache.avro.specific.SpecificDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory

import utilities.AppConfig.{AWSConfig, sparkConfig, redshiftConfig, kafkaConfig}

/**
  * Consumes messages from one or more topics in Kafka and does wordcount.
  * Usage: MyKafkaConsumer <brokers> <topics>
  *   <brokers> is a list of one or more Kafka brokers
  *   <topics> is a list of one or more kafka topics to consume from
  *
  * Example:
  *    $ bin/run-example streaming.MyKafkaConsumer broker1-host:port,broker2-host:port \
  *    topic1,topic2
  *
  * Refs:
  * - http://aseigneurin.github.io/2016/03/04/kafka-spark-avro-producing-and-consuming-avro-messages.html
  * - https://blog.knoldus.com/2016/08/02/scala-kafka-avro-producing-and-consuming-avro-messages/
  *
  * */
object MyKafkaConsumer {

  object AvroUtil
  {
    val schemaString: String = Source.fromURL(getClass.getResource("/schema.avsc")).mkString
    val schema: Schema = new Schema.Parser().parse(schemaString)

    def deserializeMessage(msg: Array[Byte]): GenericRecord = {
      val reader: SpecificDatumReader[GenericRecord] = new SpecificDatumReader[GenericRecord](schema)
      val decoder = DecoderFactory.get.binaryDecoder(msg, null)
      val data: GenericRecord = reader.read(null, decoder)
      data
    }
  }

  // log settings
  Logger.getLogger("org").setLevel(Level.ERROR)
  Logger.getLogger("akka").setLevel(Level.ERROR)
  Logger.getLogger("spark").setLevel(Level.ERROR)


  def main(args: Array[String]): Unit = {
    println(">>> Starting Kafka Consumer...")

    // Create context with X second batch interval
    val sparkConf = new SparkConf()
      .setMaster(sparkConfig.master)
      .setAppName("MyKafkaConsumer")
    sparkConf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")

    val ssc = new StreamingContext(sparkConf, Seconds(sparkConfig.batchWindow))

    // context configs
    ssc.sparkContext.hadoopConfiguration.set("fs.s3n.awsAccessKeyId", AWSConfig.awsAccessKeyId)
    ssc.sparkContext.hadoopConfiguration.set("fs.s3n.awsSecretAccessKey", AWSConfig.awsSecretAccessKey)
    ssc.checkpoint("checkpoints")

    // Create direct kafka stream with brokers and topics
    val topicsSet = kafkaConfig.topics.split(",").toSet
    val kafkaParams = Map[String, String]("metadata.broker.list" -> kafkaConfig.metadataBrokerList)
    val messages = KafkaUtils.createDirectStream[String, Array[Byte], StringDecoder, DefaultDecoder](
      ssc, kafkaParams, topicsSet)

    // Start processing DStreams
    val requestLines = messages.map(_._2)

    // DEBUG -- Prints all received data
    //    requestLines.foreachRDD(rdd => {
    //      rdd.foreach({ avroRecord =>
    //        val data: GenericRecord = AvroUtil.deserializeMessage(avroRecord)
    //
    //        println(s">>> Got data: ${data.get("sites")}")
    //
    //        val sites: Seq[String] = data.get("sites").toString.split(" ")
    //        val r = sites.flatMap(_.split(" ")).map(site => (site, 1))
    //      })
    //    })

    val mappedSites = requestLines.transform {
      rdd =>
        val sitesRDD: RDD[String] = rdd.map { bytes => AvroUtil.deserializeMessage(bytes) }.map { genericRecord: GenericRecord => genericRecord.get("sites").toString }
        sitesRDD.flatMap(_.split(" "))
    }

    // get top visited sites in past 60 seconds
    val topCounts = mappedSites.map(x => (x, 1L)).reduceByKeyAndWindow(_ + _, Seconds(sparkConfig.reduceWindow))
      .map { case (topic, count) => (count, topic) }
      .transform(_.sortByKey(ascending = false))

    // Print popular sites
    topCounts.foreachRDD(rdd => {
      val topList = rdd.take(10)
      println(s"\nPopular sites in last ${sparkConfig.reduceWindow} seconds (${rdd.count()} total):")
      topList.foreach { case (count, site) => println(s"$site ($count visits)") }
      println("\n")
    })

    // redshift connection credentials
    val (url, user, password, tempdir) = (redshiftConfig.jdbcurl, redshiftConfig.user, redshiftConfig.password, redshiftConfig.tempdir)

    // create DataFrame
    topCounts.foreachRDD { rdd =>
      // Get the singleton instance of SparkSession
      val spark = SparkSession.builder.config(rdd.sparkContext.getConf).getOrCreate()
      import spark.implicits._
      // Convert RDD[String] to DataFrame
      val currentSitesDataFrame = rdd.toDF("count","site")

      // create new DataFrames and save to RedShift
      try {
        currentSitesDataFrame.coalesce(4).write // coalesce partitions (default is 200) into only 4 to improve write performance
          // writes data from current window to current sites table
          .format("com.databricks.spark.redshift")
          .option("url", s"$url?user=$user&password=$password")
          .option("dbtable", "sites_current")
          .option("tempdir", tempdir)
          .option("forward_spark_s3_credentials", "true")
          .mode("overwrite")
          .save()

        currentSitesDataFrame.coalesce(4).write
          // writes data from current window to cumulative sites table
          .format("com.databricks.spark.redshift")
          .option("url", s"$url?user=$user&password=$password")
          .option("dbtable", "sites_cumulative")
          .option("tempdir", tempdir)
          .option("forward_spark_s3_credentials", "true")
          .mode("append")
          .save()

        // now we'll reduce the data in the cumulative sites table
        val existingSitesDataFrame = spark.read
          // first load existing cumulative sites table
          .format("com.databricks.spark.redshift")
          .option("url", s"$url?user=$user&password=$password")
          .option("dbtable", "sites_cumulative")
          .option("tempdir", tempdir).option("forward_spark_s3_credentials", "true")
          .load()

        // reduce by key and sum up values
        existingSitesDataFrame.createOrReplaceTempView("existingSitesDataFrame")
        val cumulativeDataFrame = spark.sql("SELECT site, SUM(count) AS count FROM existingSitesDataFrame GROUP BY site")

        // write back to sites_cumulative table
        cumulativeDataFrame.coalesce(4).write
          .format("com.databricks.spark.redshift")
          .option("url", s"$url?user=$user&password=$password")
          .option("dbtable", "sites_cumulative")
          .option("tempdir", tempdir)
          .option("forward_spark_s3_credentials", "true")
          .mode("overwrite")
          .save()

        println(">>> Current site totals...\n")
        currentSitesDataFrame.show()
        println(">>> Cumulative totals...\n")
        cumulativeDataFrame.show()

      } catch {
        // catches NPE in check lifecycle rule configuration (outstanding bug in AWS SDK)
        // This is just a WARN but still annoying when debugging
        // https://github.com/databricks/spark-redshift/pull/357
        case e: java.lang.NullPointerException =>
      }
    }

    requestLines.count().map(cnt => "Received " + cnt + " kafka messages.").print()

    ssc.start()
    ssc.awaitTermination()
  }
}
