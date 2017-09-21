package com.ovoenergy.delivery.service

import java.time.{Clock, Duration}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.RunnableGraph
import com.amazonaws.regions.Regions
import com.ovoenergy.comms.helpers.{Kafka, Topic}
import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.comms.model.sms.ComposedSMSV2
import com.ovoenergy.delivery.service.email.IssueEmail
import com.ovoenergy.delivery.service.email.mailgun.MailgunClient
import com.ovoenergy.delivery.service.http.HttpClient
import com.ovoenergy.delivery.service.kafka.DeliveryServiceGraph
import com.ovoenergy.delivery.service.kafka.process.{FailedEvent, IssuedForDeliveryEvent}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.sms.IssueSMS
import com.ovoenergy.delivery.service.sms.twilio.TwilioClient
import com.ovoenergy.delivery.service.validation.{BlackWhiteList, ExpiryCheck}
import com.ovoenergy.delivery.config._
import com.ovoenergy.delivery.service.ErrorHandling._

import scala.language.implicitConversions
import com.typesafe.config.ConfigFactory
import com.ovoenergy.comms.serialisation.Codecs._
import com.ovoenergy.delivery.service.domain.{DeliveryError, GatewayComm}
import com.ovoenergy.delivery.service.persistence.DynamoPersistence.Context
import com.ovoenergy.delivery.service.persistence.{AwsProvider, DynamoPersistence}
import com.ovoenergy.delivery.service.util.HashFactory

import scala.language.reflectiveCalls
import scala.concurrent.duration.FiniteDuration
import scala.io.Source

object Main extends App with LoggingWithMDC {

  implicit val clock = Clock.systemDefaultZone()

  private implicit class RichDuration(val duration: Duration) extends AnyVal {
    def toFiniteDuration: FiniteDuration = FiniteDuration.apply(duration.toNanos, TimeUnit.NANOSECONDS)
  }

  implicit val conf             = ConfigFactory.load()
  implicit val actorSystem      = ActorSystem("kafka")
  implicit val materializer     = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  implicit val scheduler        = actorSystem.scheduler

  implicit val appConf = ConfigLoader.applicationConfig match {
    case Left(e)    => log.error(s"Stopping application as config failed to load with error: $e"); sys.exit(1)
    case Right(res) => res
  }

  val failedTopic     = Kafka.aiven.failed.v2
  val failedPublisher = exitAppOnFailure(failedTopic.publisher, failedTopic.name)

  val issuedForDeliveryTopic     = Kafka.aiven.issuedForDelivery.v2
  val issuedForDeliveryPublisher = exitAppOnFailure(issuedForDeliveryTopic.publisher, issuedForDeliveryTopic.name)

  val region = Regions.fromName(conf.getString("aws.region"))
  val isRunningInLocalDocker = sys.env.get("ENV").contains("LOCAL") && sys.env
      .get("RUNNING_IN_DOCKER")
      .contains("true")

  val dynamoPersistence = new DynamoPersistence(
    Context(AwsProvider.dynamoClient(isRunningInLocalDocker, region), conf.getString("commRecord.persistence.table")))

  val issueEmailComm: (ComposedEmailV2) => Either[DeliveryError, GatewayComm] = IssueEmail.issue(
    checkBlackWhiteList = BlackWhiteList.buildForEmail,
    isExpired = ExpiryCheck.isExpired,
    sendEmail = MailgunClient.sendEmail(HttpClient.apply)
  )

  val emailGraph = DeliveryServiceGraph[ComposedEmailV2](
    topic = Kafka.aiven.composedEmail.v2,
    deliverComm = DeliverComm(new HashFactory, dynamoPersistence, issueEmailComm),
    sendFailedEvent = FailedEvent.email(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.email(issuedForDeliveryPublisher)
  )

  val issueSMSComm: (ComposedSMSV2) => Either[DeliveryError, GatewayComm] = IssueSMS.issue(
    checkBlackWhiteList = BlackWhiteList.buildForSms,
    isExpired = ExpiryCheck.isExpired,
    sendSMS = TwilioClient.send(HttpClient.apply)
  )

  val smsGraph = DeliveryServiceGraph[ComposedSMSV2](
    topic = Kafka.aiven.composedSms.v2,
    deliverComm = DeliverComm(new HashFactory, dynamoPersistence, issueSMSComm),
    sendFailedEvent = FailedEvent.sms(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.sms(issuedForDeliveryPublisher)
  )

  for (line <- Source.fromFile("./banner.txt").getLines) {
    println(line)
  }

  log.info("Delivery Service started")
  setupGraph(emailGraph, "Email Delivery")
  setupGraph(smsGraph, "SMS Delivery")

  private def setupGraph(graph: RunnableGraph[Control], graphName: String) = {
    val control: Control = graph.run()
    control.isShutdown.foreach { _ =>
      log.error(s"ARGH! The Kafka source has shut down for graph $graphName. Killing the JVM and nuking from orbit.")
      System.exit(1)
    }
  }
}
