package com.quizling.db

import com.quizling.shared.entities.MatchReport
import com.typesafe.config.ConfigFactory
import org.bson.codecs.configuration.CodecRegistries.{fromProviders, fromRegistries}
import org.mongodb.scala.bson.codecs.Macros._
import org.mongodb.scala.{MongoClient, MongoCollection}

// Should abstract the DbCreator as an abstract class then implement an instance specific to Mongo
// Also should make this more obvious/easier to use...this is awful
object DbCreator {
  private val DB_CONFIG_PATH = "system.db"
  private val CODEC = fromRegistries(fromProviders(classOf[MatchReport]), MongoClient.DEFAULT_CODEC_REGISTRY)

  def connectToMatchResultCollection(): MongoCollection[MatchReport] = {
    val dbSettings = ConfigFactory.load().getConfig(DB_CONFIG_PATH)
    val connectionHost = dbSettings.getString("host")
    val dbName = dbSettings.getString("name")
    val collectionName = dbSettings.getString("match.result.collection")

    val mongoClient: MongoClient = MongoClient(s"mongodb://$connectionHost")
    val database = mongoClient.getDatabase(dbName).withCodecRegistry(CODEC)
    database.getCollection[MatchReport](collectionName)
  }
}
