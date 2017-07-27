package com.ovoenergy.delivery.service.validation

import com.ovoenergy.delivery.config.{EmailAppConfig, SmsAppConfig}
import org.scalatest._

class BlackWhiteListSpec extends FlatSpec with Matchers {

  implicit val emailAppconf = EmailAppConfig("(.+@foo.com)|(ok@bar.com)", List("bad@foo.com"))
  val emailRegexCheck       = BlackWhiteList.buildForEmail

  it should "accept whitelisted emails that are not on the blacklist" in {
    emailRegexCheck("chris@foo.com") should be(BlackWhiteList.OK)
    emailRegexCheck("ok@bar.com") should be(BlackWhiteList.OK)
  }

  it should "reject whitelisted emails that are on the blacklist" in {
    emailRegexCheck("bad@foo.com") should be(BlackWhiteList.Blacklisted)
  }

  it should "reject non-whitelisted emails" in {
    emailRegexCheck("chris@dunno.com") should be(BlackWhiteList.NotWhitelisted)
  }

  it should "only match strings that match the whitelist pattern exactly" in {
    emailRegexCheck("chris@foo.com.com") should be(BlackWhiteList.NotWhitelisted)
  }

  it should "reject non-whitelisted numbers" in {
    implicit val smsAppConf = SmsAppConfig(List("+447933452345"), Nil)
    val smsListCheck        = BlackWhiteList.buildForSms
    smsListCheck("+447465275432") shouldBe BlackWhiteList.NotWhitelisted
  }

  it should "reject blacklisted numbers" in {
    implicit val smsAppConf = SmsAppConfig(Nil, List("+447933452345"))
    val smsListCheck        = BlackWhiteList.buildForSms
    smsListCheck("+447933452345") shouldBe BlackWhiteList.Blacklisted
  }

}
