package utilities

import com.typesafe.config.ConfigFactory
/**
  * Created by markmester on 7/26/17.
  */
object AppConfig {
  private val config =  ConfigFactory.load()
  private lazy val root = config.getConfig("app")

  object  AWSConfig {
    private val awsconfig = root.getConfig("aws")

    lazy val awsAccessKeyId: String = awsconfig.getString("awsAccessKeyId")
    lazy val awsSecretAccessKey: String = awsconfig.getString("awsSecretAccessKey")
  }

  object  kafkaConfig {
    private val kafkaconfig = root.getConfig("kafka")

    lazy val metadataBrokerList: String = kafkaconfig.getString("metadataBrokerList")
    lazy val topics: String = kafkaconfig.getString("topics")
  }

  object  producerConfig {
    private val producerconfig = root.getConfig("producer")

    lazy val minBatchSize: Int = producerconfig.getInt("minBatchSize")
    lazy val messagesPerSec: Int = producerconfig.getInt("messagesPerSec")
    lazy val wordsPerMessage: Int = producerconfig.getInt("wordsPerMessage")
  }

  object sparkConfig {
    private val sparkconfig = root.getConfig("spark")

    lazy val reduceWindow: Int = sparkconfig.getInt("reduceWindow")
    lazy val batchWindow: Int = sparkconfig.getInt("batchWindow")
    lazy val master: String = sparkconfig.getString("master")
  }

  object redshiftConfig {
    private val redshiftconfig = root.getConfig("redshift")

    lazy val jdbcHostname: String = redshiftconfig.getString("jdbcHostname")
    lazy val jdbcPort: Int = redshiftconfig.getInt("jdbcPort")
    lazy val jdbcDatabase: String = redshiftconfig.getString("jdbcDatabase")
    lazy val user: String = redshiftconfig.getString("user")
    lazy val password: String = redshiftconfig.getString("password")
    lazy val tempdir: String = redshiftconfig.getString("tempdir")
    lazy val jdbcurl: String = s"jdbc:redshift://$jdbcHostname:$jdbcPort/$jdbcDatabase"
  }
}
