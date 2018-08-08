package com.ovoenergy.delivery.service.email

import java.time.Instant

import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.validation.BlackWhiteList

object IssueEmail extends LoggingWithMDC {

  def issue(checkBlackWhiteList: (String) => BlackWhiteList.Verdict,
            isExpired: Option[Instant] => Boolean,
            sendEmail: ComposedEmailV4 => Either[DeliveryError, GatewayComm])(
      composedEmail: ComposedEmailV4): Either[DeliveryError, GatewayComm] = {

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

    for {
      _           <- blackWhiteListCheck
      _           <- expiryCheck
      gatewayComm <- sendEmail(composedEmail)
    } yield gatewayComm
  }

}
