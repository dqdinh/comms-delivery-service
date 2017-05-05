package com.ovoenergy.delivery.service.email

import com.ovoenergy.comms.model.{FailedV2, MetadataV2}
import com.ovoenergy.comms.model.email.{ComposedEmail, ComposedEmailV2}
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.validation.BlackWhiteList

object IssueEmail extends LoggingWithMDC {

  def issue(checkBlackWhiteList: (String) => BlackWhiteList.Verdict,
            isExpired: Option[String] => Boolean,
            sendEmail: (ComposedEmail) => Either[DeliveryError, GatewayComm])(
      composedEmail: ComposedEmailV2): Either[FailedV2, GatewayComm] = {

    def blackWhiteListCheck: Either[DeliveryError, Unit] = checkBlackWhiteList(composedEmail.recipient) match {
      case BlackWhiteList.OK =>
        Right(())
      case BlackWhiteList.NotWhitelisted =>
        logWarn(composedEmail, s"Email addressed is not whitelisted: ${composedEmail.recipient}")
        Left(EmailAddressNotWhitelisted(composedEmail.recipient))
      case BlackWhiteList.Blacklisted =>
        logWarn(composedEmail, s"Email addressed is blacklisted: ${composedEmail.recipient}")
        Left(EmailAddressBlacklisted(composedEmail.recipient))
    }

    def expiryCheck: Either[DeliveryError, Unit] = {
      if (isExpired(composedEmail.expireAt)) {
        logInfo(composedEmail, s"Comm was expired")
        Left(Expired)
      } else {
        Right(())
      }
    }

    import cats.syntax.either._
    val result = for {
      _           <- blackWhiteListCheck
      _           <- expiryCheck
      gatewayComm <- sendEmail(composedEmail)
    } yield gatewayComm

    result.leftMap { deliveryError =>
      FailedV2(
        metadata = MetadataV2.fromSourceMetadata("delivery-service", composedEmail.metadata),
        internalMetadata = composedEmail.internalMetadata,
        reason = deliveryError.description,
        errorCode = deliveryError.errorCode
      )
    }
  }

}
