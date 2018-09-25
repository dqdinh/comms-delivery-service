package com.ovoenergy.delivery.service.email

import java.time.{Clock, Instant}

import cats.effect.Sync
import com.ovoenergy.comms.model.email.ComposedEmailV4
import com.ovoenergy.delivery.service.domain._
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.ovoenergy.delivery.service.validation.BlackWhiteList
import cats.implicits._
import com.ovoenergy.delivery.service.persistence.CommContent

object IssueEmail extends LoggingWithMDC {
  //TODO: Make single IssueComm, final tagless style algebra
  def issue[F[_]](
      checkBlackWhiteList: (String) => BlackWhiteList.Verdict,
      isExpired: Option[Instant] => Boolean,
      content: CommContent[F],
      sendEmail: Content.Email => F[GatewayComm])(implicit F: Sync[F]): ComposedEmailV4 => F[GatewayComm] = {
    composedEmail: ComposedEmailV4 =>
      // TODO: Move out of this function
      def blackWhiteListCheck: F[Unit] = checkBlackWhiteList(composedEmail.recipient) match {
        case BlackWhiteList.OK =>
          F.unit
        case BlackWhiteList.NotWhitelisted =>
          F.delay(logWarn(composedEmail, s"Email addressed is not whitelisted: ${composedEmail.recipient}")) >>
            F.raiseError(EmailAddressNotWhitelisted(composedEmail.recipient))
        case BlackWhiteList.Blacklisted =>
          F.delay(logWarn(composedEmail, s"Email addressed is blacklisted: ${composedEmail.recipient}")) >>
            F.raiseError(EmailAddressBlacklisted(composedEmail.recipient))
      }

      def expiryCheck: F[Unit] = {
        if (isExpired(composedEmail.expireAt)) {
          F.delay(logInfo(composedEmail, s"Comm was expired")) >>
            F.raiseError(Expired)
        } else {
          F.unit
        }
      }
      for {
        _              <- blackWhiteListCheck
        _              <- expiryCheck
        fetchedContent <- content.getEmailContent(composedEmail)
        gatewayComm    <- sendEmail(fetchedContent)
      } yield gatewayComm
  }

}
