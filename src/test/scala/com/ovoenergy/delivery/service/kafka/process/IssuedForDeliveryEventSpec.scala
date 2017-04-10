package com.ovoenergy.delivery.service.kafka.process

import java.time.Clock

import com.ovoenergy.comms.model.{ComposedEmail, IssuedForDelivery}
import com.ovoenergy.delivery.service.domain.GatewayComm
import com.ovoenergy.delivery.service.util.ArbGenerator
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.scalacheck.Arbitrary
import org.scalacheck.Shapeless._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class IssuedForDeliveryEventSpec extends FlatSpec with Matchers with ArbGenerator with GeneratorDrivenPropertyChecks {

  private implicit val clock = Clock.systemUTC()

  private val gatewayComm                     = generate(implicitly[Arbitrary[GatewayComm]])
  private val composedEmail                   = generate(implicitly[Arbitrary[ComposedEmail]])
  private var issuedForDeliveryEventPublished = Option.empty[IssuedForDelivery]
  private val publishEvent = (issuedForDelivery: IssuedForDelivery) => {
    issuedForDeliveryEventPublished = Some(issuedForDelivery)
    Future.successful(new RecordMetadata(new TopicPartition("", 1), 1l, 1l, 1l, 1l, 1, 1))
  }

  "IssuedForDeliveryEvent" should "process an issued email" in {
    IssuedForDeliveryEvent.send(publishEvent)(composedEmail, gatewayComm)
    issuedForDeliveryEventPublished.get.metadata.traceToken shouldBe composedEmail.metadata.traceToken
    issuedForDeliveryEventPublished.get.metadata.source shouldBe "delivery-service"
    issuedForDeliveryEventPublished.get.gatewayMessageId shouldBe gatewayComm.id
    issuedForDeliveryEventPublished.get.gateway shouldBe gatewayComm.gateway
    issuedForDeliveryEventPublished.get.internalMetadata shouldBe composedEmail.internalMetadata
    issuedForDeliveryEventPublished.get.channel shouldBe gatewayComm.channel
  }

}
