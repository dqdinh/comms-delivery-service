package com.ovoenergy.delivery.service.email

import java.util.UUID

import cakesolutions.kafka.KafkaProducer
import cakesolutions.kafka.KafkaProducer.Conf
import com.ovoenergy.comms.ComposedEmail
import com.ovoenergy.delivery.service.Serialization._
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.scalacheck.Arbitrary
import org.scalacheck.Shapeless._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{FlatSpec, Matchers, Tag}

object TestItOutSpec extends Tag("DockerComposeTag")

class TestItOutSpec extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks with ScalaFutures {

  implicit def arbUUID: Arbitrary[UUID] = Arbitrary {
    UUID.randomUUID()
  }

  implicit val config: PatienceConfig = PatienceConfig(Span(60, Seconds))

  "producer" should "create messages" in {

    forAll(minSuccessful(1)) { (msg: ComposedEmail) =>

      Thread.sleep(5000)

      println(s"Producing message: $msg" )
      val producer  = KafkaProducer(Conf(new StringSerializer, composedEmailSerializer, "192.168.1.64:29092"))
      val future = producer.send(new ProducerRecord[String, ComposedEmail]("comms.composed.email", msg))
      whenReady(future) {
        case _ =>
          println("It's here")
          Thread.sleep(5000)

      }
    }




  }

}