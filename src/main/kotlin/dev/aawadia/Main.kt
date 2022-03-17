package dev.aawadia

import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.vertx.core.DeploymentOptions
import io.vertx.core.Vertx
import io.vertx.core.VertxOptions
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.await
import io.vertx.micrometer.MicrometerMetricsOptions
import io.vertx.micrometer.backends.BackendRegistries

fun main() {
  val vertx = Vertx.vertx(getVertxOptions())
  vertx.deployVerticle(TCPProxy::class.java, getDeploymentOptions())
}

class TCPProxy : CoroutineVerticle() {
  private val registry = BackendRegistries.getDefaultNow() as PrometheusMeterRegistry

  override suspend fun start() {
    super.start()
    val netClient = vertx.createNetClient()
    val netServer = vertx.createNetServer()

    val egressSocket =
      netClient.connect(config.getInteger("forwardPort", 5432), config.getString("forwardHost", "localhost"))
        .await()

    netServer.connectHandler { ingressSocket ->
      ingressSocket.pipe().to(egressSocket)
      egressSocket.pipe().to(ingressSocket)
    }

    netServer.listen(config.getInteger("listenPort", 9090))
      .onSuccess { println("server ready on port ${it.actualPort()}") }
      .onFailure { it.printStackTrace() }

    vertx.createHttpServer().requestHandler { it.response().end(registry.scrape()) }.listen(9091)
  }
}

fun getDeploymentOptions(
  forwardPort: Int = 5432,
  forwardHost: String = "localhost",
  listenPort: Int = 9090,
  instances: Int = 2 * Runtime.getRuntime().availableProcessors()
): DeploymentOptions {
  return DeploymentOptions()
    .setInstances(instances)
    .setWorkerPoolSize(16 * instances)
    .setConfig(
      JsonObject()
        .put("forwardPort", System.getenv("forward_port")?.toIntOrNull() ?: forwardPort)
        .put("forwardHost", System.getenv("forward_host")?.toString() ?: forwardHost)
        .put("listenPort", System.getenv("listen_port")?.toIntOrNull() ?: listenPort)
    )
}

fun getPrometheusRegistry(): PrometheusMeterRegistry {
  val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
  ClassLoaderMetrics().bindTo(registry)
  JvmMemoryMetrics().bindTo(registry)
  JvmGcMetrics().bindTo(registry)
  ProcessorMetrics().bindTo(registry)
  JvmThreadMetrics().bindTo(registry)
  UptimeMetrics().bindTo(registry)
  FileDescriptorMetrics().bindTo(registry)

  return registry
}

fun getVertxOptions(): VertxOptions {
  return VertxOptions().setMetricsOptions(
    MicrometerMetricsOptions()
      .setEnabled(true)
      .setMicrometerRegistry(getPrometheusRegistry())
  )
}