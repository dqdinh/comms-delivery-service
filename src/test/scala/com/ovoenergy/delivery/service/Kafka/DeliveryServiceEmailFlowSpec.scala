package com.ovoenergy.delivery.service.Kafka

import com.ovoenergy.delivery.service.kafka.DeliveryServiceEmailFlow
import org.scalatest.{FlatSpec, Matchers}

class DeliveryServiceEmailFlowSpec extends FlatSpec
  with Matchers {

  behavior of "DeliveryServiceFlow"

  it should "placeholder test" in {

    val groupid = "hi"
    val consumerTopic = "hii"
    val consumerSettings = ???
    val deserializer = ???
    val sendMail = ???
    val kafkaProducers = ???
    val flow = DeliveryServiceEmailFlow(consumerSettings, consumerTopic, deserializer, sendMail, kafkaProducers)



  }

}
