package com.ovoenergy.delivery.service.domain

import java.time.{OffsetDateTime, ZonedDateTime}
import java.time.format.DateTimeFormatter

import cats.{Apply, Monad}
import com.ovoenergy.comms.model.{Customer, TemplateManifest}
import com.ovoenergy.comms.model.email.ComposedEmailV4
import cats.implicits._

object Content {
  case class SMS(textBody: TextBody)
  case class Print(value: Array[Byte])

  case class Email(sender: Sender,
                   recipient: Recipient,
                   subject: Subject,
                   htmlBody: HtmlBody,
                   textBody: Option[TextBody],
                   customFormData: CustomFormData)
  object Email {
    private val dtf = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    def apply[F[_]](composedEmail: ComposedEmailV4, subject: Subject, htmlBody: HtmlBody, textBody: Option[TextBody])(
        implicit time: F[ZonedDateTime],
        F: Apply[F]): F[Email] = {
      val customerId = composedEmail.metadata.deliverTo match {
        case Customer(cId) => Some(cId)
        case _             => None
      }
      time.map { t =>
        Email(
          Content.Sender(composedEmail.sender),
          Content.Recipient(composedEmail.recipient),
          subject,
          htmlBody,
          textBody,
          CustomFormData(
            createdAt = t.format(dtf),
            customerId = customerId,
            traceToken = composedEmail.metadata.traceToken,
            canary = composedEmail.metadata.canary,
            templateManifest = composedEmail.metadata.templateManifest,
            internalTraceToken = composedEmail.internalMetadata.internalTraceToken,
            triggerSource = composedEmail.metadata.triggerSource,
            friendlyDescription = composedEmail.metadata.friendlyDescription,
            commId = composedEmail.metadata.commId
          )
        )
      }
    }
  }
  case class Sender(value: String)
  case class Recipient(value: String)
  case class Subject(value: String)
  case class HtmlBody(value: String)
  case class CustomFormData(createdAt: String,
                            customerId: Option[String],
                            traceToken: String,
                            canary: Boolean,
                            templateManifest: TemplateManifest,
                            internalTraceToken: String,
                            triggerSource: String,
                            friendlyDescription: String,
                            commId: String)
  case class TextBody(value: String)
}
