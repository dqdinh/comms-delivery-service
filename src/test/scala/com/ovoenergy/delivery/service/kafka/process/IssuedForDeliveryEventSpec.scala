package com.ovoenergy.delivery.service.kafka.process

import java.time.Clock

import com.ovoenergy.comms.model.IssuedForDeliveryV2
import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.delivery.service.domain.GatewayComm
import com.ovoenergy.delivery.service.util.ArbGenerator
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.scalacheck.Shapeless._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class IssuedForDeliveryEventSpec extends FlatSpec with Matchers with ArbGenerator with GeneratorDrivenPropertyChecks {

  private implicit val clock = Clock.systemUTC()

  private val gatewayComm                     = generate[GatewayComm]
  private val composedEmail                   = generate[ComposedEmailV2]
  private var issuedForDeliveryEventPublished = Option.empty[IssuedForDeliveryV2]
  private val publishEvent = (issuedForDelivery: IssuedForDeliveryV2) => {
    issuedForDeliveryEventPublished = Some(issuedForDelivery)
    Future.successful(new RecordMetadata(new TopicPartition("", 1), 1l, 1l, 1l, 1l, 1, 1))
  }

  "IssuedForDeliveryEvent" should "process an issued email" in {
    IssuedForDeliveryEvent.email(publishEvent)(composedEmail, gatewayComm)
    issuedForDeliveryEventPublished.get.metadata.traceToken shouldBe composedEmail.metadata.traceToken
    issuedForDeliveryEventPublished.get.metadata.source shouldBe "delivery-service"
    issuedForDeliveryEventPublished.get.gatewayMessageId shouldBe gatewayComm.id
    issuedForDeliveryEventPublished.get.gateway shouldBe gatewayComm.gateway
    issuedForDeliveryEventPublished.get.internalMetadata shouldBe composedEmail.internalMetadata
    issuedForDeliveryEventPublished.get.channel shouldBe gatewayComm.channel
  }

}
