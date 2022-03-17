package dev.aawadia

import io.vertx.core.Vertx
import io.vertx.core.net.NetSocket
import org.junit.Test
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

class ProxyTest {
  @Test
  fun test() {
    val vertx = Vertx.vertx()
    var connected = false
    var msg = ""
    var receivedMsg = ""
    val c = CountDownLatch(1)

    fun connectHandler(netSocket: NetSocket) {
      connected = true
      netSocket.handler { buffer ->
        msg = buffer.toString()
        netSocket.write(msg)
      }
    }
    vertx.createNetServer().connectHandler(::connectHandler).listen(7000)

    val data = UUID.randomUUID().toString()
    val future = vertx.deployVerticle(TCPProxy::class.java, getDeploymentOptions(forwardPort = 7000, instances = 1))
      .compose { vertx.createNetClient().connect(9090, "localhost") }
      .onSuccess {
        it.handler { buffer -> receivedMsg = buffer.toString() }
        it.write(data)
      }
      .onFailure { assertTrue { it.printStackTrace(); true } }

    vertx.setTimer(1000) {
      future.onSuccess {
        assertTrue { connected }
        assertTrue { msg == data }
        assertTrue { receivedMsg == data }
      }.onFailure { fail(it.localizedMessage, it) }
      c.countDown()
    }

    c.await(5, TimeUnit.SECONDS)
  }
}
