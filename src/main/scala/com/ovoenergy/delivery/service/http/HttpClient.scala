package com.ovoenergy.delivery.service.http

import okhttp3._

import scala.util.Try

object HttpClient {

  private val httpClient = new OkHttpClient()

  def apply(request: Request) = {
      Try(httpClient.newCall(request).execute())
  }

}
