package com.ovoenergy.delivery.service.kafka.process

import com.ovoenergy.comms.{ComposedEmail, EmailProgressed}
import com.ovoenergy.delivery.service.email.mailgun.{BlacklistedEmailAddress, EmailDeliveryError}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC

import scala.concurrent.Future
import scala.util.control.NonFatal

object EmailDeliveryProcess extends LoggingWithMDC {

  def apply(isBlackListed: (ComposedEmail) => Boolean,
            emailFailedPublisher: (EmailDeliveryError)  => Future[_],
            emailProgressedPublisher: (EmailProgressed) => Future[_],
            sendEmail: (ComposedEmail) => Either[EmailDeliveryError, EmailProgressed])(composedEmail: ComposedEmail): Future[_] = {

    def sendAndProcessComm() = {
      sendEmail(composedEmail) match {
        case Left(failed)      => emailFailedPublisher(failed)
        case Right(progressed) => emailProgressedPublisher(progressed)
      }
    }

    try {
      if (isBlackListed(composedEmail)) emailFailedPublisher(BlacklistedEmailAddress)
      else sendAndProcessComm()
    } catch {
      case NonFatal(ex) =>
        logError(composedEmail.metadata.transactionId, s"Skipping event", ex)
        Future.successful()
    }
  }

  override def loggerName: String = "EmailProcesses"
}
