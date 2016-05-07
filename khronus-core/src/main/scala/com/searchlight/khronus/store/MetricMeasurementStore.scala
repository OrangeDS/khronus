package com.searchlight.khronus.store

import com.searchlight.khronus.api.{ Measurement, MetricMeasurement }
import com.searchlight.khronus.model._
import com.searchlight.khronus.service.MonitoringSupport
import com.searchlight.khronus.util.log.Logging
import com.searchlight.khronus.util.{ ConcurrencySupport, Measurable }
import org.HdrHistogram.Histogram

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

trait MetricMeasurementStoreSupport {
  def metricStore: MetricMeasurementStore = CassandraMetricMeasurementStore
}

trait MetricMeasurementStore {
  def storeMetricMeasurements(metricMeasurements: List[MetricMeasurement])
}

object CassandraMetricMeasurementStore extends MetricMeasurementStore with BucketSupport with MetaSupport with Logging with ConcurrencySupport with MonitoringSupport with TimeWindowsSupport with Measurable {

  implicit val executionContext: ExecutionContext = executionContext("metric-receiver-worker")

  private val rawDuration = 1 millis
  private val storeGroupDuration = 5 seconds

  def storeMetricMeasurements(metricMeasurements: List[MetricMeasurement]) = {
    try {
      store(metricMeasurements)
    } catch {
      case reason: Throwable ⇒ log.error("Failed storing samples", reason)
    }
  }

  private def store(metrics: List[MetricMeasurement]) = measureTime("measurementStore.store", "store metricMeasurements") {
    log.info(s"Received samples of ${metrics.length} metrics")

    val buckets = collectBuckets(metrics)

    val futures = groupedByType(buckets).map {
      case (mtype, groupedBuckets) ⇒
        getStore(mtype).store(groupedBuckets, rawDuration)
    }

    measureFutureTime("measurementStore.store.futures", "store metricMeasurements futures")(Future.sequence(futures))
  }

  def groupedByType(buckets: mutable.Buffer[(Metric, () ⇒ Bucket)]): Map[MetricType, mutable.Buffer[(Metric, () ⇒ Bucket)]] = {
    buckets.groupBy(_._1.mtype)
  }

  def collectBuckets(metrics: List[MetricMeasurement]): mutable.Buffer[(Metric, () ⇒ Bucket)] = {
    val buckets = mutable.Buffer[(Metric, () ⇒ Bucket)]()

    val now = System.currentTimeMillis()

    metrics foreach (metricMeasurement ⇒ {
      val metric = metricMeasurement.asMetric
      val groupedMeasurements = metricMeasurement.measurements.groupBy(measurement ⇒ Timestamp(measurement.ts.getOrElse(now)).alignedTo(storeGroupDuration))
      buckets ++= buildBuckets(metric, groupedMeasurements)
    })

    buckets
  }

  private def buildBuckets(metric: Metric, groupedMeasurements: Map[Timestamp, List[Measurement]]): List[(Metric, () ⇒ Bucket)] = {
    track(metric)
    groupedMeasurements.toList.map {
      case (timestamp, measures) ⇒
        (metric, () ⇒ metric.mtype.bucketWithMeasures(metric, timestamp.toBucketNumberOf(rawDuration), measures))
    }
  }

  private def track(metric: Metric) = measureTime("measurementStore.track", "track metric") {
    metaStore.snapshot.get(metric) collect { case (timestamp, active) ⇒ metaStore.notifyMetricMeasurement(metric, active) } getOrElse {
      log.debug(s"Got a new metric: $metric. Will store metadata for it")
      storeMetadata(metric)
    }
  }

  private def storeMetadata(metric: Metric) = measureFutureTime("measurementStore.storeMetadata", "store metadata") {
    metaStore.insert(metric)
  }

  private def alreadyProcessed(metric: Metric, rawBucketNumber: BucketNumber) = {
    //get the bucket number in the smallest window duration
    val measureBucket = rawBucketNumber ~ smallestWindow.duration
    //get the current tick. The delay is to softly avoid out of sync clocks between nodes (another node start to process the tick)
    if (Tick.alreadyProcessed(rawBucketNumber)) {
      log.warn(s"Measurements for $metric marked to be reprocessed because their bucket number ($measureBucket) is less or equals than the current bucket tick (${Tick().bucketNumber})")
    }
    false
  }

}