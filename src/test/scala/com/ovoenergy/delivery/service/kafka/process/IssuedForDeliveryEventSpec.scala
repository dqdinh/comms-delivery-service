package com.ovoenergy.delivery.service.kafka.process

import java.time.Clock

import cats.effect.IO
import com.ovoenergy.comms.model.{Arbitraries, IssuedForDeliveryV3}
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.delivery.service.domain.GatewayComm
import com.ovoenergy.delivery.service.util.ArbGenerator
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.scalatest.{FlatSpec, Matchers}

class IssuedForDeliveryEventSpec extends FlatSpec with Matchers with Arbitraries with ArbGenerator {

  private implicit val clock = Clock.systemUTC()

  private val gatewayComm                     = generate[GatewayComm]
  private val composedEmail                   = generate[ComposedEmailV4]
  private var issuedForDeliveryEventPublished = Option.empty[IssuedForDeliveryV3]
  private val publishEvent = (issuedForDelivery: IssuedForDeliveryV3) =>
    IO {
      issuedForDeliveryEventPublished = Some(issuedForDelivery)
      new RecordMetadata(new TopicPartition("", 1), 1l, 1l, 1l, java.lang.Long.valueOf(1), 1, 1)
  }

  "IssuedForDeliveryEvent" should "process an issued email" in {
    IssuedForDeliveryEvent.email(publishEvent)(composedEmail, gatewayComm).unsafeRunSync()
    issuedForDeliveryEventPublished.get.metadata.traceToken shouldBe composedEmail.metadata.traceToken
    issuedForDeliveryEventPublished.get.metadata.source shouldBe "delivery-service"
    issuedForDeliveryEventPublished.get.gatewayMessageId shouldBe gatewayComm.id
    issuedForDeliveryEventPublished.get.gateway shouldBe gatewayComm.gateway
    issuedForDeliveryEventPublished.get.internalMetadata shouldBe composedEmail.internalMetadata
    issuedForDeliveryEventPublished.get.channel shouldBe gatewayComm.channel
  }

}
