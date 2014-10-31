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

package com.despegar.metrik.web.service.influx

import com.despegar.metrik.model.StatisticSummary
import com.despegar.metrik.store.{ StatisticSummarySupport, MetaSupport }
import com.despegar.metrik.web.service.influx.parser._
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

trait InfluxQueryResolver extends MetaSupport with StatisticSummarySupport {
  this: InfluxService ⇒

  import InfluxQueryResolver._

  lazy val parser = new InfluxQueryParser

  def search(query: String): Future[Seq[InfluxSeries]] = {
    log.info(s"Starting Influx query [$query]")

    if (ListSeries.equalsIgnoreCase(query)) {
      log.info("Listing series...")
      metaStore.retrieveMetrics.map(results ⇒ results.map(x ⇒ new InfluxSeries(x.name)))
    } else {
      log.info(s"Executing query [$query]")
      parser.parse(query) map {
        influxCriteria ⇒
          val slice = buildSlice(influxCriteria.filters)

          val timeWindow: FiniteDuration = influxCriteria.groupBy.duration
          val metricName: String = influxCriteria.table.name
          val maxResults: Int = influxCriteria.limit.getOrElse(Int.MaxValue)

          summaryStore.readAll(timeWindow, metricName, slice.from, slice.to, maxResults) map {
            results ⇒ Seq(toInfluxSeries(results, influxCriteria.projection, metricName))
          }
      }
    }.getOrElse(throw new UnsupportedOperationException(s"Unsupported query [$query]"))
  }

  private def toInfluxSeries(summaries: Seq[StatisticSummary], projection: Projection, key: String): InfluxSeries = {
    log.info(s"Building Influx series: Key $key - Projection: $projection - Summaries count: ${summaries.size}")

    projection match {
      case Field(name, _) ⇒ {

        val points = summaries.foldLeft(Vector.empty[Vector[Long]]) {
          (acc, current) ⇒
            acc :+ Vector(toSeconds(current.timestamp), current.get(name)) //TODO: should avoid reflection strategy?
        }
        InfluxSeries(key, Vector(influxTimeKey, name), points)
      }
      case everythingElse ⇒ InfluxSeries("none")
    }
  }

  private def buildSlice(filters: List[Filter]): Slice = {
    var from = -1L
    var to = System.currentTimeMillis()
    filters foreach {
      case filter: IntervalFilter ⇒ {
        filter.operator match {
          case Operators.Gt  ⇒ from = filter.value + 1
          case Operators.Gte ⇒ from = filter.value
          case Operators.Lt  ⇒ to = filter.value - 1
          case Operators.Lte ⇒ to = filter.value
        }
      }
      case StringFilter(_, _, _) ⇒ //TODO
    }
    Slice(from, to)
  }

  private def toSeconds(millis: Long): Long = {
    TimeUnit.MILLISECONDS.toSeconds(millis)
  }
}

object InfluxQueryResolver {
  val ListSeries = "list series"

  val influxTimeKey = "time"

  case class Slice(from: Long, to: Long)

}