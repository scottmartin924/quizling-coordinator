package com.quizling.actor

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.quizling.actor.MatchCoordinator.{IncorrectAnswerMessage, QuestionAction, QuestionConfiguration, QuestionReadyMessage, QuestionResolvedMessage, QuestionResult}
import com.quizling.actor.QuestionCoordinator.{AnswerQuestionRequest, ProposedAnswer, QuestionEvent, QuestionTimeout, QuizAnswer}

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
}

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
      // FIXME This is awful...I think can use deeper pattern matching to help
      case AnswerQuestionRequest(`questionId`, participantId, proposedAnswer) => {
        if (participantAnswers.contains(participantId)) {
          context.log.info(s"Question $questionId answered by $participantId being ignored since participant has already answered")
          this
        } else {
          // If correct answer
          if (proposedAnswer.answerId == correctAnswer.answerId) {
            context.log.info(s"Question $questionId answered correctly by answer ${proposedAnswer.answerId}")
            participantAnswers += (participantId -> proposedAnswer)
            val questionResult = QuestionResult(answeredCorrectly = true, answeredBy = Some(participantId), answer = correctAnswer)
            requester ! QuestionResolvedMessage(questionId, questionResult)
            Behaviors.stopped
          } else {
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
            } else {
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
