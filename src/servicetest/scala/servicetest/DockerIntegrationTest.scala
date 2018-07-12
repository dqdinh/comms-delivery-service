package servicetest

import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.Executors

import cakesolutions.kafka.KafkaConsumer
import cakesolutions.kafka.KafkaConsumer.Conf
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.jaxrs.JerseyDockerCmdExecFactory
import com.ovoenergy.comms.dockertestkit.DockerContainerExtensions
import com.ovoenergy.comms.helpers.Kafka
import com.ovoenergy.delivery.service.util.LocalDynamoDb
import com.typesafe.config.ConfigFactory
import com.whisk.docker.impl.dockerjava.{Docker, DockerJavaExecutorFactory, DockerKitDockerJava}
import com.whisk.docker.{
  ContainerLink,
  DockerCommandExecutor,
  DockerContainer,
  DockerContainerState,
  DockerFactory,
  DockerReadyChecker,
  LogLineReceiver,
  VolumeMapping
}
import kafka.admin.AdminUtils
import kafka.utils.ZkUtils
import org.apache.commons.io.input.{Tailer, TailerListenerAdapter}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.serialization.StringDeserializer
import org.scalatest._
import org.scalatest.concurrent.{Eventually, PatienceConfiguration, ScalaFutures}
import org.scalatest.time.{Seconds, Span}
import servicetest.dynamo.DynamoTesting

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.Try

