package com.ovoenergy.delivery.service.kafka.process

import com.ovoenergy.comms.{ComposedEmail, EmailProgressed}
import com.ovoenergy.delivery.service.email.mailgun.{BlacklistedEmailAddress, EmailDeliveryError}
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import scala.concurrent.ExecutionContext.Implicits.global
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

    val result = if (isBlackListed(composedEmail)) emailFailedPublisher(BlacklistedEmailAddress)
      else sendAndProcessComm()

    result.recover{
      case NonFatal(err) => logWarn(composedEmail.metadata.transactionId, "Skipping event", err)
    }
  }

  override def loggerName: String = "EmailProcesses"
}
