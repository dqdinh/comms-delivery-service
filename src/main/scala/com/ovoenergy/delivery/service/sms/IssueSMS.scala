package com.ovoenergy.delivery.service.sms

import java.time.Instant

import com.ovoenergy.comms.model.sms.ComposedSMSV4
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.validation.BlackWhiteList

object IssueSMS extends LoggingWithMDC {

  def issue(checkBlackWhiteList: (String) => BlackWhiteList.Verdict,
            isExpired: Option[Instant] => Boolean,
            sendSMS: ComposedSMSV4 => Either[DeliveryError, GatewayComm])(
      composedSMS: ComposedSMSV4): Either[DeliveryError, GatewayComm] = {

    def blackWhiteListCheck: Either[DeliveryError, Unit] = checkBlackWhiteList(composedSMS.recipient) match {
      case BlackWhiteList.OK =>
        Right(())
      case BlackWhiteList.NotWhitelisted =>
        logWarn(composedSMS, s"Mobile number is not whitelisted: ${composedSMS.recipient}")
        Left(EmailAddressNotWhitelisted(composedSMS.recipient))
      case BlackWhiteList.Blacklisted =>
        logWarn(composedSMS, s"Mobile number is blacklisted: ${composedSMS.recipient}")
        Left(EmailAddressBlacklisted(composedSMS.recipient))
    }

    def expiryCheck: Either[DeliveryError, Unit] = {
      if (isExpired(composedSMS.expireAt)) {
        logInfo(composedSMS, s"Comm was expired")
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
