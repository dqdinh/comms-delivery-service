package com.ovoenergy.delivery.service.kafka

import akka.Done
import com.ovoenergy.comms.{ComposedEmail, Failed}
import com.ovoenergy.delivery.service.email.mailgun.EmailProgressed
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future

class EmailDeliveryProcess(emailFailedProducer: (Failed) => Future[Done], emailProgressed: (EmailProgressed) => Future[Done], sendEmail: (ComposedEmail) => Either[Failed, EmailProgressed]) extends LoggingWithMDC {

  private def sendAndProcessComm(composedEmail: ComposedEmail): Future[Done] = {
    sendEmail(composedEmail) match {
      case Left(failed)      => emailFailedProducer(failed)
      case Right(progressed) => emailProgressed(progressed)
    }
  }

  def apply(composedEmail: ComposedEmail) = {
        try {
          sendAndProcessComm(composedEmail)
        } catch {
          case ex: Throwable =>
            logError(composedEmail.metadata.transactionId, s"Skipping event", ex)
            Future(Done)
        }
  }

  override def loggerName: String = "EmailProcesses"
}
