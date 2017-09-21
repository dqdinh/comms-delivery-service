package com.ovoenergy.delivery.service.logging

import com.ovoenergy.comms.model.LoggableEvent
import org.slf4j.{LoggerFactory, MDC}

trait LoggingWithMDC {

  def loggerName: String = getClass.getSimpleName.reverse.dropWhile(_ == '$').reverse

  lazy val log = LoggerFactory.getLogger(loggerName)

  def logDebug(event: LoggableEvent, message: String): Unit = {
    log(event, log.debug(message))
  }

  def logInfo(event: LoggableEvent, message: String): Unit = {
    log(event, log.info(message))
  }

  def logWarn(event: LoggableEvent, message: String): Unit = {
    log(event, log.warn(message))
  }

  def logWarn(event: LoggableEvent, message: String, error: Throwable): Unit = {
    log(event, log.warn(message, error))
  }

  def logError(event: LoggableEvent, message: String, error: Throwable): Unit = {
    log(event, log.error(message, error))
  }

  def logError(event: LoggableEvent, message: String): Unit = {
    log(event, log.error(message))
  }

  private def log(event: LoggableEvent, writeToLog: => Unit) {
    event.mdcMap.foreach {
      case (k, v) => MDC.put(k, v)
    }
    try {
      writeToLog
    } finally {
      event.mdcMap.keys.foreach(MDC.remove)
    }
  }

}
