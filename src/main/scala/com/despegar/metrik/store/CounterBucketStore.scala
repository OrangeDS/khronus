package com.despegar.metrik.store

import java.nio.ByteBuffer

import com.despegar.metrik.model.{ StatisticSummary, Bucket, Metric, CounterBucket }
import com.despegar.metrik.util.KryoSerializer
import com.netflix.astyanax.model.Column
import scala.concurrent.Future
import scala.concurrent.duration._

trait CounterBucketStoreSupport extends BucketStoreSupport[CounterBucket] {
  override def bucketStore: BucketStore[CounterBucket] = CassandraBucketStore
}

object CassandraBucketStore extends BucketStore[CounterBucket] {
  val windowDurations: Seq[Duration] = Seq(1 millis, 30 seconds) //FIXME put configured windows

  val serializer: KryoSerializer[CounterBucket] = new KryoSerializer("counterBucket", List(CounterBucket.getClass))

  override def getColumnFamilyName(duration: Duration): String = s"counterBucket${duration.length}${duration.unit}"

  def deserialize(buffer: ByteBuffer): CounterBucket = {
    serializer.deserialize(buffer.array())
  }

  override def toBucket(windowDuration: Duration)(column: Column[java.lang.Long]): CounterBucket = {
    val timestamp = column.getName()
    val counter = deserialize(column.getByteBufferValue)
    new CounterBucket(timestamp / windowDuration.toMillis, windowDuration, counter.counts)
  }

  override def serializeBucket(bucket: CounterBucket): ByteBuffer = {
    ByteBuffer.wrap(serializer.serialize(bucket))
  }
}
