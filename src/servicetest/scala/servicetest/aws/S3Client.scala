package servicetest.aws

import com.amazonaws.services.s3.{AmazonS3Client, AmazonS3ClientBuilder}
import org.scalatest.{BeforeAndAfterAll, Suite}

trait S3Client extends BeforeAndAfterAll { self: Suite =>

  lazy val s3Client =
    AmazonS3ClientBuilder
      .standard()
      .build()

  override def afterAll(): Unit = {
    super.afterAll()
    s3Client.asInstanceOf[AmazonS3Client].shutdown()
  }
}
