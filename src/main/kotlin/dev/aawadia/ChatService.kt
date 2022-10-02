package dev.aawadia

import es.moki.ratelimitj.core.limiter.request.RequestLimitRule
import es.moki.ratelimitj.inmemory.request.InMemorySlidingWindowRequestRateLimiter
import io.vertx.core.http.ServerWebSocket
import io.vertx.core.impl.ConcurrentHashSet
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

typealias SocketNick = String

val requestRateLimiter = InMemorySlidingWindowRequestRateLimiter(RequestLimitRule.of(Duration.ofSeconds(5), 2))

class ChatService(private val rooms: ConcurrentHashMap<String, ServerChatRoom> = ConcurrentHashMap()) {
  private val validRooms = ConcurrentHashSet<String>(128)
  private val allowedRooms = listOf(
    "dev",
    "kubernetes",
    "terraform",
    "food",
    "aws",
    "music",
    "hackernews",
    "golang",
    "mysql",
    "postgres",
    "jobs",
    "kotlin",
    "java",
    "rust",
    "grpc",
    "vertx",
    "server",
    "android",
    "ios",
    "datascience",
    "kafka",
    "redis",
    "javascript",
    "observability",
    "opensource",
    "general",
    "random",
  )

  init {
    validRooms.addAll(allowedRooms)
  }

  fun addConnectedWebsocketToRoom(webSocket: ServerWebSocket, roomName: String, nick: SocketNick): Int {
    val serverChatRoom = this.rooms.computeIfAbsent(roomName) { ServerChatRoom() }
    serverChatRoom.connectedUsers.addUser(webSocket, nick)
    return serverChatRoom.connectedUsers.getCount()
  }

  fun isNickAlreadyJoined(room: String, nick: SocketNick): Boolean =
    rooms[room]?.connectedUsers?.nickExists(nick) ?: false

  fun writeFinalMessage(websocket: ServerWebSocket, finalMessage: String) {
    websocket.writeFinalTextFrame(finalMessage + "\n").compose { websocket.close() }.onFailure { it.printStackTrace() }
  }

  fun attachIncomingMessageHandler(webSocket: ServerWebSocket, roomName: String, nick: String) {
    webSocket.textMessageHandler { textMessage ->
      if (requestRateLimiter.overLimitWhenIncremented(webSocket.textHandlerID())
        or (textMessage.startsWith("/") or textMessage.startsWith("\\"))
      ) return@textMessageHandler
      messagesTransmitted.increment()
      rooms[roomName]?.connectedUsers?.getSockets()
        ?.filterNot { it.textHandlerID() == webSocket.textHandlerID() }
        ?.forEach {
          it.writeTextMessage(
            "[${
              LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM/dd/yyyy 'at' hh:mm a"))
            }]-[${nick}]: $textMessage\n"
          )
        }
    }
  }

  fun sendJoinMessage(webSocket: ServerWebSocket, roomName: String, nick: String) {
    if (requestRateLimiter.overLimitWhenIncremented("joinMsg:${nick}")) return
    rooms[roomName]?.connectedUsers?.getSockets()
      ?.filterNot { it.textHandlerID() == webSocket.textHandlerID() }
      ?.forEach { it.writeTextMessage("[${nick}] just joined the room \n") }
  }

  fun sendServerMessage(roomName: String, msg: String): Int {
    if (roomName == "all") {
      return rooms.map {
        it.value.connectedUsers.getSockets()
          .onEach { webSocket -> webSocket.writeTextMessage("[server]: $msg\n") }
          .count()
      }.sum()
    }
    return rooms[roomName]?.connectedUsers?.getSockets()?.onEach { it.writeTextMessage("[server]: $msg\n") }?.count()
      ?: 0
  }

  fun attachCloseHandler(webSocket: ServerWebSocket, roomName: String, nick: SocketNick) {
    webSocket.closeHandler {
      connections.decrement()
      rooms[roomName]?.let { serverChatRoom ->
        with(serverChatRoom) {
          connectedUsers.removeNick(nick)
          connectedUsers.removeSocket(webSocket)
          if (requestRateLimiter.overLimitWhenIncremented("leaveMsg:${nick}")) return@closeHandler
          connectedUsers.getSockets().forEach { it.writeTextMessage("[${nick}] just left the room \n") }
        }
      }
    }
  }

  fun sendWelcomeMessage(webSocket: ServerWebSocket, roomSize: Int) {
    webSocket.writeTextMessage("Hello! Currently $roomSize users here in this room")
  }

  fun getRooms(): ConcurrentHashMap<String, ServerChatRoom> = this.rooms

  fun isRoomNameInvalid(room: String): Boolean = validRooms.contains(room).not()

  fun removeRoom(roomName: String) {
    validRooms.remove(roomName)
  }

  fun addRoom(roomName: String) {
    validRooms.add(roomName)
  }

  fun getValidRooms(): List<String> = allowedRooms

  fun removeUser(roomName: String, nick: SocketNick) {
    rooms[roomName]?.connectedUsers?.removeNick(nick)
  }
}
