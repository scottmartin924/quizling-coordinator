package com.quizling.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.quizling.actor.SocketEvent.{SocketMessage, WebsocketEvent}

// TODO Could improve this by allowing list of actors to forward to (although probably could do the same thing better using akka streams?)
// also make the

object SocketEventListener {
  def apply[T](forwardTo: ActorRef[T], messageAdapter: WebsocketEvent => T): Behavior[WebsocketEvent] =
    Behaviors.setup[WebsocketEvent] { ctx =>

      def receive[T](forwardTo: ActorRef[T], adapter: WebsocketEvent => T): Behavior[WebsocketEvent] =
        Behaviors.receiveMessage[WebsocketEvent] {
          case smsg @ SocketMessage(msg) => {
            ctx.log.info(s"Socket message for actor $forwardTo. Message: $msg")
            forwardTo ! adapter(smsg)
            Behaviors.same
          }
            // FIXME Handle these two cases
          case SocketEvent.Complete =>
            ctx.log.info("Socket complete")
            Behaviors.same
          case SocketEvent.Failure(ex) =>
            ctx.log.error(s"Socket failure: $ex")
            Behaviors.same
        }

      receive(forwardTo, messageAdapter)
    }
}
