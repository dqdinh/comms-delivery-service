package com.ovoenergy.delivery.service.http

import okhttp3._

import scala.util.Try

object HttpClient {

  val httpClient = new OkHttpClient()

  val processRequest = (request: Request) => {
      Try(httpClient.newCall(request).execute())
  }

}
