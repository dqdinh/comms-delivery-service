package com.ovoenergy.delivery.service.kafka

import java.time.format.DateTimeFormatter
import java.time.{Clock, OffsetDateTime}
import java.util.UUID

import com.ovoenergy.comms.{ComposedEmail, Metadata}
import okhttp3.{Request, Response}

import scala.util.Try

object MetadataUtil {

  val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  def apply(uuidGenerator: () => UUID, clock: Clock)(composedEmail: ComposedEmail): Metadata = {
    composedEmail.metadata.copy(
      timestampIso8601 = OffsetDateTime.now(clock).format(dtf),
      kafkaMessageId = uuidGenerator(),
      source = "delivery-service",
      sourceMetadata = Some(composedEmail.metadata.copy(sourceMetadata = None))
    )
  }

}
