package com.ovoenergy.delivery.service.email

import com.ovoenergy.comms.model.email.ComposedEmail
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.validation.BlackWhiteList

object IssueEmail extends LoggingWithMDC {

  def issue(checkBlackWhiteList: (String) => BlackWhiteList.Verdict,
            isExpired: Option[String] => Boolean,
            sendEmail: (ComposedEmail) => Either[DeliveryError, GatewayComm])(
      composedEmail: ComposedEmail): Either[DeliveryError, GatewayComm] = {

    val traceToken = composedEmail.metadata.traceToken

    def blackWhiteListCheck: Either[DeliveryError, Unit] = checkBlackWhiteList(composedEmail.recipient) match {
      case BlackWhiteList.OK =>
        Right(())
      case BlackWhiteList.NotWhitelisted =>
        logWarn(traceToken, s"Email addressed is not whitelisted: ${composedEmail.recipient}")
        Left(EmailAddressNotWhitelisted(composedEmail.recipient))
      case BlackWhiteList.Blacklisted =>
        logWarn(traceToken, s"Email addressed is blacklisted: ${composedEmail.recipient}")
        Left(EmailAddressBlacklisted(composedEmail.recipient))
    }

    def expiryCheck: Either[DeliveryError, Unit] = {
      if (isExpired(composedEmail.expireAt)) {
        logInfo(traceToken, s"Comm was expired")
        Left(Expired)
      } else {
        Right(())
      }
    }

    import cats.syntax.either._
    for {
      _           <- blackWhiteListCheck
      _           <- expiryCheck
      gatewayComm <- sendEmail(composedEmail)
    } yield gatewayComm
  }

}
