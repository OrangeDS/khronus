package com.despegar.metrik.util

import com.despegar.metrik.store.{CassandraStatisticSummaryStore, Cassandra}
import com.netflix.astyanax.connectionpool.OperationResult
import com.netflix.astyanax.model.ColumnFamily
import org.scalatest.{BeforeAndAfter, FunSuite, BeforeAndAfterAll}
import scala.collection.JavaConverters._
import scala.util.Try
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._

trait BaseIntegrationTest extends FunSuite with BeforeAndAfterAll with BeforeAndAfter {

  override def beforeAll = {
    Cassandra initialize

    truncateColumnFamilies
  }

  after {
    truncateColumnFamilies
  }

  def await[T](f: => Future[T]):T = Await.result(f, 10 seconds)
  
  private def truncateColumnFamilies = Try {
    foreachColumnFamily {
      Cassandra.keyspace.truncateColumnFamily(_)
    }
  }

  def foreachColumnFamily(f: ColumnFamily[String, java.lang.Long] => OperationResult[_])
}
