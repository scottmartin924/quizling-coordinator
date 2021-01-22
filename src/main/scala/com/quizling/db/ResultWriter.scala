package com.quizling.db

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.quizling.shared.entities.MatchReport

object ResultWriter {
  sealed trait DatabaseCommand
  final case class DatabasePersistRequest(entity: MatchReport) extends DatabaseCommand

  def apply(dbWriter: AbstractDbInsert[MatchReport]): Behavior[DatabaseCommand] = Behaviors.receive{ (ctx, msg) =>
    msg match {
      case DatabasePersistRequest(result) => {
        dbWriter.insert(entity = result,
          onFailure = Some((e: Throwable) => ctx.log.error(s"Could not write result $result to database: $e")))
        Behaviors.same
      }
    }
  }
}
