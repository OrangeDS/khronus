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

package com.despegar.metrik.store

import java.nio.ByteBuffer

import com.despegar.metrik.model._
import com.despegar.metrik.util.Settings
import org.HdrHistogram.{ Histogram, SkinnyHistogram }

import scala.concurrent.duration._
import com.despegar.metrik.util.log.Logging

trait HistogramBucketSupport extends BucketStoreSupport[HistogramBucket] {
  override def bucketStore: BucketStore[HistogramBucket] = CassandraHistogramBucketStore
}

object CassandraHistogramBucketStore extends BucketStore[HistogramBucket] with Logging {

  val windowDurations: Seq[Duration] = Settings().Histogram.WindowDurations
  override val limit: Int = Settings().Histogram.BucketLimit
  override val fetchSize: Int = Settings().Histogram.BucketFetchSize

  override def toBucket(windowDuration: Duration, timestamp: Long, histogram: Array[Byte]) = {
    new HistogramBucket(Timestamp(timestamp).toBucketNumber(windowDuration), deserializeHistogram(histogram))
  }

  override def tableName(duration: Duration) = s"histogramBucket${duration.length}${duration.unit}"

  def serializeBucket(metric: Metric, windowDuration: Duration, bucket: HistogramBucket): ByteBuffer = {
    val buffer = ByteBuffer.allocate(bucket.histogram.getEstimatedFootprintInBytes)
    val bytesEncoded = bucket.histogram.encodeIntoCompressedByteBuffer(buffer)
    log.debug(s"$metric- Histogram of $windowDuration with ${bucket.histogram.getTotalCount()} measures encoded and compressed into $bytesEncoded bytes")
    buffer.limit(bytesEncoded)
    buffer.rewind()
    buffer
  }

  private def deserializeHistogram(bytes: Array[Byte]): Histogram = SkinnyHistogram.decodeFromCompressedByteBuffer(ByteBuffer.wrap(bytes), 0)

  override def ttl(windowDuration: Duration): Int = Settings().Histogram.BucketRetentionPolicy

}