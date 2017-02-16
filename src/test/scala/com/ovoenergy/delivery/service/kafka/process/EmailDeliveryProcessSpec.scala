package com.ovoenergy.delivery.service.kafka.process

import java.util.UUID

import akka.Done
import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.validation.BlackWhiteList
import com.ovoenergy.delivery.service.email.mailgun.EmailDeliveryError
import org.scalacheck.Shapeless._
import org.scalacheck._
import org.scalatest.prop._
import org.scalatest.{Failed => _, _}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

class EmailDeliveryProcessSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  private def generate[A](a: Arbitrary[A]) = {
    a.arbitrary.sample.get
  }

  val progressed    = generate(implicitly[Arbitrary[EmailProgressed]])
  val failed        = generate(implicitly[Arbitrary[Failed]])
  val composedEmail = generate(implicitly[Arbitrary[ComposedEmail]])
  val uuid          = generate(implicitly[Arbitrary[UUID]])
  val deliveryError = generate(implicitly[Arbitrary[EmailDeliveryError]])

  val blackWhiteListOK                     = (_: String) => BlackWhiteList.OK
  val generateUUID                         = () => uuid
  val successfullySendEmail                = (_: ComposedEmail) => Right(progressed)
  val successfullySendFailedEvent          = (_: Failed) => Future.successful(failed)
  val successfullySendEmailProgressedEvent = (_: EmailProgressed) => Future.successful(Done)
  val notExpired                           = (_: Option[String]) => false

  behavior of "EmailDeliveryProcess"

  it should "Handle Successfully sent emails" in {
    val result = Await.result(
      EmailDeliveryProcess(blackWhiteListOK,
                           notExpired,
                           successfullySendFailedEvent,
                           successfullySendEmailProgressedEvent,
                           generateUUID,
                           successfullySendEmail)(composedEmail),
      5 seconds
    )
    result should be(Done)
  }

  it should "Handle emails which have failed to send, generating appropriate error code in failed event" in {
    val failToSendEmail = (_: ComposedEmail) => Left(deliveryError)

    val result = Await.result(
      EmailDeliveryProcess(blackWhiteListOK,
                           notExpired,
                           successfullySendFailedEvent,
                           successfullySendEmailProgressedEvent,
                           generateUUID,
                           failToSendEmail)(composedEmail),
      5 seconds
    )
    result should be(failed)
  }

  it should "Handle exceptions thrown when sending a Failed event" in {
    val failToSendEmail       = (_: ComposedEmail) => Left(deliveryError)
    val failToSendFailedEvent = (_: Failed) => Future.failed(new Exception("Email delivery error exception"))

    Await.result(
      EmailDeliveryProcess(blackWhiteListOK,
                           notExpired,
                           failToSendFailedEvent,
                           successfullySendEmailProgressedEvent,
                           generateUUID,
                           failToSendEmail)(composedEmail),
      5 seconds
    )
  }

  it should "Handle exceptions thrown by emailProgressedProducer" in {
    val failToSendEmailProgressedEvent =
      (_: EmailProgressed) => Future.failed(new Exception("Email progressed exception"))
    Await.result(
      EmailDeliveryProcess(blackWhiteListOK,
                           notExpired,
                           successfullySendFailedEvent,
                           failToSendEmailProgressedEvent,
                           generateUUID,
                           successfullySendEmail)(composedEmail),
      5 seconds
    )
  }

  it should "not send an email if the recipient address is blacklisted" in {
    val blacklisted = (_: String) => BlackWhiteList.Blacklisted

    val result = Await.result(
      EmailDeliveryProcess(blacklisted,
                           notExpired,
                           successfullySendFailedEvent,
                           successfullySendEmailProgressedEvent,
                           generateUUID,
                           successfullySendEmail)(composedEmail),
      5 seconds
    )
    result should be(failed)
  }

  it should "not send an email if the comm has expired" in {
    val expired = (_: Option[String]) => true

    val result = Await.result(
      EmailDeliveryProcess(blackWhiteListOK,
                           expired,
                           successfullySendFailedEvent,
                           successfullySendEmailProgressedEvent,
                           generateUUID,
                           successfullySendEmail)(composedEmail),
      5 seconds
    )
    result should be(failed)
  }

}
