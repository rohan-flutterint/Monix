/*
 * Copyright (c) 2014-2015 by its authors. Some rights reserved.
 * See the project homepage at: http://www.monifu.org
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

package monifu.reactive.internals.builders

import monifu.reactive.Ack.Continue
import monifu.reactive.{Ack, Observable}
import monifu.reactive.internals._

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

private[reactive] object from {
  /**
   * Implementation for [[Observable.fromIterable]].
   */
  def iterable[T](iterable: Iterable[T]): Observable[T] =
    Observable.create { subscriber =>
      var streamError = true
      try {
        val i = iterable.iterator
        streamError = false
        iterator(i).onSubscribe(subscriber)
      }
      catch {
        case NonFatal(ex) if streamError =>
          subscriber.onError(ex)
      }
    }

  /**
   * Implementation for [[Observable.fromIterator]].
   */
  def iterator[T](iterator: Iterator[T]): Observable[T] = {
    Observable.create { subscriber =>
      import subscriber.{scheduler => s}
      val modulus = s.env.batchSize - 1

      def startFeedLoop(iterator: Iterator[T]): Unit = s.execute(new Runnable {
        /**
         * Loops synchronously, pushing elements into the downstream observer,
         * until either the iterator is finished or an asynchronous barrier
         * is being hit.
         */
        @tailrec
        def fastLoop(syncIndex: Int): Unit = {
          // the result of onNext calls, on which we must do back-pressure
          var ack: Future[Ack] = Continue
          // we do not want to catch errors from our interaction with our observer,
          // since SafeObserver should take care of than, hence we must only
          // catch and stream errors related to the interactions with the iterator
          var streamError = true
          // true in case our iterator is seen to be empty and we must signal onComplete
          var iteratorIsDone = false
          // non-null in case we caught an iterator related error and we must signal onError
          var iteratorTriggeredError: Throwable = null

          try {
            if (iterator.hasNext) {
              val next = iterator.next()
              streamError = false
              ack = subscriber.onNext(next)
            }
            else
              iteratorIsDone = true
          }
          catch {
            case NonFatal(ex) if streamError =>
              iteratorTriggeredError = ex
          }

          if (iteratorIsDone) {
            subscriber.onComplete()
          }
          else if (iteratorTriggeredError != null) {
            subscriber.onError(iteratorTriggeredError)
          }
          else {
            val nextIndex = if (!ack.isCompleted) 0 else
              (syncIndex + 1) & modulus

            if (nextIndex > 0) {
              if (ack == Continue)
                fastLoop(nextIndex)
              else ack.value.get match {
                case Continue.IsSuccess =>
                  fastLoop(nextIndex)
                case Failure(ex) =>
                  s.reportFailure(ex)
                case _ =>
                  () // do nothing
              }
            }
            else ack.onComplete {
              case Continue.IsSuccess =>
                run()
              case Failure(ex) =>
                s.reportFailure(ex)
              case _ =>
                () // do nothing
            }
          }
        }

        def run(): Unit = {
          fastLoop(0)
        }
      })

      var streamError = true
      try {
        val isEmpty = iterator.isEmpty
        streamError = false

        if (isEmpty)
          subscriber.onComplete()
        else
          startFeedLoop(iterator)
      }
      catch {
        case NonFatal(ex) if streamError =>
          subscriber.onError(ex)
      }
    }
  }

  /**
   * Implementation for [[Observable.fromFuture]].
   */
  def future[T](f: Future[T]): Observable[T] =
    Observable.create { subscriber =>
      import subscriber.{scheduler => s}

      f.value match {
        case Some(Success(value)) =>
          subscriber.onNext(value).onContinueSignalComplete(subscriber)
        case Some(Failure(ex)) =>
          subscriber.onError(ex)
        case None => f.onComplete {
          case Success(value) =>
            subscriber.onNext(value).onContinueSignalComplete(subscriber)
          case Failure(ex) =>
            subscriber.onError(ex)
        }
      }
    }
}