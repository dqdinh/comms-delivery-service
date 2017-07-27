package com.ovoenergy.delivery.service.util

import java.time.Instant
import java.util.UUID

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.refineV
import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Shapeless._
import org.scalacheck.rng.Seed
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import scala.util.Random

trait ArbGenerator {

  implicit def arbInstant: Arbitrary[Instant] = Arbitrary {
    Instant.now().plusSeconds(Random.nextInt(5))
  }

  // Ensure we don't get empty strings
  implicit def arbString: Arbitrary[String] = Arbitrary {
    UUID.randomUUID().toString
  }

  implicit def arbPositive: Arbitrary[Refined[Int, Positive]] = Arbitrary {
    refineV[Positive](1).right.get
  }

  implicit def arbFiniteDuration: Arbitrary[FiniteDuration] = Arbitrary {
    Gen.choose(1, 10).map(_.seconds)
  }

  def generate[A: Arbitrary] =
    implicitly[Arbitrary[A]].arbitrary
      .apply(Gen.Parameters.default.withSize(Random.nextInt(2)), Seed.random())
      .get

}
