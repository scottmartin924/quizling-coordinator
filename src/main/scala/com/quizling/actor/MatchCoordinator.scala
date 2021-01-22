package com.quizling.actor

import java.util.UUID

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.quizling.actor.Director.{DirectorCommand, MatchConfiguration}
import com.quizling.actor.MatchCoordinator._
import com.quizling.actor.QuestionCoordinator.{Answer, AnswerQuestionRequest, Question, QuestionEvent}
import com.quizling.actor.SocketEvent.{SocketMessage, WebsocketEvent}

import scala.concurrent.duration.FiniteDuration

object MatchCoordinator {
  final val DEFAULT_SCORE = 0

  def apply(matchId: String,
            matchConfiguration: MatchConfiguration,
            director: ActorRef[DirectorCommand],
            socketWriter: ActorRef[WebsocketEvent]): Behavior[MatchCoordinatorEvent] =
    Behaviors.setup(ctx => new MatchCoordinator(ctx, matchId, matchConfiguration, director, socketWriter))

  sealed trait MatchCoordinatorEvent
  final case class AnswerQuestion(answerId: String, questionId: String, participantId: String, answer: Answer) extends MatchCoordinatorEvent
  final case object RequestQuizQuestion extends MatchCoordinatorEvent

  sealed trait QuestionAction extends MatchCoordinatorEvent
  final case class IncorrectAnswerMessage(questionId: String, answerId: String, participantId: String) extends QuestionAction
  final case class QuestionResolvedMessage(questionId: String, result: QuestionResult) extends QuestionAction
  final case class QuestionReadyMessage(questionId: String, questionConfig: QuestionConfiguration) extends QuestionAction

  // Match coordinator domain objects
  final case class MatchParticipant(participantId: String)
  final case class QuestionConfiguration(question: Question, timer: Option[FiniteDuration] = None)
  final case class QuestionResult(answeredCorrectly: Boolean,
                                  answeredBy: Option[String] = None,
                                  timedOut: Boolean = false
                                 )
}

class MatchCoordinator(val ctx: ActorContext[MatchCoordinatorEvent], matchId: String,
                       val matchConfiguration: MatchConfiguration,
                       val director: ActorRef[DirectorCommand],
                       val socketWriter: ActorRef[WebsocketEvent])
  extends AbstractBehavior[MatchCoordinatorEvent](ctx) {

  private val participants: Set[String] = matchConfiguration.participants.map(_.participantId)
  private var score: Map[String, Int] = (for (participant <- matchConfiguration.participants; id = participant.participantId)
    yield { id -> MatchCoordinator.DEFAULT_SCORE }).toMap

  private var quizQuestions = matchConfiguration.quiz.questions
  private var activeQuestions = Map.empty[String, ActorRef[QuestionEvent]]
  private var completedQuestions = List.empty[QuestionResult]

  // Request first quiz question
  context.self ! RequestQuizQuestion

  override def onMessage(msg: MatchCoordinatorEvent): Behavior[MatchCoordinatorEvent] = {
    msg match {
      case RequestQuizQuestion => {
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
          // TODO Send socket message with score and summary and send message to director with information for db write (actually maybe put that in a poststop???)
          // In order to do that need to settle on protocol of messages
          ctx.log.info(s"Match $matchId complete")
          socketWriter ! SocketMessage(s"Match $matchId complete")
          Behaviors.stopped
        }
      }

      case QuestionReadyMessage(questionId, config) => {
        context.log.info(s"Question $questionId for match $matchId ready")
        activeQuestions.get(questionId).fold
          { context.log.error(s"Question $questionId for match $matchId not found in active questions") }
          { qu =>
            // FIXME Should send socket message indicating how to configure the question for the UI
            socketWriter ! SocketMessage(s"New message ready!! ${config.question.questionText}")
          }
        this
      }

      case AnswerQuestion(answerId, questionId, participantId, answer) => {
        activeQuestions.get(questionId).fold {
          context.log.error(s"Question $questionId is not currently active to accept answer $answerId")
        }{ qu =>
          context.log.info(s"Answer $answerId for question $questionId being passed to actor $qu")
          qu ! AnswerQuestionRequest(questionId, answerId, participantId, answer)
        }
        this
      }

      case QuestionResolvedMessage(questionId, result @ QuestionResult(true, answeredBy, timedOut)) => {
        context.log.info(s"Question $questionId correctly answered by $answeredBy")
        val answerer = answeredBy.get // Bad practice here...should fix
        val currentScore = score.getOrElse(answerer, MatchCoordinator.DEFAULT_SCORE)
        score += answerer -> (currentScore + 1)
        resolveQuestion(result)
        this
      }

      case QuestionResolvedMessage(questionId, result @ QuestionResult(false, _, _)) => {
        context.log.info(s"Question $questionId not answered correctly")
        completedQuestions = completedQuestions :+ result
        resolveQuestion(result)
        this
      }

      case IncorrectAnswerMessage(questionId, answerId, participantId) => {
        context.log.info(s"Incorrect answer $answerId for question $questionId by participant $participantId")
        // FIXME Send message indicating answer incorrect
        this
      }
    }
  }

  // Resolve question by adding result to completedQuestions and request next question (eventually might split this into a db write as well)
  private def resolveQuestion(result: QuestionResult): Unit = {
    completedQuestions = completedQuestions :+ result
    context.self ! RequestQuizQuestion
  }
}
