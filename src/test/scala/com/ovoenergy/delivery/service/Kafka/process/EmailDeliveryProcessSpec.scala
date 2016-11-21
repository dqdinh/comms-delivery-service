package com.ovoenergy.delivery.service.Kafka.process

import java.util.UUID

import com.ovoenergy.comms.{ComposedEmail, EmailProgressed, Failed}
import com.ovoenergy.delivery.service.email.mailgun.EmailDeliveryError
import com.ovoenergy.delivery.service.kafka.process.EmailDeliveryProcess
import org.scalacheck.Arbitrary
import org.scalacheck.Shapeless._
import org.scalatest._
import org.scalatest.prop._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class EmailDeliveryProcessSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  val successfulEmailProgressedProducer = (f: EmailProgressed) => Future.successful(())
  val successfulEmailFailedProducer     = (f: EmailDeliveryError) => Future.successful(())

  def notBlackListed(composedEmail: ComposedEmail) = true

  behavior of "EmailDeliveryProcess"

  it should "Handle Successfully sent emails" in {
    forAll(minSuccessful(10)) { (msg: ComposedEmail, progressed: EmailProgressed) =>
      val sendMail = (mail: ComposedEmail) => Right(progressed)

      val res: Future[_] = EmailDeliveryProcess(notBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, sendMail)(msg)
      res.map(_ == Unit)
    }
  }

  it should "Handle emails which have failed to send" in {
    forAll(minSuccessful(10)) { (msg: ComposedEmail, deliveryError: EmailDeliveryError) =>
      val sendMail = (mail: ComposedEmail) => Left(deliveryError)

      val res: Future[_] = EmailDeliveryProcess(notBlackListed, successfulEmailFailedProducer, successfulEmailProgressedProducer, sendMail)(msg)
      res.map(_ == Unit)
    }
  }

  val failedEmailProgressedProducer = (f: EmailProgressed) => Future.failed(new Exception("Broken"))
  val failedlEmailFailedProducer    = (f: EmailDeliveryError) => Future.failed(new Exception("Broken"))

  it should "Handle exceptions thrown by emailFailedProducer" in {
    forAll(minSuccessful(2)) { (msg: ComposedEmail, deliveryError: EmailDeliveryError) =>
      val sendMail = (mail: ComposedEmail) => Left(deliveryError)

      val res: Future[_] = EmailDeliveryProcess(notBlackListed, failedlEmailFailedProducer, failedEmailProgressedProducer, sendMail)(msg)
      res.map(_ == Unit)
    }
  }
  it should "Handle exceptions thrown by emailProgressedProducer" in {
    forAll(minSuccessful(2)) { (msg: ComposedEmail, progressed: EmailProgressed) =>
      val sendMail = (mail: ComposedEmail) => Right(progressed)

      val res: Future[_] = EmailDeliveryProcess(notBlackListed, failedlEmailFailedProducer, failedEmailProgressedProducer, sendMail)(msg)
      res.map(_ == Unit)
    }
  }
}
