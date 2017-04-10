package com.ovoenergy.delivery.service.sms

import com.ovoenergy.comms.model._
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.validation.BlackWhiteList
object IssueSMS extends LoggingWithMDC {

  def issue(checkBlackWhiteList: (String) => BlackWhiteList.Verdict,
            isExpired: Option[String] => Boolean,
            sendSMS: (ComposedSMS) => Either[DeliveryError, GatewayComm])(
      composedSMS: ComposedSMS): Either[DeliveryError, GatewayComm] = {

    val traceToken = composedSMS.metadata.traceToken

    def blackWhiteListCheck: Either[DeliveryError, Unit] = checkBlackWhiteList(composedSMS.recipient) match {
      case BlackWhiteList.OK =>
        Right(())
      case BlackWhiteList.NotWhitelisted =>
        logWarn(traceToken, s"Mobile number is not whitelisted: ${composedSMS.recipient}")
        Left(EmailAddressNotWhitelisted(composedSMS.recipient))
      case BlackWhiteList.Blacklisted =>
        logWarn(traceToken, s"Mobile number is blacklisted: ${composedSMS.recipient}")
        Left(EmailAddressBlacklisted(composedSMS.recipient))
    }

    def expiryCheck: Either[DeliveryError, Unit] = {
      if (isExpired(composedSMS.expireAt)) {
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
      gatewayComm <- sendSMS(composedSMS)
    } yield gatewayComm
  }
}
