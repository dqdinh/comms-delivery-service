package servicetest.aws

import java.time.{DateTimeException, Instant}
import java.util.UUID

import com.gu.scanamo.DynamoFormat
import com.ovoenergy.comms.model._
import com.ovoenergy.comms.templates.TemplateMetadataDynamoFormats

trait DynamoFormats extends TemplateMetadataDynamoFormats {

  implicit val uuidDynamoFormat =
    DynamoFormat.coercedXmap[UUID, String, IllegalArgumentException](UUID.fromString)(_.toString)

  implicit val instantDynamoFormat =
    DynamoFormat.coercedXmap[Instant, Long, DateTimeException](Instant.ofEpochMilli)(_.toEpochMilli)

  implicit val commTypeDynamoFormat =
    DynamoFormat.coercedXmap[CommType, String, MatchError](CommType.unsafeFromString)(_.toString)

}

object DynamoFormats extends DynamoFormats
