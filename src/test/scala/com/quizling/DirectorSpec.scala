////#full-example
//package com.quizling
//
//import akka.actor.testkit.typed.Effect.Spawned
//import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit}
//import org.scalatest.wordspec.AnyWordSpecLike
//import com.quizling.TestData.TestMatchConfiguration
//import com.quizling.actor.Director.RespondMatchQueryCoordinator
//import com.quizling.actor.{Director, MatchCoordinator}
//
//class DirectorSpec
//  extends ScalaTestWithActorTestKit
//    with AnyWordSpecLike {
//
//  "A director" must {
//    "start and store a match when requested" in {
//      val testKit = BehaviorTestKit(Director())
//      val matchId = "test"
//      testKit.run(Director.CreateMatch(TestMatchConfiguration.matchConfiguration, id = Some(matchId)))
//      val childActor = testKit.expectEffectType[Spawned[MatchCoordinator.MatchCoordinatorEvent]]
//
//      // Expect name of spawned child actor is given matchId
//      childActor.childName shouldBe matchId
//
//      // Expect match found when queried about
//      testKit.run(Director.RetrieveMatchCoordinator(matchId, testKit.ref))
//      testKit.selfInbox().expectMessage(RespondMatchQueryCoordinator(Some(childActor.ref)))
//    }
//  }
//}
