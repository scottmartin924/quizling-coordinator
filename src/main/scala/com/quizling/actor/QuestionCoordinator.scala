package com.quizling.actor

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.quizling.actor.MatchCoordinator.{IncorrectAnswerMessage, QuestionAction, QuestionReadyMessage, QuestionResolvedMessage}
import com.quizling.actor.QuestionCoordinator.{AnswerQuestionRequest, ProposedAnswer, QuestionConfiguration, QuestionEvent, QuestionResult, QuestionTimeout, QuizAnswer}

import scala.concurrent.duration.FiniteDuration

/**
 * QuestionCoordinator actor companion object to setup the actor and
 * to story the event classes the actor will receive
 */
object QuestionCoordinator {
  def apply(questionId: String,
            questionConfig: QuestionConfiguration,
            participants: Set[String],
            questionRequester: ActorRef[QuestionAction]): Behavior[QuestionEvent] =
    Behaviors.setup(ctx =>
      Behaviors.withTimers { timers =>
        new QuestionCoordinator(ctx, questionId, questionConfig, participants, timers, questionRequester)
      })

  final case class QuizAnswer(answerId: String, answerText: String, isCorrect: Boolean)
  final case class Question(questionText: String, answers: Seq[QuizAnswer])

  // This is used to represent an answer a client has sent in, but has not been confirmed yet
  final case class ProposedAnswer(answerId: String, answerText: String)

  sealed trait QuestionEvent
  final case class AnswerQuestionRequest(questionId: String, participantId: String, answer: ProposedAnswer) extends QuestionEvent
  private final case object QuestionTimeout extends QuestionEvent

  final case class QuestionConfiguration(question: Question, timer: Option[FiniteDuration] = None)
  final case class QuestionResult(answeredCorrectly: Boolean,
                                  answeredBy: Option[String] = None,
                                  answer: QuizAnswer,
                                  timedOut: Boolean = false
                                 )
}

/**
 * Actor to manage quiz questions; determines if answers are correct, handles question timeouts
 * @param ctx the actor system context
 * @param questionId the id of the question
 * @param config the question configuration including the question, answers, and timeout
 * @param participants the participants eligible to answer this question
 * @param timers system timers to handle timing question and ending question when time expires
 * @param requester the actor that spawned this question. Question results and events will be sent to this actor
 */
class QuestionCoordinator(ctx: ActorContext[QuestionEvent],
                          questionId: String,
                          config: QuestionConfiguration,
                          participants: Set[String],
                          timers: TimerScheduler[QuestionEvent],
                          requester: ActorRef[QuestionAction])
  extends AbstractBehavior[QuestionEvent](ctx) {

  // Store participant answers (incorrect or correct)
  private var participantAnswers = Map.empty[String, ProposedAnswer]

  // If timer configuration set then start timer
  config.timer.foreach(time => timers.startSingleTimer(QuestionTimeout, time))
  requester ! QuestionReadyMessage(questionId, config)

  // Find correct answer (note this assumes only one correct answer would need to adjust for multiple correct answers)
  private val correctAnswer: QuizAnswer = config.question.answers.filter(_.isCorrect).head


  override def onMessage(msg: QuestionEvent): Behavior[QuestionEvent] = {
    msg match {
      // NOTE: This could probably be written cleaner...so many nested if/else doesn't feel very scala-like
      case AnswerQuestionRequest(`questionId`, participantId, proposedAnswer) => {
        if (participantAnswers.contains(participantId)) {
          context.log.info(s"Question $questionId answered by $participantId being ignored since participant has already answered")
          this
        }
        else {
          // If correct answer
          if (proposedAnswer.answerId == correctAnswer.answerId) {
            context.log.info(s"Question $questionId answered correctly by answer ${proposedAnswer.answerId}")
            participantAnswers += (participantId -> proposedAnswer)
            val questionResult = QuestionResult(answeredCorrectly = true, answeredBy = Some(participantId), answer = correctAnswer)
            requester ! QuestionResolvedMessage(questionId, questionResult)
            Behaviors.stopped
          }
          else {
            context.log.info(s"Question $questionId answered incorrectly by answer ${proposedAnswer.answerId}")
            participantAnswers += (participantId -> proposedAnswer)
            // Set participant as having answered
            participantAnswers += (participantId -> proposedAnswer)
            requester ! IncorrectAnswerMessage(questionId, proposedAnswer.answerId, participantId = participantId)

            // If every participant has answered then send message saying to not wait on timeout and stop
            if (participantAnswers.size == participants.size) {
              val result = QuestionResult(answeredCorrectly = false, answer = correctAnswer, timedOut = false)
              requester ! QuestionResolvedMessage(questionId, result)
              Behaviors.stopped
            }
            else {
              this
            }
          }
        }
      }

      case QuestionTimeout => {
        context.log.info(s"Question $questionId timed out")
        val questionResult = QuestionResult(answeredCorrectly = false, answer = correctAnswer, timedOut = true)
        requester ! QuestionResolvedMessage(questionId, questionResult)
        Behaviors.stopped
      }
    }
  }
}
