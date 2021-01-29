package com.quizling.actor

import java.util.UUID

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.quizling.actor.Director.{DirectorCommand, MatchCompleted, MatchConfiguration, MatchResult}
import com.quizling.actor.MatchCoordinator._
import com.quizling.actor.QuestionCoordinator.{AnswerQuestionRequest, ProposedAnswer, Question, QuestionEvent, QuizAnswer}
import com.quizling.shared.dto.socket.Protocol._

import scala.concurrent.duration._



object MatchCoordinator {
  def apply(matchId: String,
            matchConfiguration: MatchConfiguration,
            director: ActorRef[DirectorCommand],
            socketWriter: ActorRef[ServerEvent]): Behavior[MatchCoordinatorEvent] =
    Behaviors.setup(ctx =>
      Behaviors.withTimers { timers =>new MatchCoordinator(ctx, matchId, matchConfiguration, director, socketWriter, timers) }
      )

  sealed trait MatchCoordinatorEvent
  final case class AnswerQuestion(questionId: String, participantId: String, answerId: String, answer: String) extends MatchCoordinatorEvent
  final case object RequestQuizQuestion extends MatchCoordinatorEvent
  final case class ParticipantReadyEvent(participantId: String) extends MatchCoordinatorEvent

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
                       val socketWriter: ActorRef[ServerEvent],
                       val timers: TimerScheduler[MatchCoordinatorEvent])
  extends AbstractBehavior[MatchCoordinatorEvent](ctx) {

  final val DEFAULT_SCORE = 0

  // This could be in a config file or even configurable per match, but they're here for now
  final val DURATION_BETWEEN_QUESTIONS = 3.seconds // Time after a completed question to send the next question

  // Stores which participants are ready for the match to start (NOTE: Will wait for all participants to join, there is no "timeout" currently)
  private var participantsActive: Set[String] = Set.empty
  private var score: Map[String, Int] = (for (participant <- matchConfiguration.participants; id = participant.participantId)
    yield { id -> DEFAULT_SCORE }).toMap

  // Need to hydrate answers with answer ids
  private var quizQuestions: Seq[Question] = matchConfiguration.quiz.questions
  private var activeQuestions = Map.empty[String, ActorRef[QuestionEvent]]
  private var completedQuestions = List.empty[QuestionResult]

  override def onMessage(msg: MatchCoordinatorEvent): Behavior[MatchCoordinatorEvent] = {
    msg match {
      case RequestQuizQuestion => {
        // If no more quiz questions then done
        if (quizQuestions.nonEmpty) {
          val questionId = UUID.randomUUID().toString
          ctx.log.info(s"Starting question $questionId for match $matchId")
          val question = quizQuestions.head
          val questionConfiguration = QuestionConfiguration(question, matchConfiguration.timer)
          val questionRef = context.spawn(QuestionCoordinator(questionId, questionConfiguration, participantsActive, context.self), name = questionId)
          activeQuestions += questionId -> questionRef
          // Take all quizQuestions except the head
          quizQuestions = quizQuestions.tail
          this
        } else {
          // In order to do that need to settle on protocol of messages
          ctx.log.info(s"Match $matchId complete")
          // Send matchcomplete info to socket, complete the socket, send matchcomplete info to director
          socketWriter ! MatchCompleteEvent(matchId, score)
          socketWriter ! SocketEvent.Complete
          director ! MatchCompleted(matchId = matchId, matchResult = MatchResult(score))
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
        val currentScore = score.getOrElse(answerer, DEFAULT_SCORE)
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

      case ParticipantReadyEvent(participantId) => {
        // If participant in participant list mark them active and if everybody here then start match
        if (matchConfiguration.participants.map(_.participantId).contains(participantId)) {
          context.log.info(s"Participant '$participantId' ready for match to begin")
          participantsActive += participantId
          if (participantsActive.size == matchConfiguration.participants.size)
            context.self ! RequestQuizQuestion
        } else {
          context.log.info(s"Participant '$participantId' not found for match '$matchId'")
        }
        this
      }
    }
  }

  // Resolve question by adding result to completedQuestions and request next question (eventually might split this into a db write as well)
  private def resolveQuestion(result: QuestionResult): Unit = {
    completedQuestions = completedQuestions :+ result
    // Start timer before sending next question
    timers.startSingleTimer(RequestQuizQuestion, DURATION_BETWEEN_QUESTIONS)
  }
}
