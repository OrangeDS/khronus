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

package com.despegar.metrik.cluster

import akka.actor._
import akka.routing.{ Broadcast, FromConfig }
import com.despegar.metrik.store.MetaSupport
import com.despegar.metrik.util.Settings
import us.theatr.akka.quartz.{ AddCronScheduleFailure, _ }
import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{ Failure, Success }
import com.despegar.metrik.model.Metric

class Master extends Actor with ActorLogging with RouterProvider with MetricFinder {

  import com.despegar.metrik.cluster.Master._
  import context._

  var idleWorkers = Set[ActorRef]()
  var pendingMetrics = Seq[Metric]()

  val settings = Settings(system).Master

  self ! Initialize

  def receive: Receive = uninitialized

  def uninitialized: Receive = {
    case Initialize ⇒
      log.info(s"Initializating master ${self.path}")
      val router = createRouter()

      scheduleHeartbeat(router)
      scheduleTick()

      become(initialized(router))

    case AddCronScheduleFailure(reason) ⇒
      log.error(reason, "Could not schedule tick")
      throw reason

    case everythingElse ⇒ //ignore
  }

  def initialized(router: ActorRef): Receive = {

    case Tick ⇒ lookupMetrics onComplete {
      case Success(metrics)          ⇒ self ! PendingMetrics(metrics)
      case Failure(NonFatal(reason)) ⇒ log.error(reason, "Error trying to get metrics.")
    }

    case PendingMetrics(metrics) ⇒ {
      log.info(s"Pending metrics received: ${pendingMetrics.size} pending metrics and ${idleWorkers.size} idle workers")
      log.debug(s"Pending metrics: $pendingMetrics workers idle: $idleWorkers")
      log.debug(s"Idle workers: $idleWorkers")
      pendingMetrics ++= metrics filterNot (metric ⇒ pendingMetrics contains metric)

      while (pendingMetrics.nonEmpty && idleWorkers.nonEmpty) {
        val worker = idleWorkers.head
        val pending = pendingMetrics.head

        log.info(s"Dispatching $pending to ${worker.path}")
        worker ! Work(pending)

        idleWorkers = idleWorkers.tail
        pendingMetrics = pendingMetrics.tail
      }
    }

    case Register(worker) ⇒
      log.info("Registering worker [{}]", worker.path)
      watch(worker)
      idleWorkers += worker

    case WorkDone(worker) ⇒
      if (pendingMetrics.nonEmpty) {
        log.debug(s"Dispatching ${pendingMetrics.head} to ${worker.path}")
        worker ! Work(pendingMetrics.head)
        pendingMetrics = pendingMetrics.tail
      } else {
        log.debug(s"Pending metrics is empty. Adding worker ${worker.path} to worker idle list")
        idleWorkers += worker
      }

    case Terminated(worker) ⇒
      log.info("Removing worker [{}] from worker list", worker.path)
      idleWorkers -= worker
  }

  def scheduleHeartbeat(router: ActorRef) {
    log.info("Scheduling Heartbeat in order to discover workers periodically")
    system.scheduler.schedule(settings.DiscoveryStartDelay, settings.DiscoveryInterval, router, Broadcast(Heartbeat))
  }

  def scheduleTick() {
    log.info(s"Scheduling tick at ${settings.TickCronExpression}")
    val tickScheduler = actorOf(Props[QuartzActor])
    tickScheduler ! AddCronSchedule(self, settings.TickCronExpression, Tick, true)
  }
}

object Master {
  case object Tick
  case class PendingMetrics(metrics: Seq[Metric])
  case class Initialize(cronExpression: String, router: ActorRef)
  case class MasterConfig(cronExpression: String)

  def props: Props = Props(classOf[Master])
}

trait RouterProvider {
  this: Actor ⇒

  def createRouter(): ActorRef = {
    SupervisorStrategy
    context.actorOf(Props[Worker].withRouter(FromConfig().withSupervisorStrategy(RouterSupervisorStrategy.restartOnError)), "workerRouter")
  }
}

trait MetricFinder extends MetaSupport {
  def lookupMetrics: Future[Seq[Metric]] = metaStore.retrieveMetrics
}
