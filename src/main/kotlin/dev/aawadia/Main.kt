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
import io.vertx.micrometer.MicrometerMetricsOptions
import java.io.File
import java.util.concurrent.atomic.LongAdder
import kotlin.concurrent.thread

val messagesTransmitted = LongAdder()
val connections = LongAdder()

fun main() {
  val vertx = Vertx.vertx(getVertxOptions())
  val chatService = ChatService()
  vertx.deployVerticle({ ChatServer(chatService) }, getDeploymentOptions())
    .onFailure { it.printStackTrace() }
    .onSuccess { println("deployed..") }

  miscUtilityWork(vertx)
}

fun miscUtilityWork(vertx: Vertx) {
  val statsFile = File("stats.txt")
  if (statsFile.exists().not()) statsFile.createNewFile()
  restoreCounts(statsFile)
  periodicSaveOfCounts(vertx, statsFile)
  addShutdownHook(vertx, statsFile)
}

fun restoreCounts(statsFile: File) {
  val text = statsFile.readText()
  if (text.isEmpty()) return
  val stats = JsonObject(text)
  messagesTransmitted.add(stats.getLong("messagesTransmitted", 0))
}

fun periodicSaveOfCounts(vertx: Vertx, statsFile: File) {
  vertx.setPeriodic(60_000) {
    val closeStats = JsonObject()
    closeStats.put("messagesTransmitted", messagesTransmitted.sum())
    statsFile.writeText(closeStats.encodePrettily())
  }
}

fun addShutdownHook(vertx: Vertx, statsFile: File) {
  Runtime.getRuntime().addShutdownHook(thread(start = false) {
    vertx.close()
    val closeStats = JsonObject()
    closeStats.put("messagesTransmitted", messagesTransmitted.sum())
    statsFile.writeText(closeStats.encodePrettily())
  })
}

fun getDeploymentOptions(instances: Int = 2 * Runtime.getRuntime().availableProcessors()): DeploymentOptions {
  return DeploymentOptions()
    .setInstances(instances)
    .setWorkerPoolSize(16 * instances)
    .setConfig(JsonObject().put("key", System.getenv("key") ?: "5194980534"))
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
