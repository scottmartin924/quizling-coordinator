package com.quizling

import java.util.UUID

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import com.quizling.Director.MatchConfiguration
import com.quizling.MatchCoordinator.{AnswerQuestion, IncorrectAnswerMessage, MatchCoordinatorCommand, QuestionConfiguration, QuestionReadyMessage, QuestionResolvedMessage, QuestionResult, RequestQuizQuestion}
import com.quizling.QuestionCoordinator.{Answer, AnswerQuestionRequest, Question, QuestionCommand}

import scala.concurrent.duration.FiniteDuration

object MatchCoordinator {
  final val DEFAULT_SCORE = 0

  def apply(matchId: String, matchConfiguration: MatchConfiguration): Behavior[MatchCoordinatorCommand] =
    Behaviors.setup(ctx => new MatchCoordinator(ctx, matchId, matchConfiguration))

  sealed trait MatchCoordinatorCommand
  final case class AnswerQuestion(answerId: String, questionId: String, participantId: String, answer: Answer) extends MatchCoordinatorCommand
  final case class RequestQuizQuestion() extends MatchCoordinatorCommand

  sealed trait QuestionAction extends MatchCoordinatorCommand
  final case class IncorrectAnswerMessage(questionId: String, answerId: String, participantId: String) extends QuestionAction
  final case class QuestionResolvedMessage(questionId: String, result: QuestionResult) extends QuestionAction
  final case class QuestionReadyMessage(questionId: String, questionConfig: QuestionConfiguration) extends QuestionAction

  // Match coordinator domain objects
  final class MatchParticipant(val participantId: String)
  final class QuestionConfiguration(val question: Question, val timer: Option[FiniteDuration] = None)
  final case class QuestionResult(answeredCorrectly: Boolean,
                                  answeredBy: Option[String] = None,
                                  timedOut: Boolean = false
                                 )
}

class MatchCoordinator(ctx: ActorContext[MatchCoordinatorCommand], matchId: String, matchConfiguration: MatchConfiguration)
  extends AbstractBehavior[MatchCoordinatorCommand](ctx) {

  // What to do on startup...namely spin up the participants actors then create the first question from the quiz
  // FIXME Decide if we want participant actors or now, for now no
//  private var participants = for (participant <- matchConfiguration.participants)
//    yield participant.participantId -> ctx.spawn(Participant(), participant.participantId)

  private val participants = (matchConfiguration.participants.map(_.participantId))

  // FIXME this is hideous, but works for now...need the for comprehension to return a map which I'm not sure it can
  private var score = (for (participant <- matchConfiguration.participants; id = participant.participantId)
    yield { id -> MatchCoordinator.DEFAULT_SCORE }).toMap

  private var quizQuestions = matchConfiguration.quiz.questions
  private var activeQuestions = Map.empty[String, ActorRef[QuestionCommand]]
  private var completedQuestions = List.empty[QuestionResult]

  // Request first quiz question
  requestQuestion()

  override def onMessage(msg: MatchCoordinatorCommand): Behavior[MatchCoordinatorCommand] = {
    msg match {
      case RequestQuizQuestion() => {
        // If no more quiz questions then done
        if (!quizQuestions.isEmpty) {
          val questionId = UUID.randomUUID().toString
          ctx.log.info(s"Starting question $questionId for match $matchId")
          val question = quizQuestions.head
          val questionConfiguration = new QuestionConfiguration(question, matchConfiguration.timer)
          val questionRef = context.spawn(QuestionCoordinator(questionId, questionConfiguration, participants, context.self), name = questionId)
          activeQuestions += questionId -> questionRef
          // Take all quizQuestions except the head
          quizQuestions = quizQuestions.tail
          this
        } else {
          ctx.log.info(s"Match $matchId complete")
          // TODO Send socket message with score and summary and send message to director with information for db write (actually maybe put that in a poststop???
          Behaviors.stopped
        }
      }

      case QuestionReadyMessage(questionId, config) => {
        context.log.info(s"Question $questionId for match $matchId ready")
        if (activeQuestions.contains(questionId)) {
          // TODO Send socket message with question info
        } else {
          context.log.info(s"Question $questionId for match $matchId not found in active questions")
        }
        this
      }

      case AnswerQuestion(answerId, questionId, participantId, answer) => {
        activeQuestions.get(questionId).fold {
          context.log.info(s"Question $questionId is not currently active for answer $answerId")
          // TODO Send socket message?
          this
        }{ qu =>
          context.log.info(s"Answer $answerId for question $questionId being passed to actor $qu")
          qu ! AnswerQuestionRequest(questionId, answerId, participantId, answer)
          this
        }
      }

      case QuestionResolvedMessage(questionId, result @ QuestionResult(answeredCorrectly, answeredBy, timedOut)) => {
        // TODO Consider if want to pattern match against answeredCorrectly...definitely could but some duplicated code
        if (answeredCorrectly) {
          context.log.info(s"Question $questionId correctly answered by $answeredBy")
          val answerer = answeredBy.get // Bad practice here...should fix
          val currentScore = score.get(answerer).getOrElse(MatchCoordinator.DEFAULT_SCORE)
          score += answerer -> (currentScore + 1)
        } else {
          context.log.info(s"Question $questionId not answered correctly")
        }
        completedQuestions = completedQuestions :+ result
        requestQuestion()
        this
      }

      case IncorrectAnswerMessage(questionId, answerId, participantId) => {
        context.log.info(s"Incorrect answer $answerId for question $questionId by participant $participantId")
        this
      }

      case _ => {
        println("Catch all")
        Behaviors.unhandled
      }
    }
  }

  private def requestQuestion(): Unit = {
    context.self ! RequestQuizQuestion()
  }
}
