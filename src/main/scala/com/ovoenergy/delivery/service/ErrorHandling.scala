package com.ovoenergy.delivery.service

import com.ovoenergy.comms.serialisation.Retry
import com.ovoenergy.delivery.service.logging.LoggingWithMDC

object ErrorHandling extends LoggingWithMDC {

  implicit class eitherExtensions[LeftRes, RightRes](eitherval: Either[LeftRes, RightRes]) {
    def exitAppOnFailure(topicName: String)(implicit errorBuidler: ErrorBuilder[LeftRes]): RightRes = {
      eitherval match {
        case Left(err)  => {
          val loggableError = errorBuidler.buildError(topicName).apply(err)
          log.error(loggableError.str, loggableError.exception)
          sys.exit(1)
        }
        case Right(res) => res
      }
    }
  }
  case class ErrorToLog(str: String, exception: Throwable)

  trait ErrorBuilder[T]{
    def buildError(topic: String): T => ErrorToLog
  }

  // This is only being used for calls to the schema registry currently, hence the error message
  implicit val retryFailureBuilder = new ErrorBuilder[Retry.Failed] {
    override def buildError(topic: String): (Retry.Failed) => ErrorToLog = { failed =>
      ErrorToLog(
        s"Failed to register schema to topic $topic, ${failed.attemptsMade} attempts made",
        failed.finalException
      )
    }
  }
}
