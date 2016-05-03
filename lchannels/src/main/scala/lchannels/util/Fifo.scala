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
package lchannels.util

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import lchannels._

///////////////////////////////////////////////////////////////////////////////
// Session type (from output endpoint):
// rec X . !Datum(T). X
///////////////////////////////////////////////////////////////////////////////
private case class Datum[T](value: T)(val cont: In[Datum[T]])

/** Interface for FIFO queues. */
trait Fifo[T] {
  /** Return and remove the value at the head of the queue.
   * 
   * This method blocks if the queue is empty,
   * until a value can be retrieved, or the given timeout expires.
   * 
   * @param timeout Maximum wait time when the queue is empty.
   * 
   * @throws java.util.concurrent.TimeoutException if `timeout` expires
   * @throws java.util.concurrent.InterruptedException if thread is interrupted
   */
  def read(implicit timeout: Duration): T
  
  /** Append a value to the queue.
   * 
   * @param value Value to be appended.
   */
  def write(value: T): Unit
}

/** Simple implementation of FIFO queue.
 *  
 *  @param factory Used internally to create [[In]]/[[Out]] instances.
 */
protected class FifoImpl[T](factory: () => (In[Datum[T]], Out[Datum[T]]))
    extends Fifo[T] {
  private[this] var (in, out) = factory()
  
  private[this] val inLock = new Object()  // Guards "in" above
  private[this] val outLock = new Object() // Guards "out" above
  
  def read(implicit timeout: Duration): T = inLock.synchronized {
    val res = in.receive
    in = res.cont
    res.value
  }
  
  def write(value: T): Unit = outLock.synchronized {
    out = out !! Datum[T](value)_
  }
}

/** Simple FIFO queue. */
object Fifo {
  /** Return an empty FIFO, internally based on [[LocalChannel]]s. */
  def apply[T](): Fifo[T] = {
    new FifoImpl[T](LocalChannel.factory[Datum[T]])
  }
  
  /** Return an empty FIFO, internally based on [[QueueChannel]]s
   * 
   * @param ec Execution context for [[QueueChannel]] creation.
   */
  def apply[T](ec: ExecutionContext): Fifo[T] = {
    new FifoImpl[T](() => QueueChannel.factory[Datum[T]]()(ec))
  }
}
