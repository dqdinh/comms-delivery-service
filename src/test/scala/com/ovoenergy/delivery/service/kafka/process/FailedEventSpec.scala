package com.ovoenergy.delivery.service.kafka.process

import java.time.Clock
import java.util.UUID

import com.ovoenergy.comms.model.{ComposedEmail, Failed}
import com.ovoenergy.delivery.service.domain.APIGatewayUnspecifiedError
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.scalacheck.Arbitrary
import org.scalacheck.Shapeless._
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FailedEventSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {

  private implicit val clock = Clock.systemUTC()

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  private def generate[A](a: Arbitrary[A]) = {
    a.arbitrary.sample.get
  }

  private val composedEmail        = generate(implicitly[Arbitrary[ComposedEmail]])
  private var failedEventPublished = Option.empty[Failed]
  private val publishEvent = (failed: Failed) => {
    failedEventPublished = Some(failed)
    Future.successful(new RecordMetadata(new TopicPartition("", 1), 1l, 1l, 1l, 1l, 1, 1))
  }

  "FailedEvent" should "process failed email" in {
    FailedEvent.send(publishEvent)(composedEmail, APIGatewayUnspecifiedError)
    failedEventPublished.get.metadata.traceToken shouldBe composedEmail.metadata.traceToken
    failedEventPublished.get.errorCode shouldBe APIGatewayUnspecifiedError.errorCode
    failedEventPublished.get.reason shouldBe APIGatewayUnspecifiedError.description
    failedEventPublished.get.internalMetadata shouldBe composedEmail.internalMetadata
  }

}
