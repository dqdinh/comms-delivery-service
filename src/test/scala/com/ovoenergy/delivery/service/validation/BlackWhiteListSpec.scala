package com.ovoenergy.delivery.service.validation

import org.scalatest._

class BlackWhiteListSpec extends FlatSpec with Matchers {

  val regexCheck = BlackWhiteList.buildFromRegex(
    whitelist = "(.+@foo.com)|(ok@bar.com)".r,
    blacklist = Seq("bad@foo.com")
  )

  it should "accept whitelisted emails that are not on the blacklist" in {
    regexCheck("chris@foo.com") should be(BlackWhiteList.OK)
    regexCheck("ok@bar.com") should be(BlackWhiteList.OK)
  }

  it should "reject whitelisted emails that are on the blacklist" in {
    regexCheck("bad@foo.com") should be(BlackWhiteList.Blacklisted)
  }

  it should "reject non-whitelisted emails" in {
    regexCheck("chris@dunno.com") should be(BlackWhiteList.NotWhitelisted)
  }

  it should "only match strings that match the whitelist pattern exactly" in {
    regexCheck("chris@foo.com.com") should be(BlackWhiteList.NotWhitelisted)
  }

  it should "reject non-whitelisted numbers" in {
    val listCheck = BlackWhiteList.buildFromLists(
      whitelist = Seq("+447933452345"),
      blacklist = Nil
    )

    listCheck("+447465275432") shouldBe BlackWhiteList.NotWhitelisted
  }

  it should "reject blacklisted numbers" in {
    val listCheck = BlackWhiteList.buildFromLists(
      whitelist = Nil,
      blacklist = Seq("+447933452345")
    )

    listCheck("+447933452345") shouldBe BlackWhiteList.Blacklisted
  }

}
