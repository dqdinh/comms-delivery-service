package com.ovoenergy.delivery.service.Kafka.process

import java.util.UUID

import akka.Done
import com.ovoenergy.comms.{ComposedEmail, EmailProgressed}
import com.ovoenergy.delivery.service.email.mailgun.EmailDeliveryError
import com.ovoenergy.delivery.service.kafka.process.EmailDeliveryProcess
import org.scalacheck.Arbitrary
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.prop._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class EmailDeliveryProcessSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks with MockitoSugar {

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  def isBlackListed(composedEmail: ComposedEmail) = false

  val emailProgressed = mock[EmailProgressed]
  val emailComposed   = mock[ComposedEmail]
  val deliveryError   = mock[EmailDeliveryError]
  val emailSentRes    = mock[Done]
  val emailFailedRes  = mock[Done]

  val successfulEmailProgressedProducer = (f: EmailProgressed) => Future.successful(emailSentRes)
  val successfulEmailFailedProducer     = (f: EmailDeliveryError) => Future.successful(emailFailedRes)

  behavior of "EmailDeliveryProcess"

  it should "Handle Successfully sent emails" in {
    val sendMail  = (mail: ComposedEmail) => Right(emailProgressed)
    val result    = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, sendMail)(emailComposed), 5 seconds)

    result should be(emailSentRes)
  }

  it should "Handle emails which have failed to send" in {
    val sendMail  = (mail: ComposedEmail) => Left(deliveryError)
    val result    = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, sendMail)(emailComposed), 5 seconds)

    result should be(emailFailedRes)
  }

  val emailProgressedPublisher = (f: EmailProgressed) => Future.failed(new Exception("Email progressed exception"))
  val emailFailedPublisher     = (f: EmailDeliveryError) => Future.failed(new Exception("Email delivery error exception"))

  it should "Handle exceptions thrown by emailFailedProducer" in {
    val sendMail  = (mail: ComposedEmail) => Left(deliveryError)
    val res       = EmailDeliveryProcess(isBlackListed, emailFailedPublisher, emailProgressedPublisher, sendMail)(emailComposed)

    val thrown = intercept[Exception] {
      val result = Await.result(res, 5 seconds)
      result should be(())
    }
    assert(thrown.getMessage == "Email delivery error exception")
  }

  it should "Handle exceptions thrown by emailProgressedProducer" in {
    val sendMail = (mail: ComposedEmail) => Right(emailProgressed)
    val res = EmailDeliveryProcess(isBlackListed, emailFailedPublisher, emailProgressedPublisher, sendMail)(emailComposed)

    val thrown = intercept[Exception] {
      val result = Await.result(res, 5 seconds)
      result should be(())
    }
    assert(thrown.getMessage == "Email progressed exception")
  }

  it should "detect blacklisted emails and not send them" in {
    def isBlackListed(composedEmail: ComposedEmail) = true
    val sendMail  = (mail: ComposedEmail) => Right(emailProgressed)

    val result    = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, sendMail)(emailComposed), 5 seconds)
    result should be(emailFailedRes)
  }
}


