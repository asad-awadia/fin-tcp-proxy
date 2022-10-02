package dev.aawadia

import io.vertx.core.http.ServerWebSocket
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

data class ServerChatRoom(
  val connectedUsers: ConnectedUsers = ConnectedUsers(),
  val createTime: LocalDateTime = LocalDateTime.now()
)

class ConnectedUsers {
  private val nickToSocket: ConcurrentHashMap<SocketNick, ServerWebSocket> = ConcurrentHashMap()
  private val socketToNick: ConcurrentHashMap<ServerWebSocket, SocketNick> = ConcurrentHashMap()

  fun addUser(serverWebSocket: ServerWebSocket, nick: SocketNick) {
    nickToSocket[nick] = serverWebSocket
    socketToNick[serverWebSocket] = nick
  }

  fun removeNick(nick: SocketNick) {
    val serverWebSocket = nickToSocket.remove(nick)
    serverWebSocket?.let { socketToNick.remove(serverWebSocket) }.also { serverWebSocket?.close() }
  }

  fun removeSocket(serverWebSocket: ServerWebSocket) {
    val nickToRemove = socketToNick.remove(serverWebSocket)
    nickToRemove?.let { nickToSocket.remove(nickToRemove) }
  }

  fun nickExists(nick: SocketNick): Boolean = nickToSocket.containsKey(nick)

  fun getSockets(): Sequence<ServerWebSocket> = socketToNick.keys.asSequence()

  fun getCount(): Int = socketToNick.size
}