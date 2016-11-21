package com.ovoenergy.delivery.service.kafka.process

import com.ovoenergy.comms.{ComposedEmail, EmailProgressed}
import com.ovoenergy.delivery.service.email.mailgun.{BlacklistedEmailAddress, EmailDeliveryError}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.control.NonFatal

class EmailDeliveryProcess(isBlackListed: (ComposedEmail) => Boolean,
                           emailFailedProducer: (EmailDeliveryError) => Future[Unit],
                           emailProgressedProducer: (EmailProgressed) => Future[Unit],
                           sendEmail: (ComposedEmail) => Either[EmailDeliveryError, EmailProgressed]) extends LoggingWithMDC {

  def apply(composedEmail: ComposedEmail): Future[Unit] = {

    def sendAndProcessComm() = {
      sendEmail(composedEmail) match {
        case Left(failed)      => emailFailedProducer(failed)
        case Right(progressed) => emailProgressedProducer(progressed)
      }
    }

    try {
      if (isBlackListed(composedEmail)) emailFailedProducer(BlacklistedEmailAddress)
      else sendAndProcessComm()
    } catch {
      case NonFatal(ex) =>
        logError(composedEmail.metadata.transactionId, s"Skipping event", ex)
        Future(())
    }
  }

  override def loggerName: String = "EmailProcesses"
}
