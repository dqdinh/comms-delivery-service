package servicetest

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executors

import cakesolutions.kafka.KafkaConsumer
import com.whisk.docker.impl.dockerjava.DockerKitDockerJava
import com.whisk.docker.{
  ContainerLink,
  DockerCommandExecutor,
  DockerContainer,
  DockerContainerState,
  DockerReadyChecker,
  VolumeMapping
}
import kafka.admin.AdminUtils
import kafka.utils.ZkUtils
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.serialization.StringDeserializer
import org.scalatest.concurrent.{Eventually, PatienceConfiguration, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import org.scalatest._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

case class LogOutputAndWaitForLineThatContains(str: String, containerName: String) extends DockerReadyChecker {

  val outputDir = Paths.get("target", "integration-test-logs")

  val outputFile = outputDir.resolve(s"$containerName-${LocalDateTime.now().toString}.log")

  override def apply(container: DockerContainerState)(implicit docker: DockerCommandExecutor,
                                                      ec: ExecutionContext): Future[Boolean] = {
    println(s"Waiting for container [$containerName] to become ready. Logs are being streamed to $outputFile.")

    for {
      id <- container.id
      _ <- docker.withLogStreamLinesRequirement(id, withErr = true) { line =>
        val lineWithLineEnding = if (line.endsWith("\n")) line else line + "\n"
        Files.write(outputFile,
                    lineWithLineEnding.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND)
        val ready = line.contains(str)
        if (ready)
          println(s"Container [$containerName] is ready")
        ready
      }(docker, ec)
    } yield {
      true
    }
  }
}

trait DockerIntegrationTest
    extends DockerKitDockerJava
    with ScalaFutures
    with TestSuite
    with BeforeAndAfterAll
    with Eventually {

  override val StartContainersTimeout = 5.minutes

  override implicit lazy val dockerExecutionContext: ExecutionContext = {
    // using Math.max to prevent unexpected zero length of docker containers
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(Math.max(1, dockerContainers.length * 4)))
  }

  val hostIpAddress = {
    import sys.process._
    "./get_ip_address.sh".!!.trim
  }

  // TODO currently no way to set the memory limit on docker containers. Need to make a PR to add support to docker-it-scala. I've checked that the spotify client supports it.

  val topicNames = Seq(
    "comms.triggered.v3",
    "comms.orchestrated.email.v3",
    "comms.orchestrated.sms.v2",
    "comms.composed.email.v2",
    "comms.composed.sms.v2",
    "comms.progressed.email.v2",
    "comms.progressed.sms.v2",
    "comms.link.clicked.v2",
    "comms.failed.v2",
    "comms.cancellation.requested.v2",
    "comms.failed.cancellation.v2",
    "comms.cancelled.v2"
  )

  val zookeeper = DockerContainer("confluentinc/cp-zookeeper:3.1.1", name = Some("aivenZookeeper"))
    .withPorts(32182 -> Some(32182))
    .withEnv(
      "ZOOKEEPER_CLIENT_PORT=32182",
      "ZOOKEEPER_TICK_TIME=2000",
      "KAFKA_HEAP_OPTS=-Xmx256M -Xms128M"
    )
    .withReadyChecker(LogOutputAndWaitForLineThatContains("binding to port", "aivenZookeeper"))

  val kafka = {
    // create each topic with 1 partition and replication factor 1
    val createTopicsString = topicNames.map(t => s"$t:1:1").mkString(",")
    val lastTopicName      = topicNames.last

    DockerContainer("wurstmeister/kafka:0.10.2.1", name = Some("aivenKafka"))
      .withPorts(29093 -> Some(29093))
      .withLinks(ContainerLink(zookeeper, "aivenZookeeper"))
      .withEnv(
        "KAFKA_BROKER_ID=2",
        "KAFKA_ZOOKEEPER_CONNECT=aivenZookeeper:32182",
        "KAFKA_PORT=29093",
        "KAFKA_ADVERTISED_PORT=29093",
        s"KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$hostIpAddress:29093",
        "KAFKA_HEAP_OPTS=-Xmx256M -Xms128M",
        s"KAFKA_CREATE_TOPICS=$createTopicsString"
      )
      .withReadyChecker(LogOutputAndWaitForLineThatContains(s"""Created topic "$lastTopicName"""", "aivenKafka")) // Note: this needs to be the last topic in the list of topics above
  }

  val schemaRegistry = DockerContainer("confluentinc/cp-schema-registry:3.2.2", name = Some("schema-registry"))
    .withPorts(8081 -> Some(8081))
    .withLinks(
      ContainerLink(zookeeper, "aivenZookeeper"),
      ContainerLink(kafka, "aivenKafka")
    )
    .withEnv(
      "SCHEMA_REGISTRY_HOST_NAME=schema-registry",
      "SCHEMA_REGISTRY_KAFKASTORE_CONNECTION_URL=aivenZookeeper:32182",
      s"SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS=PLAINTEXT://$hostIpAddress:29093"
    )
    .withReadyChecker(LogOutputAndWaitForLineThatContains("Server started, listening for requests", "schema-registry"))

  val fakes3 = DockerContainer("lphoward/fake-s3:latest", name = Some("fakes3"))
    .withPorts(4569 -> Some(4569))
    .withReadyChecker(LogOutputAndWaitForLineThatContains("WEBrick::HTTPServer#start", "fakes3"))

  val mockServers = DockerContainer("jamesdbloom/mockserver", name = Some("mockservers"))
    .withPorts(1080 -> Some(1080))
    .withReadyChecker(LogOutputAndWaitForLineThatContains("MockServer proxy started", "mockservers"))

  val fakes3ssl = DockerContainer("cbachich/ssl-proxy:latest", name = Some("fakes3ssl"))
    .withPorts(443 -> Some(443))
    .withLinks(ContainerLink(fakes3, "proxyapp"))
    .withEnv(
      "PORT=443",
      "TARGET_PORT=4569"
    )
    .withReadyChecker(LogOutputAndWaitForLineThatContains("Starting Proxy: 443", "fakes3ssl"))

  val deliveryService = {
    val envVars = List(
      Some("ENV=LOCAL"),
      Some("KAFKA_HOSTS_LEGACY=legacyKafka:29092"),
      Some("KAFKA_HOSTS_AIVEN=aivenKafka:29093"),
      Some("LOCAL_DYNAMO=http://dynamodb:8000"),
      Some("DOCKER_COMPOSE=true"),
      Some("SCHEMA_REGISTRY_URL=http://schema-registry:8081"),
      Some("MAILGUN_DOMAIN=mailgun@email.com"),
      Some("MAILGUN_API_KEY=my_super_secret_api_key"),
      Some("MAILGUN_HOST=http://api.mailgun.net:1080"),
      Some("TWILIO_HOST=http://api.twilio.com:1080"),
      sys.env.get("AWS_ACCESS_KEY_ID").map(envVar => s"AWS_ACCESS_KEY_ID=$envVar"),
      sys.env.get("AWS_ACCOUNT_ID").map(envVar => s"AWS_ACCOUNT_ID=$envVar"),
      sys.env.get("AWS_SECRET_ACCESS_KEY").map(envVar => s"AWS_SECRET_ACCESS_KEY=$envVar")
    ).flatten

    val awsAccountId = sys.env.getOrElse(
      "AWS_ACCOUNT_ID",
      sys.error("Environment variable AWS_ACCOUNT_ID must be set in order to run the integration tests"))

    DockerContainer(s"$awsAccountId.dkr.ecr.eu-west-1.amazonaws.com/delivery-service:0.1-SNAPSHOT",
                    name = Some("delivery-service"))
      .withLinks(
        ContainerLink(kafka, "aivenKafka"),
        ContainerLink(schemaRegistry, "schema-registry"),
        ContainerLink(fakes3ssl, "ovo-comms-audit.s3-eu-west-1.amazonaws.com"),
        ContainerLink(mockServers, "api.mailgun.net"),
        ContainerLink(mockServers, "api.twilio.com")
      )
      .withEnv(envVars: _*)
      .withVolumes(List(VolumeMapping(host = s"${sys.env("HOME")}/.aws", container = "/sbin/.aws"))) // share AWS creds so that credstash works
      .withReadyChecker(LogOutputAndWaitForLineThatContains("Delivery Service started", "delivery-service")) // TODO check topics/consumers in the app and output a log when properly ready
  }

  override def dockerContainers =
    List(zookeeper, kafka, schemaRegistry, fakes3, fakes3ssl, mockServers, deliveryService)

  lazy val zkUtils = ZkUtils("localhost:32182", 30000, 5000, isZkSecurityEnabled = false)

  def checkKafkaTopic(topic: String, zkUtils: ZkUtils, description: String) = {
    println(s"Checking we can retrieve metadata about topic $topic on $description ZooKeeper")
    eventually {
      val topicInfo = AdminUtils.fetchTopicMetadataFromZk(topic, zkUtils)
      val error     = topicInfo.error()
      if (Errors.NONE != topicInfo.error()) {
        fail(s"${topicInfo.topic()} encountered an error: $error")
      }
    }
    println("Yes we can!")
  }

  def checkCanConsumeFromKafkaTopic(topic: String, bootstrapServers: String, description: String) {
    println(s"Checking we can consume from topic $topic on $description Kafka")
    import cakesolutions.kafka.KafkaConsumer._
    import scala.collection.JavaConverters._
    val consumer = KafkaConsumer(
      Conf[String, String](Map("bootstrap.servers" -> bootstrapServers, "group.id" -> UUID.randomUUID().toString),
                           new StringDeserializer,
                           new StringDeserializer))
    consumer.assign(List(new TopicPartition(topic, 0)).asJava)
    eventually(PatienceConfiguration.Timeout(Span(20, Seconds))) {
      consumer.poll(200)
    }
    println("Yes we can!")
  }

  abstract override def beforeAll(): Unit = {
    super.beforeAll()

    import scala.collection.JavaConverters._
    val logDir = Paths.get("target", "integration-test-logs")

    if (Files.exists(logDir))
      Files.list(logDir).iterator().asScala.foreach(Files.delete)
    else
      Files.createDirectories(logDir)

    println(
      "Starting a whole bunch of Docker containers. This could take a few minutes, but I promise it'll be worth the wait!")
    startAllOrFail()

    topicNames.foreach(t => checkKafkaTopic(t, zkUtils, "Aiven"))
    topicNames.foreach(t => checkCanConsumeFromKafkaTopic(t, "localhost:29093", "Aiven"))
  }

  abstract override def afterAll(): Unit = {
    Try {
      zkUtils.close()
    }

    stopAllQuietly()

    super.afterAll()
  }

}
