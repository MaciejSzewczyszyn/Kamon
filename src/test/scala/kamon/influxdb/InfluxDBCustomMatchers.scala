package kamon.influxdb

import org.scalatest.matchers.{MatchResult, Matcher}

trait InfluxDBCustomMatchers {

  case class LineProtocol(measurement: String, tags: Seq[String], fields: Seq[String], timestamp: Option[String])
  class LineProtocolMatcher(expectedPoint: String) extends Matcher[String] {


    def apply(left: String) = {

      val leftLP = getLineProtocol(left)
      val expectedLP = getLineProtocol(expectedPoint)

      val failureMessage = new StringBuilder

      def measurementMatches = {
        val res = leftLP.measurement == expectedLP.measurement
        if (!res) failureMessage ++= "Measurement did not match. "
        res
      }

      def tagsMatch = {
        val res = leftLP.tags.sorted == expectedLP.tags.sorted
        if (!res) failureMessage ++= "Tags did not match "
        res
      }

      def fieldsMatch = {
        val res = leftLP.fields.sorted == expectedLP.fields.sorted
        if (!res) failureMessage ++= "Fields did not match. "
        res
      }

      def timestampsMatch = {
        val res = leftLP.timestamp == expectedLP.timestamp
        if (!res) failureMessage ++= "Timestamp did not match. "
        res
      }

      MatchResult(
        measurementMatches && tagsMatch && fieldsMatch && timestampsMatch,
        s"${expectedLP.measurement}: ${failureMessage.toString} $left did not match expected result: $expectedPoint.",
        "Line Protocol Point matched expectations"
      )
    }
  }

  def getLineProtocol(point: String): LineProtocol = {
    val parts = point.split(" ")

    val measurementAndTags = parts(0).split(",").toList

    val (measurement, tags): (String, List[String]) = measurementAndTags match {
      case x::xs => (x, xs)
    }

    val metric = parts(1).split(",")
    val timestamp = if(parts(2).nonEmpty) Some(parts(2)) else None
    LineProtocol(measurement, tags, metric, timestamp)
  }

  def matchExpectedLineProtocolPoint(expectedPoint: String) = new LineProtocolMatcher(expectedPoint)

}

object InfluxDBCustomMatchers extends InfluxDBCustomMatchers
