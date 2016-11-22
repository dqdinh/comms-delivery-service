package com.ovoenergy.delivery.service.Kafka.process

import java.io.IOException
import java.util.UUID

import akka.Done
import com.ovoenergy.comms.{ComposedEmail, EmailProgressed, Metadata}
import com.ovoenergy.delivery.service.email.mailgun.EmailDeliveryError
import com.ovoenergy.delivery.service.kafka.process.EmailDeliveryProcess
import org.scalacheck.Arbitrary
import org.scalatest._
import org.mockito.Mockito.when
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
  val metaData        = mock[Metadata]

  val successfulEmailProgressedProducer = (f: EmailProgressed) => Future.successful(emailSentRes)
  val successfulEmailFailedProducer     = (f: EmailDeliveryError) => Future.successful(emailFailedRes)

  behavior of "EmailDeliveryProcess"

  val emailProgressedPublisher = (f: EmailProgressed) => Future.failed(new IOException("Email progressed exception"))
  val emailFailedPublisher     = (f: EmailDeliveryError) => Future.failed(new IOException("Email delivery error exception"))

  it should "Handle exceptions thrown by emailFailedProducer" in {
    when(emailComposed.metadata).thenReturn(metaData)
    when(metaData.transactionId).thenReturn("YOOO")

    val sendMail  = (mail: ComposedEmail) => Left(deliveryError)
    val res       = Await.result(EmailDeliveryProcess(isBlackListed, emailFailedPublisher, emailProgressedPublisher, sendMail)(emailComposed), 10 seconds)

    assert(res == ())
  }

  it should "Handle exceptions thrown by emailProgressedProducer" in {
    val sendMail  = (mail: ComposedEmail) => Right(emailProgressed)
    val res       =  Await.result(EmailDeliveryProcess(isBlackListed, emailFailedPublisher, emailProgressedPublisher, sendMail)(emailComposed), 10 seconds)
    assert(res == ())
  }

  it should "detect blacklisted emails and not send them" in {
    def isBlackListed(composedEmail: ComposedEmail) = true
    val sendMail  = (mail: ComposedEmail) => Right(emailProgressed)

    val result    = Await.result(EmailDeliveryProcess(isBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, sendMail)(emailComposed), 5 seconds)
    result should be(emailFailedRes)
  }
}


