package com.ovoenergy.delivery.service.kafka.process

import cats.effect.IO
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.comms.model.print.ComposedPrintV2
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.util.ArbGenerator
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.scalatest.{FlatSpec, Matchers}

class FailedEventSpec extends FlatSpec with Matchers with Arbitraries with ArbGenerator with BuilderInstances {

  private val feedbackRm =
    new RecordMetadata(new TopicPartition("feedback", 1), 1l, 1l, 1l, java.lang.Long.valueOf(1), 1, 1)
  private val failedRm =
    new RecordMetadata(new TopicPartition("failedV3", 1), 1l, 1l, 1l, java.lang.Long.valueOf(1), 1, 1)

  private val publishLegacyFailed = (failed: FailedV3) => IO(failedRm)
  private val publishFeedback     = (feedback: Feedback) => IO(feedbackRm)

  "FailedEvent" should "process failed email" in {
    val composedEmail = generate[ComposedEmailV4]
    val rms = FailedEvent
      .apply[IO, ComposedEmailV4](publishLegacyFailed, publishFeedback)
      .apply(composedEmail, APIGatewayUnspecifiedError(EmailGatewayError, "Something went wrong"))
      .unsafeRunSync()

    rms should contain theSameElementsAs Seq(feedbackRm, failedRm)
  }

  "FailedEvent" should "process failed sms" in {
    val composedSms = generate[ComposedSMSV4]
    val rms = FailedEvent
      .apply[IO, ComposedSMSV4](publishLegacyFailed, publishFeedback)
      .apply(composedSms, APIGatewayUnspecifiedError(EmailGatewayError, "Something went wrong"))
      .unsafeRunSync()

    rms should contain theSameElementsAs Seq(feedbackRm, failedRm)
  }

  "FailedEvent" should "process failed print" in {
    val composedPrint = generate[ComposedPrintV2]
    val rms = FailedEvent
      .apply[IO, ComposedPrintV2](publishLegacyFailed, publishFeedback)
      .apply(composedPrint, APIGatewayUnspecifiedError(EmailGatewayError, "Something went wrong"))
      .unsafeRunSync()

    rms should contain theSameElementsAs Seq(feedbackRm, failedRm)
  }

}
