package com.ovoenergy.delivery.service.kafka.process

import akka.Done
import com.ovoenergy.comms.{ComposedEmail, Failed}
import com.ovoenergy.delivery.service.email.mailgun.EmailProgressed
import com.ovoenergy.delivery.service.logging.LoggingWithMDC

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class EmailDeliveryProcess(emailFailedProducer: (Failed) => Future[Unit], emailProgressedProducer: (EmailProgressed) => Future[Unit], sendEmail: (ComposedEmail) => Either[Failed, EmailProgressed]) extends LoggingWithMDC {

  private def sendAndProcessComm(composedEmail: ComposedEmail): Future[Unit] = {
    sendEmail(composedEmail) match {
      case Left(failed)      => emailFailedProducer(failed)
      case Right(progressed) => emailProgressedProducer(progressed)
    }
  }

  def apply(composedEmail: ComposedEmail) = {
        try {
          sendAndProcessComm(composedEmail)
        } catch {
          case ex: Throwable =>
            logError(composedEmail.metadata.transactionId, s"Skipping event", ex)
            Future(())
        }
  }

  override def loggerName: String = "EmailProcesses"
}
