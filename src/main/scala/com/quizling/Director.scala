package com.quizling

import java.util.UUID

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.quizling.Director.{CreateMatch, DirectorCommand, RespondMatchQuery, RetrieveMatch}
import com.quizling.MatchCoordinator.{MatchCoordinatorCommand, MatchParticipant}
import com.quizling.QuestionCoordinator.Question

import scala.concurrent.duration.FiniteDuration

object Director {

  def apply(): Behavior[Director.DirectorCommand] = {
    Behaviors.setup(ctx => new Director(ctx))
  }

  sealed trait DirectorCommand
  final case class CreateMatch(configuration: MatchConfiguration, id: Option[String] = None) extends DirectorCommand
  final case class RetrieveMatch(matchId: String, replyTo: ActorRef[RespondMatchQuery]) extends DirectorCommand
  final case class RespondMatchQuery(actorRef: Option[ActorRef[MatchCoordinatorCommand]]) extends DirectorCommand

  // Director domain objects
  final class Quiz(val questions: Seq[Question])

  final class MatchConfiguration(val participants: Set[MatchParticipant], val quiz: Quiz, val timer: Option[FiniteDuration] = None) {
    require(!participants.isEmpty)
  }
}

class Director(ctx: ActorContext[Director.DirectorCommand]) extends AbstractBehavior[Director.DirectorCommand](ctx) {

  private var activeMatches = Map.empty[String, ActorRef[MatchCoordinatorCommand]]

  // TODO On startup need to create DbWriter actor (and watch it perhaps with special configurtion (maybe not just restart)

  override def onMessage(msg: DirectorCommand): Behavior[DirectorCommand] = {
    // TODO Consider: should we watch the matchcoordinator? Or just have it send notification when done??
    msg match {
      case CreateMatch(configuration, id) => {
        val matchId = id.getOrElse(UUID.randomUUID().toString)
        context.log.info(s"Creating match $matchId")
        val matchCoordinator = ctx.spawn(MatchCoordinator(matchId, configuration), matchId)
        activeMatches += (matchId -> matchCoordinator)
        this
      }

      case RetrieveMatch(matchId, replyTo) => {
        val responseMsg = activeMatches.get(matchId)
          .fold{ RespondMatchQuery(None) } { x => RespondMatchQuery(Some(x)) }
        replyTo ! responseMsg
        this
      }
    }
  }
}
