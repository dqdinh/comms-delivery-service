package com.ovoenergy.delivery.service

import java.io.ByteArrayInputStream
import java.util

import com.sksamuel.avro4s.{AvroInputStream, AvroJsonInputStream, FromRecord, SchemaFor}
import org.apache.kafka.common.serialization.Deserializer

import scala.util.Try


class AvroDeserializer[T](implicit schema: SchemaFor[T], record: FromRecord[T]) extends Deserializer[Try[T]] {

  override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {}

  override def close(): Unit = {}

  override def deserialize(topic: String, data: Array[Byte]): Try[T] = {
    val in = new ByteArrayInputStream(data, 0, data.size)
    val input: AvroJsonInputStream[T] = AvroInputStream.json[T](in)
    input.singleEntity
  }
}