package com.ovoenergy.delivery.service.http

import com.ovoenergy.comms.model.Channel
import com.ovoenergy.delivery.service.domain.DeliveryError
import io.circe.Decoder
import okhttp3._

import scala.util.Try

object HttpClient {

  private val httpClient = new OkHttpClient()

  def apply(request: Request): Try[Response] = {
    Try(httpClient.newCall(request).execute())
  }
}
