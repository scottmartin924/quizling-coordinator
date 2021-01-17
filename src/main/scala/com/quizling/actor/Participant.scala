package com.quizling.actor

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}

object Participant {
  def apply(): Behavior[String] = Behaviors.setup(ctx => new Participant(ctx))
}

class Participant(ctx: ActorContext[String]) extends AbstractBehavior[String](ctx) {
  override def onMessage(msg: String): Behavior[String] = Behaviors.unhandled
}
