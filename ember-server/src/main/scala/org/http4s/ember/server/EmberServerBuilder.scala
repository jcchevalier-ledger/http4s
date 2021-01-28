/*
 * Copyright 2019 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.ember.server

import cats._
import cats.syntax.all._
import cats.effect._
import cats.effect.concurrent._
import fs2.io.tcp.SocketGroup
import fs2.io.tcp.SocketOptionMapping
import fs2.io.tls._
import org.http4s._
import org.http4s.server.Server

import scala.concurrent.duration._
import java.net.InetSocketAddress
import _root_.org.typelevel.log4cats.Logger
import _root_.org.typelevel.log4cats.slf4j.Slf4jLogger
import org.http4s.ember.server.internal.{ServerHelpers, Shutdown}

final class EmberServerBuilder[F[_]: Concurrent: Timer: ContextShift] private (
    val host: String,
    val port: Int,
    private val httpApp: HttpApp[F],
    private val blockerOpt: Option[Blocker],
    private val tlsInfoOpt: Option[(TLSContext, TLSParameters)],
    private val sgOpt: Option[SocketGroup],
    private val onError: Throwable => Response[F],
    private val onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit],
    val maxConcurrency: Int,
    val receiveBufferSize: Int,
    val maxHeaderSize: Int,
    val requestHeaderReceiveTimeout: Duration,
    val idleTimeout: Duration,
    val shutdownTimeout: Duration,
    val additionalSocketOptions: List[SocketOptionMapping[_]],
    private val logger: Logger[F]
) { self =>

  private def copy(
      host: String = self.host,
      port: Int = self.port,
      httpApp: HttpApp[F] = self.httpApp,
      blockerOpt: Option[Blocker] = self.blockerOpt,
      tlsInfoOpt: Option[(TLSContext, TLSParameters)] = self.tlsInfoOpt,
      sgOpt: Option[SocketGroup] = self.sgOpt,
      onError: Throwable => Response[F] = self.onError,
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit] = self.onWriteFailure,
      maxConcurrency: Int = self.maxConcurrency,
      receiveBufferSize: Int = self.receiveBufferSize,
      maxHeaderSize: Int = self.maxHeaderSize,
      requestHeaderReceiveTimeout: Duration = self.requestHeaderReceiveTimeout,
      idleTimeout: Duration = self.idleTimeout,
      shutdownTimeout: Duration = self.shutdownTimeout,
      additionalSocketOptions: List[SocketOptionMapping[_]] = self.additionalSocketOptions,
      logger: Logger[F] = self.logger
  ): EmberServerBuilder[F] =
    new EmberServerBuilder[F](
      host = host,
      port = port,
      httpApp = httpApp,
      blockerOpt = blockerOpt,
      tlsInfoOpt = tlsInfoOpt,
      sgOpt = sgOpt,
      onError = onError,
      onWriteFailure = onWriteFailure,
      maxConcurrency = maxConcurrency,
      receiveBufferSize = receiveBufferSize,
      maxHeaderSize = maxHeaderSize,
      requestHeaderReceiveTimeout = requestHeaderReceiveTimeout,
      idleTimeout = idleTimeout,
      shutdownTimeout = shutdownTimeout,
      additionalSocketOptions = additionalSocketOptions,
      logger = logger
    )

  def withHost(host: String) = copy(host = host)
  def withPort(port: Int) = copy(port = port)
  def withHttpApp(httpApp: HttpApp[F]) = copy(httpApp = httpApp)

  def withSocketGroup(sg: SocketGroup) =
    copy(sgOpt = sg.pure[Option])

  def withTLS(tlsContext: TLSContext, tlsParameters: TLSParameters = TLSParameters.Default) =
    copy(tlsInfoOpt = (tlsContext, tlsParameters).pure[Option])
  def withoutTLS =
    copy(tlsInfoOpt = None)

  def withBlocker(blocker: Blocker) =
    copy(blockerOpt = blocker.pure[Option])

  def withIdleTimeout(idleTimeout: Duration) =
    copy(idleTimeout = idleTimeout)

  def withShutdownTimeout(shutdownTimeout: Duration) =
    copy(shutdownTimeout = shutdownTimeout)

  def withOnError(onError: Throwable => Response[F]) = copy(onError = onError)
  def withOnWriteFailure(onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit]) =
    copy(onWriteFailure = onWriteFailure)
  def withMaxConcurrency(maxConcurrency: Int) = copy(maxConcurrency = maxConcurrency)
  def withReceiveBufferSize(receiveBufferSize: Int) = copy(receiveBufferSize = receiveBufferSize)
  def withMaxHeaderSize(maxHeaderSize: Int) = copy(maxHeaderSize = maxHeaderSize)
  def withRequestHeaderReceiveTimeout(requestHeaderReceiveTimeout: Duration) =
    copy(requestHeaderReceiveTimeout = requestHeaderReceiveTimeout)
  def withLogger(l: Logger[F]) = copy(logger = l)

  def build: Resource[F, Server] =
    for {
      bindAddress <- Resource.liftF(Sync[F].delay(new InetSocketAddress(host, port)))
      blocker <- blockerOpt.fold(Blocker[F])(_.pure[Resource[F, *]])
      sg <- sgOpt.fold(SocketGroup[F](blocker))(_.pure[Resource[F, *]])
      ready <- Resource.liftF(Deferred[F, Either[Throwable, Unit]])
      shutdown <- Resource.liftF(Shutdown[F](shutdownTimeout))
      _ <- Concurrent[F].background(
        ServerHelpers
          .server(
            bindAddress,
            httpApp,
            sg,
            tlsInfoOpt,
            ready,
            shutdown,
            onError,
            onWriteFailure,
            maxConcurrency,
            receiveBufferSize,
            maxHeaderSize,
            requestHeaderReceiveTimeout,
            idleTimeout,
            additionalSocketOptions,
            logger
          )
          .compile
          .drain
      )
      _ <- Resource.make(Applicative[F].unit)(_ => shutdown.await)
      _ <- Resource.liftF(ready.get.rethrow)
    } yield new Server {
      def address: InetSocketAddress = bindAddress
      def isSecure: Boolean = tlsInfoOpt.isDefined
    }
}

object EmberServerBuilder {
  def default[F[_]: Concurrent: Timer: ContextShift]: EmberServerBuilder[F] =
    new EmberServerBuilder[F](
      host = Defaults.host,
      port = Defaults.port,
      httpApp = Defaults.httpApp[F],
      blockerOpt = None,
      tlsInfoOpt = None,
      sgOpt = None,
      onError = Defaults.onError[F],
      onWriteFailure = Defaults.onWriteFailure[F],
      maxConcurrency = Defaults.maxConcurrency,
      receiveBufferSize = Defaults.receiveBufferSize,
      maxHeaderSize = Defaults.maxHeaderSize,
      requestHeaderReceiveTimeout = Defaults.requestHeaderReceiveTimeout,
      idleTimeout = Defaults.idleTimeout,
      shutdownTimeout = Defaults.shutdownTimeout,
      additionalSocketOptions = Defaults.additionalSocketOptions,
      logger = Slf4jLogger.getLogger[F]
    )

  private object Defaults {
    val host: String = server.defaults.Host
    val port: Int = server.defaults.HttpPort

    def httpApp[F[_]: Applicative]: HttpApp[F] = HttpApp.notFound[F]
    def onError[F[_]]: Throwable => Response[F] = { (_: Throwable) =>
      Response[F](Status.InternalServerError)
    }
    def onWriteFailure[F[_]: Applicative]
        : (Option[Request[F]], Response[F], Throwable) => F[Unit] = {
      case _: (Option[Request[F]], Response[F], Throwable) => Applicative[F].unit
    }
    val maxConcurrency: Int = Int.MaxValue
    val receiveBufferSize: Int = 256 * 1024
    val maxHeaderSize: Int = server.defaults.MaxHeadersSize
    val requestHeaderReceiveTimeout: Duration = 5.seconds
    val idleTimeout: Duration = server.defaults.IdleTimeout
    val shutdownTimeout: Duration = server.defaults.ShutdownTimeout
    val additionalSocketOptions = List.empty[SocketOptionMapping[_]]
  }
}
