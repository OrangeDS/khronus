/*
 * =========================================================================================
 * Copyright © 2014 the metrik project <https://github.com/hotels-tech/metrik>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 * =========================================================================================
 */

package com.despegar.metrik.influx

import com.despegar.metrik.influx.parser.Projection
import com.despegar.metrik.model.{ Functions, StatisticSummary }
import com.despegar.metrik.store.{ Slice, StatisticSummarySupport, MetaSupport }
import com.despegar.metrik.influx.parser._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import scala.collection.concurrent.TrieMap

trait InfluxQueryResolver extends MetaSupport with StatisticSummarySupport {
  this: InfluxEndpoint ⇒

  import InfluxQueryResolver._

  lazy val parser = new InfluxQueryParser

  def search(query: String): Future[Seq[InfluxSeries]] = {
    log.info(s"Starting Influx query [$query]")

    if (ListSeries.equalsIgnoreCase(query)) {
      log.info("Listing series...")
      metaStore.retrieveMetrics.map(results ⇒ results.map(x ⇒ new InfluxSeries(x.name)))
    } else {
      log.info(s"Executing query [$query]")

      val influxCriteria = parser.parse(query)

      val slice = buildSlice(influxCriteria.filters, influxCriteria.orderAsc)
      val timeWindow: FiniteDuration = influxCriteria.groupBy.duration
      val metricName: String = influxCriteria.table.name
      val maxResults: Int = influxCriteria.limit.getOrElse(Int.MaxValue)

      summaryStore.readAll(timeWindow, metricName, slice, maxResults) map {
        results ⇒ toInfluxSeries(results, influxCriteria.projections, metricName)
      }

    }
  }

  private def toInfluxSeries(summaries: Seq[StatisticSummary], projections: Seq[Projection], metricName: String): Seq[InfluxSeries] = {
    log.info(s"Building Influx series: Metric $metricName - Projections: $projections - Summaries count: ${summaries.size}")
    val functions = projections.collect({
      case Field(name, _) ⇒ Seq(name)
      case AllField()     ⇒ Functions.allNames
    }).flatten

    buildInfluxSeries(summaries, metricName, functions)
  }

  def buildInfluxSeries(summaries: Seq[StatisticSummary], metricName: String, functions: Iterable[String]): Seq[InfluxSeries] = {
    val pointsPerFunction = TrieMap[String, Vector[Vector[Long]]]()

    summaries.foreach(summary ⇒ {
      functions.foreach(function ⇒ {
        pointsPerFunction.put(function, pointsPerFunction.getOrElse(function, Vector.empty) :+ Vector(toSeconds(summary.timestamp.ms), summary.get(function)))
      })
    })

    pointsPerFunction.collect {
      case (functionName, points) ⇒ InfluxSeries(metricName, Vector(influxTimeKey, functionName), points)
    }.toSeq
  }

  private def buildSlice(filters: List[Filter], ascendingOrder: Boolean): Slice = {
    var from = -1L
    var to = System.currentTimeMillis()
    filters foreach {
      case filter: TimeFilter ⇒ {
        filter.operator match {
          case Operators.Gt  ⇒ from = filter.value + 1
          case Operators.Gte ⇒ from = filter.value
          case Operators.Lt  ⇒ to = filter.value - 1
          case Operators.Lte ⇒ to = filter.value
        }
      }
      case StringFilter(_, _, _) ⇒ //TODO
    }

    if (ascendingOrder)
      Slice(from, to, false)
    else
      Slice(to, from, true)
  }

  private def toSeconds(millis: Long): Long = {
    TimeUnit.MILLISECONDS.toSeconds(millis)
  }
}

object InfluxQueryResolver {
  val ListSeries = "list series"
  val influxTimeKey = "time"
}