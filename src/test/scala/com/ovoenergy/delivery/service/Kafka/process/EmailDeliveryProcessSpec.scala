package com.ovoenergy.delivery.service.Kafka.process

import java.time.{Clock, OffsetDateTime, ZoneId}
import java.util.UUID

import akka.Done
import com.ovoenergy.comms.model.{ComposedEmail, EmailProgressed, Metadata, Failed}
import com.ovoenergy.delivery.service.email.mailgun.EmailDeliveryError
import com.ovoenergy.delivery.service.kafka.process.EmailDeliveryProcess
import org.scalacheck.Arbitrary
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.prop._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class EmailDeliveryProcessSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks with MockitoSugar {

  val dateTime = OffsetDateTime.now()
  implicit val clock = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))

  val kafkaId = UUID.randomUUID()
  val kafkaIdGenerator = () => kafkaId

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  def isBlackListed(composedEmail: ComposedEmail) = false

  val traceToken = "fpwfj2i0jr02jr2j0"
  val createdAt = "2019-01-01T12:34:44.222Z"
  val customerId = "GT-CUS-994332344"
  val friendlyDescription = "The customer did something cool and wants to know"

  val metadata = Metadata(
    createdAt = createdAt,
    eventId = UUID.randomUUID().toString,
    customerId = customerId,
    traceToken = traceToken,
    friendlyDescription = friendlyDescription,
    source = "tests",
    sourceMetadata = None,
    canary = false)

  val emailProgressed = mock[EmailProgressed]
  val emailComposed   = ComposedEmail(metadata, "", "", "", "", None)

  val deliveryError   = mock[EmailDeliveryError]
  val emailSentRes    = mock[Done]

  val successfulEmailProgressedProducer = (f: EmailProgressed) => Future.successful(emailSentRes)
  val successfulEmailFailedProducer     = (f: Failed) => Future.successful(Failed(metadata, ""))

  behavior of "EmailDeliveryProcess"

  it should "Handle Successfully sent emails" in {
    val sendMail  = (mail: ComposedEmail) => Right(emailProgressed)
    val result    = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, kafkaIdGenerator, clock, sendMail)(emailComposed), 5 seconds)

    result should be(emailSentRes)
  }

  it should "Handle emails which have failed to send" in {
    val sendMail  = (mail: ComposedEmail) => Left(deliveryError)
    val result    = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, kafkaIdGenerator, clock, sendMail)(emailComposed), 5 seconds)

    result should be(Failed(metadata, ""))
  }

  val emailProgressedPublisher = (f: EmailProgressed) => Future.failed(new Exception("Email progressed exception"))
  val emailFailedPublisher     = (f: Failed) => Future.failed(new Exception("Email delivery error exception"))

  it should "Handle exceptions thrown by emailFailedProducer" in {
    val sendMail  = (mail: ComposedEmail) => Left(deliveryError)
    val result       = Await.result(EmailDeliveryProcess(isBlackListed, emailFailedPublisher, emailProgressedPublisher, kafkaIdGenerator, clock, sendMail)(emailComposed), 5 seconds)
    result shouldBe (())
  }

  it should "Handle exceptions thrown by emailProgressedProducer" in {
    val sendMail = (mail: ComposedEmail) => Right(emailProgressed)
    val result = Await.result(EmailDeliveryProcess(isBlackListed, emailFailedPublisher, emailProgressedPublisher, kafkaIdGenerator, clock, sendMail)(emailComposed), 5 seconds)
    result shouldBe (())
  }

  it should "detect blacklisted emails and not send them" in {
    def isBlackListed(composedEmail: ComposedEmail) = true
    val sendMail  = (mail: ComposedEmail) => Right(emailProgressed)

    val result    = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, kafkaIdGenerator, clock, sendMail)(emailComposed), 5 seconds)
    result should be(Failed(metadata, ""))
  }
}


