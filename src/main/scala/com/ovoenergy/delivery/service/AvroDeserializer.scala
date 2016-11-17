package com.ovoenergy.delivery.service

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util

import com.sksamuel.avro4s._
import org.apache.kafka.common.serialization.{Deserializer, Serializer}

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

class AvroSerializer[T](implicit schema: SchemaFor[T], record: ToRecord[T]) extends Serializer[Try[T]] {

  override def configure(configs: util.Map[String, _], isKey: Boolean): Unit = {}

  override def close(): Unit = {}

  override def serialize(topic: String, data: Try[T]): Array[Byte] = {
  val outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()
  val output = AvroOutputStream.json[T](outputStream)

    data.map { data =>
    output.write(data)
    output.close
    outputStream.toByteArray
    }.getOrElse(Array.emptyByteArray)
  }
}