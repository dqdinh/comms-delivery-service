package com.ovoenergy.delivery.service.kafka.process.email

import java.time.Clock
import java.util.UUID

import com.ovoenergy.comms.model.{ComposedEmail, EmailProgressed, IssuedForDelivery}
import com.ovoenergy.delivery.service.domain.GatewayComm
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.scalacheck.Arbitrary
import org.scalacheck.Shapeless._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EmailProgressedEventSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {

  private implicit val clock = Clock.systemUTC()

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  private def generate[A](a: Arbitrary[A]) = {
    a.arbitrary.sample.get
  }

  private val gatewayComm = generate(implicitly[Arbitrary[GatewayComm]])
  private val composedEmail = generate(implicitly[Arbitrary[ComposedEmail]])

  private var emailProgressedEventPublished = Option.empty[EmailProgressed]
  private val publishEvent = (event: EmailProgressed) => {
    emailProgressedEventPublished = Some(event)
    Future.successful(new RecordMetadata(new TopicPartition("",1), 1l, 1l, 1l, 1l, 1, 1))
  }

  "FailedEvent" should "process failed email" in {
    EmailProgressedEvent.send(publishEvent)(composedEmail, gatewayComm)
    emailProgressedEventPublished.get.metadata.traceToken shouldBe composedEmail.metadata.traceToken
    emailProgressedEventPublished.get.gatewayMessageId shouldBe Some(gatewayComm.id)
    emailProgressedEventPublished.get.gateway shouldBe gatewayComm.gateway
    emailProgressedEventPublished.get.internalMetadata shouldBe composedEmail.internalMetadata
  }

}
