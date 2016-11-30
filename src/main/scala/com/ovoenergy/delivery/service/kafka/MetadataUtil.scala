package com.ovoenergy.delivery.service.kafka

import java.time.format.DateTimeFormatter
import java.time.{Clock, OffsetDateTime}
import java.util.UUID

import com.ovoenergy.comms.model.{ComposedEmail, Metadata}

object MetadataUtil {

  val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME

  def apply(uuidGenerator: () => UUID, clock: Clock)(composedEmail: ComposedEmail): Metadata = {
    composedEmail.metadata.copy(
      createdAt = OffsetDateTime.now(clock).format(dtf),
      eventId = uuidGenerator().toString,
      source = "delivery-service",
      sourceMetadata = Some(composedEmail.metadata.copy(sourceMetadata = None))
    )
  }

}
