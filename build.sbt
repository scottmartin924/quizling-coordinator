name := "quizling-app"

version := "1.0"

scalaVersion := "2.13.4"

lazy val akkaVersion = "2.6.10"
lazy val akkaHttpVersion = "10.2.2"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  // NOTE: Tried using alpakka but had dependency version problems. Since we don't need to do much here just use mongo drivers
  "org.mongodb.scala" %% "mongo-scala-driver" % "2.9.0",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.1.0" % Test
)
