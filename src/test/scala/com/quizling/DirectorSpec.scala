//#full-example
package com.quizling

import akka.actor.testkit.typed.Effect.Spawned
import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit}
import com.quizling.Director.RespondMatchQuery
import org.scalatest.wordspec.AnyWordSpecLike
import com.quizling.TestData.{ TestMatchConfiguration }

class DirectorSpec
  extends ScalaTestWithActorTestKit
    with AnyWordSpecLike {

  "A director" must {
    "start and store a match when requested" in {
      val testKit = BehaviorTestKit(Director())
      val matchId = "test"
      testKit.run(Director.CreateMatch(TestMatchConfiguration.matchConfiguration, id = Some(matchId)))
      val childActor = testKit.expectEffectType[Spawned[MatchCoordinator.MatchCoordinatorCommand]]

      // Expect name of spawned child actor is given matchId
      childActor.childName shouldBe matchId

      // Expect match found when queried about
      testKit.run(Director.RetrieveMatch(matchId, testKit.ref))
      testKit.selfInbox().expectMessage(RespondMatchQuery(Some(childActor.ref)))
    }
  }
}
