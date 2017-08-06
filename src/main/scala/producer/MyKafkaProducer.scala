package producer

import java.io.ByteArrayOutputStream
import java.util

import sys.process._
import scala.collection.mutable.ListBuffer
import scala.io.Source
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.log4j.{Level, Logger}
import org.apache.avro.Schema
import org.apache.avro.Schema.Parser
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.{BinaryEncoder, EncoderFactory}
import org.apache.avro.specific.SpecificDatumWriter

import utilities.AppConfig.{producerConfig, kafkaConfig}

object MyKafkaProducer {

  // log settings
  Logger.getLogger("org").setLevel(Level.ERROR)
  Logger.getLogger("akka").setLevel(Level.ERROR)
  Logger.getLogger("spark").setLevel(Level.ERROR)

  def main(args: Array[String]): Unit = {
    println(">>> Starting Kafka Producer...")

    val tShark_version = "which tshark" !

    if (tShark_version != 0) {
      println("tShark does not appear to be installed! Exiting...")
      System.exit(1)
    }

    // Zookeeper connection properties
    val props = new util.HashMap[String, Object]()
    props.put(ProducerConfig.LINGER_MS_CONFIG, "1")
    props.put(ProducerConfig.RETRIES_CONFIG, "2")
    props.put(ProducerConfig.ACKS_CONFIG, "all")
    props.put(ProducerConfig.CLIENT_ID_CONFIG, util.UUID.randomUUID().toString)
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfig.metadataBrokerList)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer")

    val producer = new KafkaProducer[String, Array[Byte]](props)

//    val producer = new Producer[String, Array[Byte]](new ProducerConfig(props))

    //Read avro schema file and
    val schema: Schema = new Parser().parse(Source.fromURL(getClass.getResource("/schema.avsc")).mkString)

    // Send some messages
    while (true) {
      // get batch
      val batch = get_batch(producerConfig.minBatchSize)
      println("Got batch: ", batch)

      // send batch
      var i = 0
      var str = new ListBuffer[String]()

      for (entry <- batch) {
        str += entry
        i += 1

        if (i % producerConfig.wordsPerMessage == 0 || i == batch.length) {
          // Create avro generic record object
          val record: GenericRecord = new GenericData.Record(schema)

          //Put data in that generic record
          val format = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX")
          val datetime = format.format(new java.util.Date())
          record.put("batch_id", java.util.UUID.randomUUID.toString)
          record.put("sites", str.toList.mkString(" "))
          record.put("timestamp", datetime)

          // Serialize generic record into byte array
          val writer = new SpecificDatumWriter[GenericRecord](schema)
          val out = new ByteArrayOutputStream()
          val encoder: BinaryEncoder = EncoderFactory.get().binaryEncoder(out, null)
          writer.write(record, encoder)
          encoder.flush()
          out.close()
          val serializedBytes: Array[Byte] = out.toByteArray

          // send message
          val queueMessage = new ProducerRecord[String, Array[Byte]](kafkaConfig.topics, serializedBytes)
          producer.send(queueMessage)

          str.clear()

          println(s"Sent message: $queueMessage")
          Thread.sleep(producerConfig.messagesPerSec * 1000)
        }
      }
    }
  }


  def get_batch(batchsize: Int): Seq[String] = {
    var batch = new ListBuffer[String]()

    while (batch.length < batchsize) {

      // grab some DNS traffic
      val cmd = Seq("tshark", "-I", "-i", "en0", "-Y", "dns", "-c", "1000")

      val out = cmd.!!
        .split("\n")
        .toSeq
        .map(_.trim)
        .filter(_ != "")
        .filter(out => out.contains("Standard query response"))

      // filter out dns queries
      val pattern = """Standard query response\s\S*\s\S*\s(\S*\.\S*) """.r
      for(line <- out) {
        val site = pattern.findAllIn(line)
        if (site.nonEmpty) {
          batch += site.group(1)
        }
      }
    }

    //return
    batch.toList
  }

}
