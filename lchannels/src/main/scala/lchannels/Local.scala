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

import scala.concurrent.{Await, blocking, ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration

/** The medium of local channel endpoints. */
case class Local()

/** Simple implementation of local channel endpoints,
 *  based on Scala `Promise`s/`Future`s. */
object LocalChannel {
  /** Create a pair of linear I/O channel endpoints, for local use. */
  def factory[T](): (LocalIn[T], LocalOut[T]) = {
    val promise = scala.concurrent.Promise[T]()
    val future = promise.future
    (new LocalIn[T](future), new LocalOut[T](promise))
  }
  
  /** Spawn two functions as threads communicating via a pair of local
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
  def parallel[T, R1, R2](p1: LocalIn[T] => R1,
                          p2: LocalOut[T] => R2)
                         (implicit ec: ExecutionContext): (Future[R1], Future[R2]) = {
    val (in, out) = factory[T]()
    ( Future { blocking { p1(in) } }, Future { blocking { p2(out) } } )
  }
}

/** Local input channel endpoint, usually created via [[LocalChannel.factory]]. */
class LocalIn[+T](val future: Future[T]) extends medium.In[Local, T] {
  override def receive(implicit atMost: Duration): T = {
    Await.result[T](future, atMost)
  }
}

/** Local output channel endpoint, usually created via [[LocalChannel.factory]]. */
class LocalOut[-T](p: Promise[T]) extends medium.Out[Local, T] {  
  override def promise[U <: T] = {
    // The following cast is safe: the returned promise can only
    // be completed with U-typed values, which are also T-typed
    p.asInstanceOf[Promise[U]]
  }
  
  override def create[U]() = LocalChannel.factory[U]()
  
  override def send(msg: T): Unit = {
    promise success msg
  }
}
