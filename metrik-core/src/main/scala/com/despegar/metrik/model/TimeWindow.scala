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

package com.despegar.metrik.model

import com.despegar.metrik.model.CounterBucket._
import com.despegar.metrik.model.HistogramBucket._
import com.despegar.metrik.store._
import com.despegar.metrik.util.{ Measurable, Logging }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success }
import scala.concurrent.Future

abstract class TimeWindow[T <: Bucket, U <: Summary] extends BucketStoreSupport[T] with SummaryStoreSupport[U] with MetaSupport with Logging with Measurable {

  def process(metric: Metric, tick: Tick): scala.concurrent.Future[Unit] = measureTime("Process window", metric, duration) {
    log.debug(s"${p(metric, duration)} - Processing time window for ${Tick(tick.bucketNumber ~ duration)}")
    //get the last bucket processed for this winwdow
    val lastProcessed = lastProcessedBucket(metric)

    //retrieve the temporal histogram buckets from previous window
    val previousWindowBuckets = retrievePreviousBuckets(metric, tick, lastProcessed)

    //group histograms in buckets of my window duration
    val groupedHistogramBuckets = groupInBucketsOfMyWindow(previousWindowBuckets, metric)

    //filter out buckets already processed. we don't want to override our precious buckets with late data
    val filteredGroupedHistogramBuckets = filterOutAlreadyProcessedBuckets(groupedHistogramBuckets, metric, lastProcessed)

    val resultingBuckets = aggregateBuckets(filteredGroupedHistogramBuckets, metric)

    //store temporal histogram buckets for next window if needed
    val storeTemporalFuture = storeTemporalBuckets(resultingBuckets, metric)

    //calculate the statistic summaries (percentiles, min, max, etc...)
    val statisticsSummaries = storeTemporalFuture flatMap { _ ⇒ resultingBuckets.map(buckets ⇒ buckets map (getSummary(_))) }

    //store the statistic summaries
    val storeFuture = statisticsSummaries flatMap (summaries ⇒ summaryStore.store(metric, duration, summaries))

    //remove previous histogram buckets
    storeFuture flatMap { _ ⇒ previousWindowBuckets flatMap (windowTuples ⇒ bucketStore.remove(metric, previousWindowDuration, windowTuples.map(_._1))) }
  }

  protected def getSummary(bucket: T): U

  private def storeTemporalBuckets(resultingBuckets: Future[Seq[T]], metric: Metric) = {
    if (shouldStoreTemporalHistograms) {
      resultingBuckets flatMap { buckets ⇒
        bucketStore.store(metric, duration, buckets) andThen {
          case Failure(reason) ⇒ log.error(s"${p(metric, duration)} - Fail to store temporal buckets", reason)
        }
      }
    } else {
      Future.successful[Unit](log.debug(s"${p(metric, duration)} - Last window. No need to store buckets"))
    }
  }

  protected def aggregateBuckets(buckets: Future[Map[BucketNumber, Seq[T]]], metric: Metric): Future[Seq[T]] = measureTime("Aggregate", metric, duration) {
    buckets map (buckets ⇒ buckets.collect { case (bucketNumber, buckets) ⇒ aggregate(bucketNumber, buckets) }.toSeq)
  }

  protected def aggregate(bucketNumber: BucketNumber, buckets: Seq[T]): T

  def duration: Duration

  protected def previousWindowDuration: Duration

  protected def shouldStoreTemporalHistograms: Boolean

  private def retrievePreviousBuckets(metric: Metric, tick: Tick, lastProcessed: Future[BucketNumber]) = {
    lastProcessed flatMap (bucketNumber ⇒
      bucketStore.slice(metric, bucketNumber.startTimestamp(), tick.endTimestamp.alignedTo(duration) - 1, previousWindowDuration) andThen {
        case Success(previousBuckets) ⇒
          log.debug(s"${p(metric, duration)} - Found ${previousBuckets.size} buckets of $previousWindowDuration")
      })
  }

  private def groupInBucketsOfMyWindow(previousWindowBuckets: Future[Seq[(UniqueTimestamp, () ⇒ T)]], metric: Metric): Future[Map[BucketNumber, Seq[T]]] = {
    previousWindowBuckets map (buckets ⇒ buckets.groupBy(tuple ⇒ Timestamp(tuple._1.measurementTimestamp).toBucketNumber(duration)).mapValues(
      seq ⇒ seq.view.map(t ⇒ t._2()))) andThen {
      case Success(buckets) ⇒
        if (!buckets.isEmpty) {
          log.debug(s"${p(metric, duration)} - Grouped ${buckets.size} buckets")
        }
    }
  }

  private def filterOutAlreadyProcessedBuckets(groupedHistogramBuckets: Future[Map[BucketNumber, Seq[T]]], metric: Metric, lastProcessed: Future[BucketNumber]) = {
    lastProcessed flatMap { lastBucket ⇒
      groupedHistogramBuckets map { groups ⇒
        (groups, groups.filter(_._1.number > lastBucket.number))
      }
    } andThen {
      case Success((groups, filteredBuckets)) ⇒ {
        val filteredCount = groups.size - filteredBuckets.size
        if (filteredCount > 0) {
          log.debug(s"${p(metric, duration)} - Filtered out ${filteredCount} already processed buckets")
        }
      }
    } map { _._2 }
  }

  private def lastProcessedBucket(metric: Metric): Future[BucketNumber] = {
    metaStore.getLastProcessedTimestamp(metric) map { lastTS ⇒ Timestamp(lastTS.ms - duration.toMillis).alignedTo(duration).toBucketNumber(duration) } andThen {
      case Success(bucket) ⇒
        log.debug(s"${p(metric, duration)} - Last processed bucket: $bucket")
    }
  }

}

case class CounterTimeWindow(duration: Duration, previousWindowDuration: Duration, shouldStoreTemporalHistograms: Boolean = true)
    extends TimeWindow[CounterBucket, CounterSummary] with CounterBucketStoreSupport with CounterSummaryStoreSupport {

  override def aggregate(bucketNumber: BucketNumber, buckets: Seq[CounterBucket]): CounterBucket = new CounterBucket(bucketNumber, buckets)

  override def getSummary(bucket: CounterBucket): CounterSummary = bucket.summary

}

case class HistogramTimeWindow(duration: Duration, previousWindowDuration: Duration, shouldStoreTemporalHistograms: Boolean = true)
    extends TimeWindow[HistogramBucket, StatisticSummary] with HistogramBucketSupport with StatisticSummarySupport {

  override def aggregate(bucketNumber: BucketNumber, buckets: Seq[HistogramBucket]): HistogramBucket = new HistogramBucket(bucketNumber, buckets)

  override def getSummary(bucket: HistogramBucket): StatisticSummary = bucket.summary
}