trait DockerIntegrationTest
    extends DockerKitDockerJava
    with DynamoTesting
    with ScalaFutures
    with TestSuite
    with BeforeAndAfterAll
    with DockerContainerExtensions
    with Eventually { self =>

  def kafkaEndpoint: String = s"$hostIp:$DefaultKafkaPort"

  def legacyKafkaEndpoint: String = s"$hostIp:$DefaultLegacyKafkaPort"

  def schemaRegistryEndpoint = s"http://$hostIp:$DefaultSchemaRegistryPort"

  implicit val config           = ConfigFactory.load("servicetest.conf")
  val TopicNames                = Kafka.aiven.kafkaConfig.topics.toList.map(_._2)
  val DynamoTableName           = "comms-events"
  val DefaultDynamoDbPort       = 8000
  val DefaultKafkaPort          = 29093
  val DefaultZookeeperPort      = 32182
  val DefaultLegacyKafkaPort    = 29094
  val DefaultSchemaRegistryPort = 8081
  val ComposerHttpPort          = 8080
  val MockserverPort            = 1080

  override val StartContainersTimeout = 5.minutes
  override val StopContainersTimeout  = 1.minute

  override implicit lazy val dockerExecutionContext: ExecutionContext = {
    // using Math.max to prevent unexpected zero length of docker containers
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(Math.max(1, dockerContainers.length * 4)))
  }

  override implicit val dockerFactory: DockerFactory = new DockerJavaExecutorFactory(
    new Docker(
      config = DefaultDockerClientConfig.createDefaultConfigBuilder().build(),
      factory = new JerseyDockerCmdExecFactory()
      // increase connection pool size so we can tail the logs of all containers
        .withMaxTotalConnections(100)
        .withMaxPerRouteConnections(20)
    )
  )

  lazy val hostIp = NetworkInterface.getNetworkInterfaces.asScala
    .filter(x => x.isUp && !x.isLoopback)
    .flatMap(_.getInterfaceAddresses.asScala)
    .map(_.getAddress)
    .find(_.isSiteLocalAddress)
    .fold(throw new RuntimeException("Local ip address not found"))(_.getHostAddress)

  lazy val mockServers = {
    DockerContainer("jamesdbloom/mockserver:mockserver-3.12", name = Some("mockservers"))
      .withPorts(MockserverPort -> Some(MockserverPort))
      .withLogWritingAndReadyChecker("MockServer proxy started", "mockservers")
  }

  lazy val zookeeperContainer = DockerContainer("confluentinc/cp-zookeeper:3.3.1", name = Some("zookeeper"))
    .withPorts(DefaultZookeeperPort -> Some(DefaultZookeeperPort))
    .withEnv(
      s"ZOOKEEPER_CLIENT_PORT=$DefaultZookeeperPort",
      "ZOOKEEPER_TICK_TIME=2000",
      "KAFKA_HEAP_OPTS=-Xmx256M -Xms128M"
    )
    .withLogWritingAndReadyChecker("binding to port", "zookeeper")

  lazy val kafkaContainer = {
    DockerContainer("confluentinc/cp-kafka:3.3.1", name = Some("kafka"))
      .withPorts(DefaultKafkaPort -> Some(DefaultKafkaPort))
      .withLinks(ContainerLink(zookeeperContainer, "zookeeper"))
      .withEnv(
        s"KAFKA_ZOOKEEPER_CONNECT=zookeeper:$DefaultZookeeperPort",
        "KAFKA_BROKER_ID=1",
        s"KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$hostIp:$DefaultKafkaPort",
        "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1"
      )
      .withLogWritingAndReadyChecker(s"""started (kafka.server.KafkaServer)""", "kafka")
  }

  lazy val schemaRegistryContainer =
    DockerContainer("confluentinc/cp-schema-registry:3.3.1", name = Some("schema-registry"))
      .withPorts(DefaultSchemaRegistryPort -> Some(DefaultSchemaRegistryPort))
      .withLinks(
        ContainerLink(zookeeperContainer, "zookeeper")
      )
      .withEnv(
        "SCHEMA_REGISTRY_HOST_NAME=schema-registry",
        s"SCHEMA_REGISTRY_KAFKASTORE_CONNECTION_URL=zookeeper:$DefaultZookeeperPort",
        s"SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS=PLAINTEXT://$hostIp:$DefaultKafkaPort"
      )
      .withLogWritingAndReadyChecker("Server started, listening for requests", "schema-registry")

  val deliveryService = {
    val envVars = List(
      Some("ENV=LOCAL"),
      Some(s"KAFKA_HOSTS_AIVEN=$hostIp:$DefaultKafkaPort"),
      Some("LOCAL_DYNAMO=http://dynamodb:8000"),
      Some("RUNNING_IN_DOCKER=true"),
      Some(s"SCHEMA_REGISTRY_URL=http://schema-registry:$DefaultSchemaRegistryPort"),
      Some("MAILGUN_DOMAIN=mailgun@email.com"),
      Some("MAILGUN_API_KEY=my_super_secret_api_key"),
      Some(s"MAILGUN_HOST=http://api.mailgun.net:$MockserverPort"),
      Some(s"TWILIO_HOST=http://api.twilio.com:$MockserverPort"),
      Some(s"STANNP_URL=http://dash.stannp.com:$MockserverPort"),
      Some("STANNP_API_KEY=stannp_api_key"),
      Some("STANNP_PASSWORD=stannp_password"),
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
        ContainerLink(schemaRegistryContainer, "schema-registry"),
        ContainerLink(dynamodb, "dynamodb"),
        ContainerLink(fakes3ssl, "dev-ovo-comms-pdfs.s3-eu-west-1.amazonaws.com"),
        ContainerLink(mockServers, "api.mailgun.net"),
        ContainerLink(mockServers, "api.twilio.com"),
        ContainerLink(mockServers, "dash.stannp.com")
      )
      .withEnv(envVars: _*)
      .withVolumes(List(VolumeMapping(host = s"${sys.env("HOME")}/.aws", container = "/sbin/.aws"))) // share AWS creds so that credstash works
      .withLogWritingAndReadyChecker("Delivery Service started", "delivery-service") // TODO check topics/consumers in the app and output a log when properly ready
  }

  // TODO The fake s3 does not have a specific tag, so we have to go with latest
  lazy val fakes3 = {
    DockerContainer("lphoward/fake-s3:latest", name = Some("fakes3"))
      .withPorts(4569 -> Some(4569))
      .withLogWritingAndReadyChecker("WEBrick::HTTPServer#start", "fakes3")
  }

  lazy val fakes3ssl = {
    DockerContainer("cbachich/ssl-proxy:latest", name = Some("fakes3ssl"))
      .withPorts(443 -> Some(443))
      .withLinks(ContainerLink(fakes3, "proxyapp"))
      .withEnv(
        "PORT=443",
        "TARGET_PORT=4569"
      )
      .withLogWritingAndReadyChecker("Starting Proxy: 443", "fakes3ssl")
  }

  lazy val dynamodb = DockerContainer("forty8bit/dynamodb-local:latest", name = Some("dynamodb"))
    .withPorts(8000 -> Some(8000))
    .withCommand("-sharedDb")
    .withLogWritingAndReadyChecker(
      "Initializing DynamoDB Local",
      "dynamodb",
      onReady = () => {
        println("Creating Dynamo table")
        LocalDynamoDb.createTable(LocalDynamoDb.client())(tableName = "commRecord")(
          attributes = 'hashedComm -> ScalarAttributeType.S)
      }
    )

  override def dockerContainers =
    List(zookeeperContainer,
         kafkaContainer,
         dynamodb,
         schemaRegistryContainer,
         fakes3,
         fakes3ssl,
         mockServers,
         deliveryService)

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

    println(
      "Starting a whole bunch of Docker containers. This could take a few minutes, but I promise it'll be worth the wait!")
    startAllOrFail()
    TopicNames.foreach(t => checkCanConsumeFromKafkaTopic(t, s"localhost:$DefaultKafkaPort", "Aiven"))
  }

  abstract override def afterAll(): Unit = {
    stopAllQuietly()
    super.afterAll()
  }

  def port(internalPort: Int, dockerContainer: DockerContainer): Option[Int] =
    Await.result(dockerContainer
                   .getPorts()
                   .map(ports => ports.get(internalPort)),
                 30.seconds)

  def unsafePort(internalPort: Int, dockerContainer: DockerContainer): Int =
    port(internalPort, dockerContainer)
      .getOrElse(throw new RuntimeException(s"The port $internalPort is not exposed"))

}
