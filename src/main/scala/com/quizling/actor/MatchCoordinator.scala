package com.quizling.actor

import java.util.UUID

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.quizling.actor.Director.{DirectorCommand, MatchConfiguration}
import com.quizling.actor.MatchCoordinator._
import com.quizling.actor.QuestionCoordinator.{AnswerQuestionRequest, ProposedAnswer, Question, QuestionEvent, QuizAnswer}
import com.quizling.shared.dto.socket.Protocol._

import scala.concurrent.duration.FiniteDuration

object MatchCoordinator {
  final val DEFAULT_SCORE = 0

  def apply(matchId: String,
            matchConfiguration: MatchConfiguration,
            director: ActorRef[DirectorCommand],
            socketWriter: ActorRef[ServerEvent]): Behavior[MatchCoordinatorEvent] =
    Behaviors.setup(ctx => new MatchCoordinator(ctx, matchId, matchConfiguration, director, socketWriter))

  sealed trait MatchCoordinatorEvent
  final case class AnswerQuestion(questionId: String, participantId: String, answerId: String, answer: String) extends MatchCoordinatorEvent
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
                                  answer: QuizAnswer,
                                  timedOut: Boolean = false
                                 )
}

class MatchCoordinator(val ctx: ActorContext[MatchCoordinatorEvent], matchId: String,
                       val matchConfiguration: MatchConfiguration,
                       val director: ActorRef[DirectorCommand],
                       val socketWriter: ActorRef[ServerEvent])
  extends AbstractBehavior[MatchCoordinatorEvent](ctx) {

  private val participants: Set[String] = matchConfiguration.participants.map(_.participantId)
  private var score: Map[String, Int] = (for (participant <- matchConfiguration.participants; id = participant.participantId)
    yield { id -> MatchCoordinator.DEFAULT_SCORE }).toMap

  // Need to hydrate answers with answer ids
  private var quizQuestions: Seq[Question] = matchConfiguration.quiz.questions
  private var activeQuestions = Map.empty[String, ActorRef[QuestionEvent]]
  private var completedQuestions = List.empty[QuestionResult]

  // FIXME There might be a race condition here between requesting first question and users connecting to the websocket
  // Consider: I *think* you can send messages along with your socket connect events. Match coordinator could track then and not start until everybody has checked in
  // Even if it doesn't allow that option we could manually push a userconnectedevent if needed (would need to add that event somewhere)...I think that's an okay way to handle it

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
          val questionConfiguration = QuestionConfiguration(question, matchConfiguration.timer)
          val questionRef = context.spawn(QuestionCoordinator(questionId, questionConfiguration, participants, context.self), name = questionId)
          activeQuestions += questionId -> questionRef
          // Take all quizQuestions except the head
          quizQuestions = quizQuestions.tail
          this
        } else {
          // In order to do that need to settle on protocol of messages
          ctx.log.info(s"Match $matchId complete")
          socketWriter ! MatchCompleteEvent(matchId, score)
          socketWriter ! SocketEvent.Complete
          Behaviors.stopped
        }
      }

      case QuestionReadyMessage(questionId, config) => {
        context.log.info(s"Question $questionId for match $matchId ready")
        activeQuestions.get(questionId).fold
          { context.log.error(s"Question $questionId for match $matchId not found in active questions") }
          { _ =>
            val question = config.question
            val timer: Option[Int] = config.timer.fold[Option[Int]] { None } { t => Some(t.toSeconds.toInt) }
            socketWriter ! QuestionSocketDto(questionId = questionId,
              questionText = question.questionText,
              answers = question.answers.map(ans => AnswerSocketDto(ans.answerId, ans.answerText)),
              timer = timer
            )
          }
        this
      }

      case AnswerQuestion(questionId, participantId, answerId, answer) => {
        activeQuestions.get(questionId).fold {
          context.log.error(s"Question $questionId is not currently active to accept answer '$answer''")
        }{ qu =>
          context.log.info(s"Answer '$answer' for question $questionId being passed to actor $qu")
          qu ! AnswerQuestionRequest(questionId, participantId, ProposedAnswer(answerId, answer))
        }
        this
      }

        // Question answered correctly
      case QuestionResolvedMessage(questionId, result @ QuestionResult(true, answeredBy, correctAnswer, timedOut)) => {
        context.log.info(s"Question $questionId correctly answered by $answeredBy")
        val answerer = answeredBy.get // Bad practice here...should fix
        val currentScore = score.getOrElse(answerer, MatchCoordinator.DEFAULT_SCORE)
        score += answerer -> (currentScore + 1)
        socketWriter ! AnswerCorrectEvent(questionId = questionId,
          answererId = answerer,
          correctAnswer = AnswerSocketDto(correctAnswer.answerId, correctAnswer.answerText),
          score = score
        )
        resolveQuestion(result)
        this
      }

        // Question timed out (or all participants answered incorrectly)
      case QuestionResolvedMessage(questionId, result @ QuestionResult(false, _, correctAnswer, _)) => {
        context.log.info(s"Question $questionId not answered correctly")
        completedQuestions = completedQuestions :+ result
        resolveQuestion(result)
        socketWriter ! QuestionTimeOutEvent(questionId = questionId,
          correctAnswer = AnswerSocketDto(correctAnswer.answerId, correctAnswer.answerText),
          score = score)
        this
      }

      case IncorrectAnswerMessage(questionId, answerId, participantId) => {
        context.log.info(s"Incorrect answer $answerId for question $questionId by participant $participantId")
        socketWriter ! AnswerIncorrectEvent(questionId = questionId, incorrectAnswerId = answerId, participantId = participantId)
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
