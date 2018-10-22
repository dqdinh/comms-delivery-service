package servicetest

import org.mockserver.client.server.MockServerClient
import org.scalatest.{BeforeAndAfterAll, Suite}

trait MockServer extends BeforeAndAfterAll { self: Suite =>

  val mockServerClient = new MockServerClient("localhost", 1080)

  override def afterAll(): Unit = {
    super.afterAll()
    mockServerClient.close()
  }
}
