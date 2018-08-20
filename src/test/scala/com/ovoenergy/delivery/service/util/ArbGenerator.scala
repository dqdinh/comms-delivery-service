package com.ovoenergy.delivery.service.util

import java.time.Instant
import java.util.UUID

import com.ovoenergy.comms.model.{Arbitraries, Channel, Gateway}
import com.ovoenergy.comms.templates.model.Brand
import com.ovoenergy.delivery.config.ConstantDelayRetry
import com.ovoenergy.delivery.service.domain.{DeliveryError, DuplicateDeliveryError, GatewayComm}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.rng.Seed
import org.scalacheck.Arbitrary._

import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

trait ArbGenerator { arbitraries: Arbitraries =>

  implicit val arbBrand: Arbitrary[Brand] = Arbitrary(Gen.oneOf(Brand.allBrands))

  implicit val arbGatewayComm: Arbitrary[GatewayComm] = Arbitrary {
    for {
      gw   <- arbitrary[Gateway]
      str  <- arbitrary[String]
      chan <- arbitrary[Channel]
    } yield GatewayComm(gw, str, chan)
  }

  // Ensure we don't get empty strings
  implicit val arbString: Arbitrary[String] = Arbitrary {
    UUID.randomUUID().toString
  }

  implicit val arbPositive: Arbitrary[Refined[Int, Positive]] = Arbitrary {
    refineV[Positive](1).right.get
  }

  implicit val arbFiniteDuration: Arbitrary[FiniteDuration] = Arbitrary {
    Gen.choose(1, 10).map(_.seconds)
  }

  implicit val arbConstantDelay = Arbitrary {
    for {
      pos <- arbitrary[Refined[Int, Positive]]
      fd  <- arbitrary[FiniteDuration]
    } yield ConstantDelayRetry(pos, fd)
  }

  def generate[A: Arbitrary] =
    implicitly[Arbitrary[A]].arbitrary
      .apply(Gen.Parameters.default.withSize(Random.nextInt(2)), Seed.random())
      .get

}
