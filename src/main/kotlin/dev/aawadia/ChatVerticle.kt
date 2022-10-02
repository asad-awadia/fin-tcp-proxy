package dev.aawadia

import es.moki.ratelimitj.core.limiter.request.RequestLimitRule
import es.moki.ratelimitj.inmemory.request.InMemorySlidingWindowRequestRateLimiter
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.ext.web.handler.LoggerHandler
import io.vertx.ext.web.handler.ResponseTimeHandler
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.micrometer.backends.BackendRegistries
import java.security.MessageDigest
import java.time.Duration
import java.time.format.DateTimeFormatter

class ChatServer(private val chatService: ChatService = ChatService()) : CoroutineVerticle() {
  override suspend fun start() {
    super.start()
    val router = Router.router(vertx)
    val registry = BackendRegistries.getDefaultNow() as PrometheusMeterRegistry

    router.route().handler(BodyHandler.create())
    router.route().handler(ResponseTimeHandler.create())
    router.route().handler(CorsHandler.create("*"))
    router.route().handler(LoggerHandler.create())
    router.route().failureHandler { handleFailure(it) }

    router.get("/chat/rooms").handler { sendListOfRoomsAsResponse(it) }
    router.route("/chat/:room/:nick").handler { handleWebsocketConnection(it) }

    val key = config.getString("key", "5194980534")
    router.get("/admin/$key/stats").blockingHandler { handleStatsRequest(it) }
    router.post("/admin/$key/config").handler { handleConfigUpdate(it) }
    router.post("/admin/$key/msg").handler { handleSendServerMessage(it) }
    router.post("/admin/$key/kick").handler { handleRemoveNick(it) }
    router.get("/admin/$key/metrics").blockingHandler { it.response().end(registry.scrape()) }

    vertx.createHttpServer(getHttpServerOptions())
      .requestHandler(router)
      .exceptionHandler { it.printStackTrace() }
      .listen(9090)
      .onFailure { it.printStackTrace() }
      .onSuccess { println("server deployed to port ${it.actualPort()}..") }
  }

  private fun handleRemoveNick(routingContext: RoutingContext) {
    val jsonObject = routingContext.bodyAsJson
    chatService.removeUser(jsonObject.getString("room"), jsonObject.getString("nick"))
    routingContext.response().end()
  }

  private fun handleSendServerMessage(routingContext: RoutingContext) {
    val jsonObject = routingContext.bodyAsJson
    val count = chatService.sendServerMessage(jsonObject.getString("room"), jsonObject.getString("msg"))
    routingContext.response().putHeader("Content-type", "application/json; charset=utf-8")
      .end(JsonObject().put("count", count).encodePrettily())
  }

  private fun handleConfigUpdate(routingContext: RoutingContext) {
    if (routingContext.queryParam("op").firstOrNull() == "delete") {
      routingContext.bodyAsJsonArray.forEach { vr -> chatService.removeRoom(vr.toString()) }
    } else routingContext.bodyAsJsonArray.forEach { vr -> chatService.addRoom(vr.toString()) }
    sendListOfRoomsAsResponse(routingContext)
  }

  private fun getHttpServerOptions(): HttpServerOptions {
    return HttpServerOptions().setTcpKeepAlive(true).setCompressionSupported(true).setCompressionLevel(8)
      .setWebSocketCompressionLevel(8).setTcpFastOpen(true).setTcpNoDelay(true).setTcpQuickAck(true)
  }

  private val requestRateLimiter = InMemorySlidingWindowRequestRateLimiter(RequestLimitRule.of(Duration.ofSeconds(1), 2))
  private fun sendListOfRoomsAsResponse(routingContext: RoutingContext) {
    val requestIp = routingContext.request().getHeader("CF-Connecting-IP").ifEmpty { "N/A" }

    if (requestRateLimiter.overLimitWhenIncremented("ip:$requestIp")) {
      println("request dropped for exceeding rate limit $requestIp")
      routingContext.response().setStatusCode(408).end()
      return
    }

    routingContext.response().putHeader("Content-type", "application/json; charset=utf-8")
      .end(JsonArray(chatService.getValidRooms()).encodePrettily() + "\n")
  }

  private fun handleFailure(routingContext: RoutingContext) {
    if (!routingContext.response().ended()) routingContext.response().end("internal server error")
    routingContext.failure()?.printStackTrace()
  }

  private fun handleStatsRequest(routingContext: RoutingContext) {
    val response = getStats()
    routingContext.response().putHeader("Content-type", "application/json; charset=utf-8")
      .end(response.encodePrettily())
  }

  private fun handleWebsocketConnection(routingContext: RoutingContext) {
    val result = getPathParams(routingContext)
    if (result.isFailure) {
      routingContext.fail(500)
      return
    }

    val (room, nick) = result.getOrDefault(Pair("", ""))
    if (requestParamsAreInvalid(room, nick)) {
      routingContext.request().toWebSocket()
        .onSuccess { chatService.writeFinalMessage(it, "room invalid or nick already in use") }
        .onFailure { routingContext.fail(it) }
      return
    }

    routingContext.request().resume()
      .toWebSocket()
      .onSuccess { prepareWebsocket(it, room, nick) }
      .onFailure { it.printStackTrace() }
  }

  private fun requestParamsAreInvalid(room: String, nick: SocketNick): Boolean {
    return room.isEmpty() or nick.isEmpty() or nick.none { it.isLetterOrDigit() } or chatService.isRoomNameInvalid(room) or chatService.isNickAlreadyJoined(
      room,
      nick
    )
  }

  private fun prepareWebsocket(webSocket: ServerWebSocket, room: String, nick: SocketNick) {
    connections.increment()
    val count = chatService.addConnectedWebsocketToRoom(webSocket, room, nick)
    chatService.sendWelcomeMessage(webSocket, count - 1)
    chatService.sendJoinMessage(webSocket, room, nick)
    chatService.attachCloseHandler(webSocket, room, nick)
    chatService.attachIncomingMessageHandler(webSocket, room, nick)
  }

  private fun getPathParams(routingContext: RoutingContext): Result<Pair<String, String>> {
    val room = routingContext.pathParam("room")
    val nick = routingContext.pathParam("nick")

    return if (room.isNullOrEmpty() || nick.isNullOrEmpty()) Result.failure(IllegalArgumentException("room or nick invalid"))
    else Result.success(Pair(room, nick))
  }

  private fun getStats(): JsonObject {
    val response = JsonObject()
    response.put("messagesTransmitted", messagesTransmitted.sum())
    response.put("numOfConnections", connections.sum())
    val rooms = chatService.getRooms()
    response.put("numOfRooms", rooms.size)
    rooms.forEach { entry ->
      response.put(
        entry.key, JsonObject().put("connectionsCount", entry.value.connectedUsers.getCount())
          .put(
            "createTime", entry.value.createTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
          )
      )
    }
    return response
  }
}

fun hashString(input: String): String {
  return MessageDigest
    .getInstance("SHA-256")
    .digest(input.toByteArray())
    .fold("") { str, it -> str + "%02x".format(it) }
}