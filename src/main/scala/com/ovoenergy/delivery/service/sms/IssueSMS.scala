package com.ovoenergy.delivery.service.sms

import java.time.Instant

import cats.effect.Sync
import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.comms.templates.model.template.metadata.{TemplateId, TemplateSummary}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.validation.BlackWhiteList
import cats.implicits._
import com.ovoenergy.delivery.service.persistence.{CommContent, TemplateMetadataRepo}
import com.ovoenergy.delivery.service.sms.twilio.TwilioClient.TwilioData

object IssueSMS extends LoggingWithMDC {

  def issue[F[_]](checkBlackWhiteList: String => BlackWhiteList.Verdict,
                  isExpired: Option[Instant] => Boolean,
                  templateMetadataRepo: TemplateMetadataRepo[F],
                  content: CommContent[F],
                  sendSMS: (TwilioData) => F[GatewayComm])(implicit F: Sync[F]): ComposedSMSV4 => F[GatewayComm] = {
    composedSMS: ComposedSMSV4 =>
      def blackWhiteListCheck: F[Unit] = checkBlackWhiteList(composedSMS.recipient) match {
        case BlackWhiteList.OK => F.unit
        case BlackWhiteList.NotWhitelisted =>
          F.delay(logWarn(composedSMS, s"Phone number is not whitelisted: ${composedSMS.recipient}")) >>
            F.raiseError(PhoneNumberNotWhitelisted(composedSMS.recipient))
        case BlackWhiteList.Blacklisted =>
          F.delay(logWarn(composedSMS, s"Phone number is blacklisted: ${composedSMS.recipient}")) >>
            F.raiseError(PhoneNumberBlacklisted(composedSMS.recipient))
      }

      def expiryCheck: F[Unit] =
        if (isExpired(composedSMS.expireAt))
          F.delay(logInfo(composedSMS, s"Comm was expired")) >>
            F.raiseError(Expired)
        else
          F.unit

      templateMetadataRepo
        .getTemplateMetadata(TemplateId(composedSMS.metadata.templateManifest.id))
        .flatMap { templateSummary: TemplateSummary =>
          for {
            _              <- blackWhiteListCheck
            _              <- expiryCheck
            fetchedContent <- content.getSMSContent(composedSMS) //TODO: Better name
            gatewayComm <- sendSMS(
              TwilioData(fetchedContent, templateSummary.brand, Content.Recipient(composedSMS.recipient)))
          } yield gatewayComm
        }
        .onError {
          case de: DeliveryError => F.delay(logWarn(composedSMS, s"Failed to deliver comm: ${de.description}"))
        }
  }
}
