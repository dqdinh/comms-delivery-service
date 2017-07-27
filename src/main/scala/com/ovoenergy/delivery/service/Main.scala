package com.ovoenergy.delivery.service

import java.time.{Clock, Duration}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.RunnableGraph
import com.ovoenergy.comms.helpers.Kafka
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
import scala.language.implicitConversions
import com.typesafe.config.ConfigFactory
import com.ovoenergy.comms.serialisation.Codecs._
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

  val failedPublisher            = Kafka.aiven.failed.v2.publisher
  val issuedForDeliveryPublisher = Kafka.aiven.issuedForDelivery.v2.publisher

  val emailGraph = DeliveryServiceGraph[ComposedEmailV2](
    topic = Kafka.aiven.composedEmail.v2,
    issueComm = IssueEmail.issue(
      checkBlackWhiteList = BlackWhiteList.buildForEmail,
      isExpired = ExpiryCheck.isExpired,
      sendEmail = MailgunClient.sendEmail(HttpClient.apply)
    ),
    sendFailedEvent = FailedEvent.email(failedPublisher),
    sendIssuedToGatewayEvent = IssuedForDeliveryEvent.email(issuedForDeliveryPublisher)
  )

  val smsGraph: RunnableGraph[Control] = DeliveryServiceGraph[ComposedSMSV2](
    topic = Kafka.aiven.composedSms.v2,
    issueComm = IssueSMS.issue(
      checkBlackWhiteList = BlackWhiteList.buildForSms,
      isExpired = ExpiryCheck.isExpired,
      sendSMS = TwilioClient.send(HttpClient.apply)
    ),
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
