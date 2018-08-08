package com.ovoenergy.delivery.service

import java.nio.file.Paths
import java.time.Clock

import akka.actor.ActorSystem
import cats.effect.{Effect, IO}
import com.ovoenergy.comms.helpers.{Kafka, KafkaClusterConfig, Topic}
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.comms.model.{FailedV3, IssuedForDeliveryV3}
import com.ovoenergy.comms.templates.model.template.metadata.TemplateId
import com.ovoenergy.comms.templates.{TemplateMetadataContext, TemplateMetadataRepo}
import com.ovoenergy.delivery.config._
import com.ovoenergy.delivery.service.ErrorHandling._
import com.ovoenergy.delivery.service.domain.{BuilderInstances, DeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.email.IssueEmail
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.kafka.process.{FailedEvent, IssuedForDeliveryEvent}
import com.ovoenergy.delivery.service.kafka.{EventProcessor, Producer}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.persistence.{AwsProvider, DynamoPersistence, S3PdfRepo}
import com.ovoenergy.delivery.service.print.IssuePrint
import com.ovoenergy.delivery.service.print.stannp.StannpClient
import com.ovoenergy.delivery.service.sms.IssueSMS
import com.ovoenergy.delivery.service.sms.twilio.TwilioClient
import com.ovoenergy.delivery.service.validation.{BlackWhiteList, ExpiryCheck}
import com.ovoenergy.fs2.kafka.{ConsumerSettings, Subscription, consumeProcessAndCommit}
import com.ovoenergy.kafka.serialization.core.constDeserializer
import com.sksamuel.avro4s.{FromRecord, SchemaFor, ToRecord}
import com.typesafe.config.ConfigFactory
import fs2._
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.config.SslConfigs
import com.ovoenergy.comms.serialisation.Codecs._
import scala.concurrent.duration.{FiniteDuration, _}
import scala.io.Source
import scala.language.{implicitConversions, reflectiveCalls}
import scala.reflect.ClassTag

object Main extends StreamApp[IO] with LoggingWithMDC with BuilderInstances {

  implicit val clock            = Clock.systemDefaultZone()
  implicit val conf             = ConfigFactory.load()
  implicit val actorSystem      = ActorSystem("kafka")
  implicit val executionContext = actorSystem.dispatcher
  implicit val scheduler        = actorSystem.scheduler

  implicit val appConf = ConfigLoader.applicationConfig match {
    case Left(e)    => log.error(s"Stopping application as config failed to load with error: $e"); sys.exit(1)
    case Right(res) => res
  }

  val failedTopic = Kafka.aiven.failed.v3
  val failedProducer = Producer
    .produce[FailedV3](_.metadata.eventId, exitAppOnFailure(Producer(failedTopic), failedTopic.name), failedTopic.name)
  val failedPublisher: FailedV3 => IO[RecordMetadata] = failedProducer.apply[IO]
  // TODO: Create Feedback publisher
  val issuedForDeliveryTopic = Kafka.aiven.issuedForDelivery.v3
  val issuedForDeliveryProducer = Producer.produce[IssuedForDeliveryV3](
    _.metadata.eventId,
    exitAppOnFailure(Producer(issuedForDeliveryTopic), issuedForDeliveryTopic.name),
    issuedForDeliveryTopic.name)
  val issuedForDeliveryPublisher: IssuedForDeliveryV3 => IO[RecordMetadata] = issuedForDeliveryProducer.apply[IO]

  val isRunningInLocalDocker = sys.env.get("ENV").contains("LOCAL") && sys.env
    .get("RUNNING_IN_DOCKER")
    .contains("true")

  val dynamoClient = AwsProvider.dynamoClient(isRunningInLocalDocker)

  val dynamoPersistence = new DynamoPersistence(dynamoClient)

  val templateMetadataContext = TemplateMetadataContext(dynamoClient, appConf.aws.dynamo.tableNames.templateSummary)
  val templateMetadataRepo    = TemplateMetadataRepo.getTemplateSummary(templateMetadataContext, _: TemplateId)

  val issueEmailComm: (ComposedEmailV4) => Either[DeliveryError, GatewayComm] = IssueEmail.issue(
    checkBlackWhiteList = BlackWhiteList.buildForEmail,
    isExpired = ExpiryCheck.isExpired,
    sendEmail = MailgunClient.sendEmail(HttpClient.apply)
  )

  val issueSMSComm: (ComposedSMSV4) => Either[DeliveryError, GatewayComm] = IssueSMS.issue(
    checkBlackWhiteList = BlackWhiteList.buildForSms,
    isExpired = ExpiryCheck.isExpired,
    templateMetadataRepo = templateMetadataRepo,
    sendSMS = TwilioClient.send(HttpClient.apply)
  )

  val issuePrintComm: (ComposedPrintV2) => Either[DeliveryError, GatewayComm] = IssuePrint.issue(
    isExpired = ExpiryCheck.isExpired,
    getPdf = S3PdfRepo.getPdfDocument(AwsProvider.getS3Context(isRunningInLocalDocker)),
    sendPrint = StannpClient.send(HttpClient.apply)
  )

  for (line <- Source.fromFile("./banner.txt").getLines) {
    println(line)
  }

  val aivenCluster                           = Kafka.aiven
  val kafkaClusterConfig: KafkaClusterConfig = aivenCluster.kafkaConfig

  val pollTimeout: FiniteDuration = 150.milliseconds

  val consumerNativeSettings: Map[String, AnyRef] = {
    Map(
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> kafkaClusterConfig.hosts,
      ConsumerConfig.GROUP_ID_CONFIG          -> kafkaClusterConfig.groupId
    ) ++ kafkaClusterConfig.ssl
      .map { ssl =>
        Map(
          CommonClientConfigs.SECURITY_PROTOCOL_CONFIG -> "SSL",
          SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG      -> Paths.get(ssl.keystore.location).toAbsolutePath.toString,
          SslConfigs.SSL_KEYSTORE_TYPE_CONFIG          -> "PKCS12",
          SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG      -> ssl.keystore.password,
          SslConfigs.SSL_KEY_PASSWORD_CONFIG           -> ssl.keyPassword,
          SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG    -> Paths.get(ssl.truststore.location).toString,
          SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG        -> "JKS",
          SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG    -> ssl.truststore.password
        )
      }
      .getOrElse(Map.empty) ++ kafkaClusterConfig.nativeProperties

  }

  val consumerSettings: ConsumerSettings = ConsumerSettings(
    pollTimeout = pollTimeout,
    maxParallelism = Int.MaxValue,
    nativeSettings = consumerNativeSettings
  )

  type Record[T] = ConsumerRecord[Unit, Option[T]]

  def processEvent[F[_], T: SchemaFor: ToRecord: FromRecord: ClassTag, A](f: Record[T] => F[A], topic: Topic[T])(
      implicit F: Effect[F]): fs2.Stream[F, A] = {

    val valueDeserializer = topic.deserializer.right.get

    consumeProcessAndCommit[F].apply(
      Subscription.topics(topic.name),
      constDeserializer[Unit](()),
      valueDeserializer,
      consumerSettings
    )(f)
  }

  def emailProcessor =
    EventProcessor[IO, ComposedEmailV4](
      DeliverComm[IO, ComposedEmailV4](dynamoPersistence, issueEmailComm),
      FailedEvent.apply[IO, ComposedEmailV4](failedPublisher, ???),
      IssuedForDeliveryEvent.email[IO](issuedForDeliveryPublisher)
    )

  def smsProcessor =
    EventProcessor[IO, ComposedSMSV4](
      DeliverComm[IO, ComposedSMSV4](dynamoPersistence, issueSMSComm),
      FailedEvent[IO, ComposedSMSV4](failedPublisher, ???),
      IssuedForDeliveryEvent.sms[IO](issuedForDeliveryPublisher)
    )

  def printProcessor =
    EventProcessor[IO, ComposedPrintV2](
      DeliverComm[IO, ComposedPrintV2](dynamoPersistence, issuePrintComm),
      FailedEvent[IO, ComposedPrintV2](failedPublisher, ???),
      IssuedForDeliveryEvent.print[IO](issuedForDeliveryPublisher)
    )

  override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, StreamApp.ExitCode] = {

    val emailStream: Stream[IO, Unit] =
      processEvent[IO, ComposedEmailV4, Unit](emailProcessor, aivenCluster.composedEmail.v4)

    val smsStream: Stream[IO, Unit] =
      processEvent[IO, ComposedSMSV4, Unit](smsProcessor, aivenCluster.composedSms.v4)

    val printStream: Stream[IO, Unit] =
      processEvent[IO, ComposedPrintV2, Unit](printProcessor, aivenCluster.composedPrint.v2)

    emailStream
      .mergeHaltBoth(smsStream)
      .mergeHaltBoth(printStream)
      .drain
      .covaryOutput[StreamApp.ExitCode] ++ Stream.emit(StreamApp.ExitCode.Error)

  }

  log.info("Delivery Service started")
}
