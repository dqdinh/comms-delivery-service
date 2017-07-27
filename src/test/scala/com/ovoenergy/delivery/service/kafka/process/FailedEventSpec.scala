package com.ovoenergy.delivery.service.kafka.process

import java.time.Clock

import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.ComposedEmailV2
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.util.ArbGenerator
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.scalacheck.Shapeless._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FailedEventSpec extends FlatSpec with Matchers with ArbGenerator with GeneratorDrivenPropertyChecks {

  private implicit val clock = Clock.systemUTC()

  private val composedEmail        = generate[ComposedEmailV2]
  private var failedEventPublished = Option.empty[FailedV2]
  private val publishEvent = (failed: FailedV2) => {
    failedEventPublished = Some(failed)
    Future.successful(new RecordMetadata(new TopicPartition("", 1), 1l, 1l, 1l, java.lang.Long.valueOf(1), 1, 1))
  }

  "FailedEvent" should "process failed email" in {
    FailedEvent.email(publishEvent)(composedEmail, APIGatewayUnspecifiedError(EmailGatewayError))
    failedEventPublished.get.metadata.traceToken shouldBe composedEmail.metadata.traceToken
    failedEventPublished.get.metadata.source shouldBe "delivery-service"
    failedEventPublished.get.errorCode shouldBe APIGatewayUnspecifiedError(EmailGatewayError).errorCode
    failedEventPublished.get.reason shouldBe APIGatewayUnspecifiedError(EmailGatewayError).description
    failedEventPublished.get.internalMetadata shouldBe composedEmail.internalMetadata
  }

}
