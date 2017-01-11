package com.ovoenergy.delivery.service.kafka.process

import java.time.{Clock, OffsetDateTime, ZoneId}
import java.util.UUID

import akka.Done
import com.ovoenergy.comms.model.ErrorCode.EmailAddressBlacklisted
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.email.mailgun.EmailDeliveryError
import org.scalacheck.Shapeless._
import org.scalacheck._
import org.scalatest.prop._
import org.scalatest.{Failed => _, _}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class EmailDeliveryProcessSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {


  val dateTime = OffsetDateTime.now()
  implicit val clock = Clock.fixed(dateTime.toInstant, ZoneId.of("UTC"))

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  def isBlackListed(composedEmail: ComposedEmail) = false

  private def generate[A](a: Arbitrary[A]) = {
    a.arbitrary.sample.get
  }

  val progressed    = generate(implicitly[Arbitrary[EmailProgressed]])
  val failed        = generate(implicitly[Arbitrary[Failed]])
  val composedEmail = generate(implicitly[Arbitrary[ComposedEmail]])
  val uUID          = generate(implicitly[Arbitrary[UUID]])
  val emailSentRes  = generate(implicitly[Arbitrary[Done]])
  val deliveryError =  generate(implicitly[Arbitrary[EmailDeliveryError]])

  behavior of "EmailDeliveryProcess"

  it should "Handle Successfully sent emails" in {

      val successfulEmailFailedProducer = (f: Failed) => Future.successful(failed)
      val kafkaIdGenerator = () => uUID
      val sendMail  = (mail: ComposedEmail) => Right(progressed)
      val successfulEmailProgressedProducer = (f: EmailProgressed) => Future.successful(emailSentRes)

      val result    = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, kafkaIdGenerator, clock, sendMail)(composedEmail), 5 seconds)
      result should be(emailSentRes)
  }

  it should "Handle emails which have failed to send, generating appropriate error code in failed event" in {
    val kafkaIdGenerator = () => uUID
    val successfulEmailFailedProducer     = (f: Failed) => Future.successful(failed)
    val sendMail = (mail: ComposedEmail) => Left(deliveryError)
    val successfulEmailProgressedProducer = (f: EmailProgressed) => Future.successful(emailSentRes)

    val result = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, kafkaIdGenerator, clock, sendMail)(composedEmail), 5 seconds)
    result should be(failed)
  }

  val emailProgressedPublisher = (f: EmailProgressed) => Future.failed(new Exception("Email progressed exception"))
  val emailFailedPublisher     = (f: Failed) => Future.failed(new Exception("Email delivery error exception"))

  it should "Handle exceptions thrown by emailFailedProducer" in {
    val sendMail = (mail: ComposedEmail) => Left(deliveryError)
    val kafkaIdGenerator = () => uUID

    val result = Await.result(EmailDeliveryProcess(isBlackListed, emailFailedPublisher, emailProgressedPublisher, kafkaIdGenerator, clock, sendMail)(composedEmail), 5 seconds)
    result shouldBe (())
  }

  it should "Handle exceptions thrown by emailProgressedProducer" in {
    val sendMail = (mail: ComposedEmail) => Right(progressed)
    val kafkaIdGenerator = () => uUID
    val result = Await.result(EmailDeliveryProcess(isBlackListed, emailFailedPublisher, emailProgressedPublisher, kafkaIdGenerator, clock, sendMail)(composedEmail), 5 seconds)
    result shouldBe (())
  }

  it should "detect blacklisted emails and not send them, generating the appropriate error code in failed event" in {
    val successfulEmailFailedProducer     = (f: Failed) => Future.successful(failed)
    val kafkaIdGenerator = () => uUID
    def isBlackListed(composedEmail: ComposedEmail) = true
    val sendMail = (mail: ComposedEmail) => Right(progressed)
    val successfulEmailProgressedProducer = (f: EmailProgressed) => Future.successful(emailSentRes)

    val result = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, kafkaIdGenerator, clock, sendMail)(composedEmail), 5 seconds)
    result should be(failed)
  }
}


