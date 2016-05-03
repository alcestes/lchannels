// lchannels - session programming in Scala
// Copyright (c) 2016, Alceste Scalas and Imperial College London
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// * Redistributions of source code must retain the above copyright notice,
//   this list of conditions and the following disclaimer.
//
// * Redistributions in binary form must reproduce the above copyright notice,
//   this list of conditions and the following disclaimer in the documentation
//   and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.
/** @author Alceste Scalas <alceste.scalas@imperial.ac.uk> */
package lchannels

import scala.concurrent.{blocking, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.{Success, Failure}

import java.util.concurrent.{LinkedTransferQueue => Fifo}

/** Channel endpoints for local use, based on Java `LinkedTransferQueue`s.
 * 
 * Queue-based channels are (almost) a drop-in replacement for
 * [[LocalChannel]]s.  They are optimized for bypassing [[Out.promise]] and
 * [[In.future]] whenever possible, e.g. when a program mostly calls
 * [[Out.send]] and [[In.receive]]; as a consequence, if [[QueueOut.promise]]
 * or [[QueueIn.future]] are used, the performance will be impacted.
 * 
 * <strong>NOTE</strong>: due to limitations of Java `LinkedTransferQueue`s,
 * invoking [[QueueIn.receive]] on a `QueueIn[Null]` instance with a
 * <em>finite</em> wait time will cause a spurious timeout error.
 * If you really need channel endpoints that carry `Null` values, you should
 * use [[LocalChannel]]s.
 */
object QueueChannel {
  /** Create a pair of queue-based I/O channel endpoints.
   *  
   *  @param ec Execution context for internal `Promise`/`Future` handling
   */
  def factory[T]()(implicit ec: ExecutionContext): (QueueIn[T], QueueOut[T]) = {
    val fifo1 = new Fifo[Any]()
    val fifo2 = new Fifo[Any]()
    (new QueueIn[T](fifo1),
     new QueueOut[T](fifo1, fifo2))
  }
  
  /** Spawn two functions as threads communicating via a pair of queue-based
   *  channel endpoints.
   *  
   *  This method invokes [[factory]] to create a pair of channel endpoints
   *  `(in,out)`, and then spawns `p1(in)` and `p2(out)`.
   *  
   *  @return A pair of `Future`s `(f1, f2)`, completed respectively when
   *  `p1(in)` and `p2(out)` terminate.
   *  
   *  @param p1 Function using the input channel endpoint
   *  @param p2 Function using the output channel endpoint
   *  @param ec Execution context where the `p1` and `p2` will run
   */
  def parallel[T, R1, R2](p1: QueueIn[T] => R1,
                          p2: QueueOut[T] => R2)
                         (implicit ec: ExecutionContext): (Future[R1], Future[R2]) = {
    val (in, out) = factory[T]()
    ( Future { blocking { p1(in) } }, Future { blocking { p2(out) } } )
  }
}

/** Queue-based input endpoint, usually created via [[QueueChannel.factory]]. */
class QueueIn[+T](fifo: Fifo[Any])
                 (implicit ec: ExecutionContext) extends medium.In[Local, T] {
  // Usage states
  @inline
  private final val UNUSED = 0 // _future and recvValue are null
  @inline
  private final val FUTURED = 1 // Future created; receive() must use it
  @inline
  private final val RECEIVING = 2 // receive() invoked, switching to RECEIVED
  @inline
  private final val RECEIVED = 3 // value received; recvValue available
  
  private val usage = new java.util.concurrent.atomic.AtomicInteger(UNUSED)
  private var recvValue: Any = null // Will actually be a value of type T
  private var _future: Future[_] = null // Will actually be Future[T]
  override def future = synchronized {
    if (!usage.compareAndSet(UNUSED, FUTURED)) {
      usage.get match {
        case FUTURED => () // _future already created, nothing to do
        case RECEIVING => {
          // receive() invoked
          while (usage.get != RECEIVED) {} // Busy wait for recvValue
          _future = Future.successful(recvValue) // recvValue has type T
        }
        case RECEIVED => {
          // recvValue is available
          _future = Future.successful(recvValue) // recvValue has type T
        }
      }
    } else {
      _future = Future {
        recvValue = fifo.take()
        if (!usage.compareAndSet(FUTURED, RECEIVED)) {
          throw new RuntimeException("BUG in QueueIn.future!")
        }
        recvValue
      } // Note: this future has type Future[T]
    }
    // This cast is safe: the returned future only retrieves a T-typed value
    _future.asInstanceOf[Future[T]]
  }
  
  override def receive(implicit d: Duration): T = {
    if (!usage.compareAndSet(UNUSED, RECEIVING)) {
      usage.get match {
        case FUTURED => {
          // Future already created, we cannot use the channel directly
          super.receive
        }
        case RECEIVING => {
          // receive() has been invoked by some other thread
          while (usage.get != RECEIVED) {} // Busy wait for recvValue
          recvValue.asInstanceOf[T] // recvValue has type T
        }
        case RECEIVED => {
          // recvValue is available
          recvValue.asInstanceOf[T] // recvValue has type T
        }
      }
    } else {
      if (d.isFinite) {
        recvValue = fifo.poll(d.length, d.unit)
        if (recvValue == null) {
          // FIXME: Cannot distinguish a timeout when T is Null :-(
          throw new java.util.concurrent.TimeoutException("Channel timed out")
        }
      } else {
        recvValue = fifo.take()
      }
      if (!usage.compareAndSet(RECEIVING, RECEIVED)) {
        throw new RuntimeException("BUG in QueueIn.receive!")
      }
      recvValue.asInstanceOf[T] // recvValue has type T
    }
  }
}

/** Queue-based output endpoint, usually created via [[QueueChannel.factory]]. */
class QueueOut[-T](fifoW: Fifo[Any], fifoR: Fifo[Any])
                  (implicit ec: ExecutionContext) extends medium.Out[Local, T] {
  // Usage states
  @inline
  private final val UNUSED = 0 // _promise and sentValue are null
  @inline
  private final val PROMISED = 1 // Promise created; send() must use it
  @inline
  private final val SENDING = 2 // send()/Promise invoked, switching to SENT
  @inline
  private final val SENT = 3 // value actually sent; sentValue available
  
  private val usage = new java.util.concurrent.atomic.AtomicInteger(UNUSED)
  private var sentValue: Any = null // Will actually be a value of type T
  private var _promise: Promise[_] = null // Will actually be Promise[T]
  override def promise[U <: T] = synchronized {
    if (!usage.compareAndSet(UNUSED, PROMISED)) {
      usage.get match {
        case PROMISED => () // _promise already created, nothing to do
        case SENDING => {
          // send() invoked or Promise completed
          while (usage.get != SENT) {} // Busy wait for sentValue
          _promise = Promise.successful(sentValue) // sentValue has type T
        }
        case SENT => {
          // sentValue is available
          _promise = Promise.successful(sentValue) // sentValue has type T
        }
      }
    } else {
      _promise = Promise[Any] // Will be actually used as a Promise[T]
      
      // The following cast is safe: the returned promise can only
      // be completed with U-typed values, which are also T-typed
      _promise.future.asInstanceOf[Future[T]] onComplete {
        case Success(v) => {
          if (!usage.compareAndSet(PROMISED, SENDING)) {
            throw new RuntimeException("BUG in QueueOut.promise!")
          }
          fifoW.put(v)
          sentValue = v
          if (!usage.compareAndSet(SENDING, SENT)) {
            throw new RuntimeException("BUG in QueueOut.promise!")
          }
        }
        case Failure(e) => throw e // FIXME: can we do better?
      }
    }
    // The following cast is safe: the returned promise can only
    // be completed with U-typed values, which are also T-typed
    _promise.asInstanceOf[Promise[U]]
  }
  
  override def send(msg: T): Unit = {
    if (!usage.compareAndSet(UNUSED, SENDING)) {
      usage.get match {
        case PROMISED => {
          // Promise already created, we cannot use the channel directly
          promise success msg
        }
        case SENDING => {
          // send() has been invoked by some other thread
          throw new IllegalStateException("Output channel already used")
        }
        case SENT => {
          throw new IllegalStateException("Output channel already used")
        }
      }
    } else {
      fifoW.put(msg)
      sentValue = msg
      if (!usage.compareAndSet(SENDING, SENT)) {
        throw new RuntimeException("BUG in QueueOut.send!")
      }
    }
  }
  
  override def create[U](): (QueueIn[U], QueueOut[U]) = {
    QueueChannel.factory[U]()
  }
  
  override def createContIn[U](): (QueueIn[U], QueueOut[U]) = {
    // Keep the same queues for reading and writing
    (new QueueIn[U](fifoR),
     new QueueOut[U](fifoR, fifoW))
  }
  
  override def createContOut[U](): (QueueIn[U], QueueOut[U]) = {
    // Swap the read/write queues, so that we will not read the data we sent
    (new QueueIn[U](fifoW),
     new QueueOut[U](fifoW, fifoR))
  }
}
