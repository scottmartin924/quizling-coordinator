package com.quizling.actor

// FIXME Will need to add things to these once we know what messages we'll send
object SocketEvent {
  sealed trait WebsocketEvent
  final case class SocketMessage(message: String) extends WebsocketEvent
  final case object Complete extends WebsocketEvent
  final case class Failure(ex: Throwable) extends WebsocketEvent
}

object Protocol {

}


