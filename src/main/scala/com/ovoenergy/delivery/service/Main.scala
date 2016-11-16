package com.ovoenergy.delivery.service

import com.ovoenergy.delivery.service.logging.LoggingWithMDC

object Main extends App
  with LoggingWithMDC {

  val loggerName = "Main"

  log.info("Delivery Service started")

  while (true) {
    Thread.sleep(1000)
  }
}
