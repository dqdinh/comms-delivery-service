package com.ovoenergy.delivery.service.email

import org.scalatest._

class BlackWhiteListSpec extends FlatSpec with Matchers {

  val check = BlackWhiteList.build(
    whitelist = "(.+@foo.com)|(ok@bar.com)".r,
    blacklist = Seq("bad@foo.com")
  )

  it should "accept whitelisted emails that are not on the blacklist" in {
    check("chris@foo.com") should be(BlackWhiteList.OK)
    check("ok@bar.com") should be(BlackWhiteList.OK)
  }

  it should "reject whitelisted emails that are on the blacklist" in {
    check("bad@foo.com") should be(BlackWhiteList.Blacklisted)
  }

  it should "reject non-whitelisted emails" in {
    check("chris@dunno.com") should be(BlackWhiteList.NotWhitelisted)
  }

  it should "only match strings that match the whitelist pattern exactly" in {
    check("chris@foo.com.com") should be(BlackWhiteList.NotWhitelisted)
  }


}
