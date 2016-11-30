package com.ovoenergy.delivery.service

import com.ovoenergy.comms.model._
import com.ovoenergy.comms.serialisation.Serialisation._
import org.slf4j.LoggerFactory

object Serialization {

  val log = LoggerFactory.getLogger(getClass)

  val composedEmailSerializer     = avroSerializer[ComposedEmail]
  val composedEmailDeserializer   = avroDeserializer[ComposedEmail]
  val emailProgressedSerializer   = avroSerializer[EmailProgressed]
  val emailProgressedDeserializer = avroDeserializer[EmailProgressed]
  val failedSerializer            = avroSerializer[Failed]
  val failedDeserializer          = avroDeserializer[Failed]


}