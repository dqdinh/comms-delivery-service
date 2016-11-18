package com.ovoenergy.delivery.service.Kafka.process

import java.util.UUID

import com.ovoenergy.comms.{ComposedEmail, Failed}
import com.ovoenergy.delivery.service.email.mailgun.EmailProgressed
import com.ovoenergy.delivery.service.kafka.process.EmailDeliveryProcess
import org.scalacheck.Arbitrary

import scala.concurrent.ExecutionContext.Implicits.global
import org.scalacheck.Shapeless._
import org.scalatest._
import org.scalatest.prop._
import org.scalacheck.Arbitrary._

import scala.concurrent.Future


class EmailDeliveryProcessSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  val emailProgressedProducer = (f: EmailProgressed) => Future.successful(())
  val emailFailedProducer     = (f: Failed) => Future.successful(())

  behavior of "EmailDeliveryProcess"

  it should "placeholder test" in {
    forAll(minSuccessful(10)) { (msg: ComposedEmail, progressed: EmailProgressed) =>
      val sendMail = (mail: ComposedEmail) => Right(progressed)

      val emailDeliveryProcess = new EmailDeliveryProcess(emailFailedProducer, emailProgressedProducer, sendMail)

      val res: Future[Unit] = emailDeliveryProcess(msg)
      res.map(_ == Unit)
    }
  }
}
