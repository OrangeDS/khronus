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

package com.despegar.metrik.service

import akka.actor._
import com.despegar.metrik.service.HandShakeProtocol.Register
import com.despegar.metrik.util.CORSSupport
import spray.http.StatusCodes._
import spray.httpx.marshalling.ToResponseMarshaller
import spray.routing.{ RequestContext, _ }
import spray.util.LoggingContext

class MetrikHandler extends HttpServiceActor with ActorLogging with MetrikHandlerException {
  var composedRoute: Route = reject

  def createEndpointRoute(path: String, actor: ActorRef): Route =
    pathPrefix(separateOnSlashes(path)) {
      requestContext ⇒ actor ! requestContext
    }

  val registerReceive: Receive = {
    case Register(path, actor) ⇒
      log.info(s"Registering endpoint: $path")
      composedRoute = composedRoute ~ createEndpointRoute(path, actor)
      context become receive
  }

  def receive = registerReceive orElse runRoute(composedRoute)

}

object MetrikHandler {
  val Name = "handler-actor"
  def props: Props = Props[MetrikHandler]
}

object HandShakeProtocol {
  case class Register(path: String, actor: ActorRef)
  case class MetrikStarted(handler: ActorRef)
}

trait MetrikHandlerException {
  implicit def myExceptionHandler(implicit settings: RoutingSettings, log: LoggingContext): ExceptionHandler =
    ExceptionHandler.apply {
      case e: UnsupportedOperationException ⇒ ctx ⇒ {
        log.error(s"Handling UnsupportedOperationException ${e.getMessage}", e)
        responseWithCORSHeaders(ctx, (BadRequest, s"${e.getMessage}"))
      }
      case e: Exception ⇒ ctx ⇒ {
        log.error(s"Handling Exception ${e.getMessage}", e)
        responseWithCORSHeaders(ctx, InternalServerError)
      }
    }

  private def responseWithCORSHeaders[T](ctx: RequestContext, response: T)(implicit marshaller: ToResponseMarshaller[T]) = {
    ctx.withHttpResponseHeadersMapped(_ ⇒ CORSSupport.headers).complete(response)(marshaller)
  }
}
