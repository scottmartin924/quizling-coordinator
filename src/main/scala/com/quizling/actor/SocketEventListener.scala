package com.quizling.actor

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.quizling.shared.dto.socket.Protocol.{SocketDto, SocketEvent}

// TODO Could improve this by allowing list of actors to forward to (although probably could do the same thing better using akka streams?)

/**
 * Actor to listen for socket events, convert them into appropriate
 * actor messages (using the messageAdapter) and forward them to
 * their consuming actor (whose actor ref is given in forwardTo)
 */
object SocketEventListener extends SocketJsonSupport {
  def apply[T](forwardTo: ActorRef[T], messageAdapter: SocketDto => T): Behavior[SocketDto] =
    Behaviors.setup[SocketDto] { ctx =>

      def receive[T](forwardTo: ActorRef[T], adapter: SocketDto => T): Behavior[SocketDto] =
        Behaviors.receiveMessage[SocketDto] {
          case SocketEvent.Complete =>
            ctx.log.info("Socket complete")
            Behaviors.same
          case SocketEvent.Failure(ex) =>
            ctx.log.error(s"Socket failure: $ex")
            Behaviors.same
          case msg: SocketDto => {
            ctx.log.info(s"Socket message for actor $forwardTo. Message $msg")
            forwardTo ! adapter(msg)
            Behaviors.same
          }
        }

      receive(forwardTo, messageAdapter)
    }
}
