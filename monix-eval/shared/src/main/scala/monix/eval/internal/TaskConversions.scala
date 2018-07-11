/*
 * Copyright (c) 2014-2018 by The Monix Project Developers.
 * See the project homepage at: https://monix.io
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

package monix.eval.internal

import cats.effect._
import monix.eval.Task.Context
import monix.eval.{Callback, Task}
import monix.execution.cancelables.{SingleAssignCancelable, StackedCancelable}
import monix.execution.schedulers.TrampolinedRunnable
import monix.execution.{Cancelable, Scheduler}
import scala.util.control.NonFatal

private[eval] object TaskConversions {
  /**
    * Implementation for `Task#toIO`.
    */
  def toIO[A](source: Task[A])(implicit eff: ConcurrentEffect[Task]): IO[A] =
    source match {
      case Task.Now(value) => IO.pure(value)
      case Task.Error(e) => IO.raiseError(e)
      case Task.Eval(thunk) => IO(thunk())
      case _ =>
        IO.cancelable { cb =>
          eff.runCancelable(source)(r => { cb(r); IO.unit }).unsafeRunSync()
        }
    }

  /**
    * Implementation for `Task#toConcurrent`.
    */
  def toConcurrent[F[_], A](source: Task[A])(implicit F: Concurrent[F], eff: ConcurrentEffect[Task]): F[A] =
    source match {
      case Task.Now(value) => F.pure(value)
      case Task.Error(e) => F.raiseError(e)
      case Task.Eval(thunk) => F.delay(thunk())
      case _ =>
        F.cancelable { cb =>
          eff.runCancelable(source)(r => { cb(r); IO.unit }).unsafeRunSync()
        }
    }

  /**
    * Implementation for `Task#toAsync`.
    */
  def toAsync[F[_], A](source: Task[A])(implicit F: Async[F], eff: Effect[Task]): F[A] =
    source match {
      case Task.Now(value) => F.pure(value)
      case Task.Error(e) => F.raiseError(e)
      case Task.Eval(thunk) => F.delay(thunk())
      case task =>
        F.async { cb =>
          eff.runAsync(task)(r => { cb(r); IO.unit }).unsafeRunSync()
        }
    }

  /**
    * Implementation for `Task.from`.
    */
  def fromAsync[F[_], A](fa: F[A])(implicit F: Effect[F]): Task[A] =
    fa.asInstanceOf[AnyRef] match {
      case ref: Task[A] @unchecked => ref
      case io: IO[A] @unchecked => io.to[Task]
      case _ => F.toIO(fa).to[Task]
    }

  /**
    * Implementation for `Task.fromConcurrent`.
    */
  def fromConcurrent[F[_], A](fa: F[A])(implicit F: ConcurrentEffect[F]): Task[A] =
    fa.asInstanceOf[AnyRef] match {
      case ref: Task[A] @unchecked => ref
      case io: IO[A] @unchecked => io.to[Task]
      case _ => fromConcurrent0(fa)
    }

  private def fromConcurrent0[F[_], A](fa: F[A])(implicit F: ConcurrentEffect[F]): Task[A] = {
    val start = (ctx: Context, cb: Callback[A]) => {
      try {
        implicit val sc = ctx.scheduler
        val conn = ctx.connection
        val cancelable = SingleAssignCancelable()
        conn push cancelable

        val io = F.runCancelable(fa)(new CreateCallback[A](conn, cb))
        cancelable := Cancelable.fromIOUnsafe(io.unsafeRunSync())
      } catch {
        case e if NonFatal(e) =>
          ctx.scheduler.reportFailure(e)
      }
    } : Unit

    Task.Async(start, trampolineBefore = false, trampolineAfter = false)
  }

  private final class CreateCallback[A](
    conn: StackedCancelable, cb: Callback[A])
    (implicit s: Scheduler)
    extends (Either[Throwable, A] => IO[Unit]) with TrampolinedRunnable {

    private[this] var canCall = true
    private[this] var value: Either[Throwable, A] = _

    def run(): Unit = {
      if (canCall) {
        canCall = false
        if (conn ne null) conn.pop()
        cb(value)
        value = null
      }
    }

    override def apply(value: Either[Throwable, A]) =
      IO {
        this.value = value
        s.execute(this)
      }
  }
}
