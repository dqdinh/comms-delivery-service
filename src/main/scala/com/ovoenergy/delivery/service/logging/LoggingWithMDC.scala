package com.ovoenergy.delivery.service.logging

import org.slf4j.{LoggerFactory, MDC}

trait LoggingWithMDC {

  def loggerName: String

  lazy val log = LoggerFactory.getLogger(loggerName)

  def logDebug(transactionId: String, message: String): Unit = {
    log(transactionId, () => log.debug(message))
  }

  def logInfo(transactionId: String, message: String): Unit = {
    log(transactionId, () => log.info(message))
  }

  def logError(transactionId: String, message: String): Unit = {
    log(transactionId, () => log.error(message))
  }

  def logError(transactionId: String, message: String, error: Throwable): Unit = {
    log(transactionId, () => log.error(message, error))
  }

  def logWarn(transactionId: String, message: String, error: Throwable): Unit = {
    log(transactionId, () => log.warn(message, error))
  }

  private def log(transactionId: String, loggingFunction: () => Unit) {
    try {
      MDC.put("transactionId", transactionId)
      loggingFunction()
    } finally {
      MDC.remove("transactionId")
    }

  }


}
