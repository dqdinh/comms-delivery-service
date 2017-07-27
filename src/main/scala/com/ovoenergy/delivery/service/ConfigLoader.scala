package com.ovoenergy.delivery.service

import com.ovoenergy.delivery.config.ApplicationConfig
import com.ovoenergy.delivery.service.logging.LoggingWithMDC
import com.typesafe.config.ConfigFactory
import pureconfig._
import pureconfig.error.ConfigReaderFailures
import eu.timepit.refined.pureconfig._

object ConfigLoader extends LoggingWithMDC {

  def applicationConfig(resourceName: String): Either[ConfigReaderFailures, ApplicationConfig] = {
    loadConfig[ApplicationConfig](ConfigFactory.load(resourceName))
  }

  def applicationConfig: Either[ConfigReaderFailures, ApplicationConfig] = {
    loadConfig[ApplicationConfig](ConfigFactory.load)
  }

}
