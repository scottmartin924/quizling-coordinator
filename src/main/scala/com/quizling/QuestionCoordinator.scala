package com.quizling

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import com.quizling.MatchCoordinator.{IncorrectAnswerMessage, QuestionAction, QuestionConfiguration, QuestionReadyMessage, QuestionResolvedMessage, QuestionResult}
import com.quizling.QuestionCoordinator.{Answer, AnswerQuestionRequest, QuestionCommand, QuestionTimeout}

object QuestionCoordinator {
  def apply(questionId: String,
            questionConfig: QuestionConfiguration,
            participants: Set[String],
            questionRequester: ActorRef[QuestionAction]): Behavior[QuestionCommand] =
    Behaviors.setup(ctx =>
      Behaviors.withTimers { timers =>
        new QuestionCoordinator(ctx, questionId, questionConfig, participants, timers, questionRequester)
      })

  final case class Answer(answerText: String, isCorrect: Boolean)
  final case class Question(questionText: String, answers: Seq[Answer])

  sealed trait QuestionCommand
  final case class AnswerQuestionRequest(questionId: String, answerId: String, participantId: String, answer: Answer) extends QuestionCommand // FIXME put in the stuff that will come with an answered question
  private final case object QuestionTimeout extends QuestionCommand
}

class QuestionCoordinator(ctx: ActorContext[QuestionCommand],
                          questionId: String,
                          config: QuestionConfiguration,
                          participants: Set[String],
                          timers: TimerScheduler[QuestionCommand],
                          requester: ActorRef[QuestionAction])
  extends AbstractBehavior[QuestionCommand](ctx) {

  // Set all participants to not having answered yet
  var participantAnswers = (for (id <- participants) yield id -> false).toMap

  // If timer configuration set then start timer
  config.timer.foreach(time => timers.startSingleTimer(QuestionTimeout, time))
  requester ! QuestionReadyMessage(questionId, config)

  override def onMessage(msg: QuestionCommand): Behavior[QuestionCommand] = {
    msg match {
      case AnswerQuestionRequest(`questionId`, answerId, participantId, Answer(_, true)) => {
        context.log.info(s"Question $questionId answered correct by answer $answerId")
        val questionResult = QuestionResult(answeredCorrectly = true, answeredBy = Some(participantId))
        requester ! QuestionResolvedMessage(questionId, questionResult)
        Behaviors.stopped
      }

      case AnswerQuestionRequest(`questionId`, answerId, participantId, Answer(_, false)) => {
        context.log.info(s"Question $questionId answered incorrectly by answer $answerId")
        // Set incorrect answer for this participant id
        participantAnswers += (participantId -> true)
        requester ! IncorrectAnswerMessage(questionId, answerId, participantId = participantId)

        // If every participant has answered then send message saying to not wait on timeout and stop
        if (!participantAnswers.exists(x => x._2 == false)) {
          val result = QuestionResult(answeredCorrectly = false, timedOut = false)
          requester ! QuestionResolvedMessage(questionId, result)
          Behaviors.stopped
        }
        this
      }

      case QuestionTimeout => {
        context.log.info(s"Question $questionId timed out")
        val questionResult = QuestionResult(answeredCorrectly = false, timedOut = true)
        requester ! QuestionResolvedMessage(questionId, questionResult)
        Behaviors.stopped
      }
    }
  }
}
