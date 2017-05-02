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

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.{Try, Success, Failure}

/** Base abstract class for all channel endpoints. */
abstract class Channel[D <: Direction] {
  @transient // Serializable sub-classes might not be able to use this field 
  private val used = new java.util.concurrent.atomic.AtomicBoolean(false)
  
  protected final def markAsUsed() : Unit = {
    if (!used.compareAndSet(false, true)) {
      throw new lchannels.AlreadyUsed()
    }
  }
}

/** Signals a double usage of an input or output channel endpoint. */
class AlreadyUsed(message: String = "I/O endpoint already used")
  extends IllegalStateException(message)

/** Base abstract class for linear input channel endpoints. */
abstract class In[+T] extends Channel[Receive] {
   private var _future: Future[_] = null // Will actually be Future[T]
  /** Return a future that will be completed when the channel endpoint
   *  receives a value, or incurs in an input error.
   */
  def future(implicit ec: ExecutionContext) = synchronized {
    if (_future == null) {
      // FIXME: maybe the duration below should be a parameter (implicit?)
      _future = Future { receive(Duration.Inf) }
    }
    // This cast is safe: the returned future only retrieves a T-typed value
    _future.asInstanceOf[Future[T]]
  }
  
  /** Receive and return a message, blocking until its arrival.
   * 
   * The default implementation of this method corresponds to
   * [[receive(implicit atMost:*]] with an infinite timeout value.
   * 
   * @throws AlreadyUsed if the channel endpoint was already used for input
   * @throws java.lang.IllegalArgumentException if `atMost` is Duration.Undefined
   * @throws java.lang.InterruptedException if the current thread is interrupted while waiting
   * @throws java.util.concurrent.TimeoutException if after waiting for `atMost`, no message arrives
   * @throws scala.util.control.NonFatal in case of other errors (e.g. deserialization or network issues)
   */
  def receive(): T = {
    receive(Duration.Inf)
  }
  
  /** Receive and return a message, blocking until its arrival.
   *  
   * @param atMost Maximum wait time
   * 
   * @throws AlreadyUsed if the channel endpoint was already used for input
   * @throws java.lang.IllegalArgumentException if `atMost` is Duration.Undefined
   * @throws java.lang.InterruptedException if the current thread is interrupted while waiting
   * @throws java.util.concurrent.TimeoutException if after waiting for `atMost`, no message arrives
   * @throws scala.util.control.NonFatal in case of other errors (e.g. deserialization or network issues)
   */
  def receive(implicit atMost: Duration): T
  
  /** Receive and return message, blocking until its arrival, or until a failure.
   * 
   * The default implementation of this method corresponds to
   * [[tryReceive(implicit atMost:*]] with an infinite timeout value.
   * 
   * Once a message `msg` is received, this method returns`Success(msg)`.
   * In case of errors, the method returns `Failure(exception)`.
   * 
   * @param atMost Maximum wait time
   */
  def tryReceive(): Try[T] = {
    try {
      Success(receive())
    } catch {
      case scala.util.control.NonFatal(e) => Failure(e)
    }
  }
  
  /** Receive and return message, blocking until its arrival, or until a failure.
   * 
   * Once a message `msg` is received, this method returns`Success(msg)`.
   * In case of errors, the method returns `Failure(exception)`.
   * 
   * @param atMost Maximum wait time
   */
  def tryReceive(implicit atMost: Duration): Try[T] = {
    try {
      Success(receive)
    } catch {
      case scala.util.control.NonFatal(e) => Failure(e)
    }
  }
  
  /** Wait for a message, pass it as argument to `f`, get the return value.
   * 
   * Once a message `msg` is received, this method returns `f(msg)`.
   * 
   * @param atMost Maximum wait time
   * 
   * @throws java.lang.IllegalArgumentException if `atMost` is Duration.Undefined
   * @throws java.lang.InterruptedException if the current thread is interrupted while waiting
   * @throws java.util.concurrent.TimeoutException if after waiting for `atMost`, no message arrives
   * @throws scala.util.control.NonFatal in case of other errors (e.g. deserialization or network issues)
   */
  def ?[R](f: T => R)(implicit atMost: Duration): R = {
    f(receive)
  }
  
  /** Wait for a message or error, pass it as argument to `f`, get the return value.
   * 
   * Once a message `msg` is received, this method returns
   * `f(Success(msg))`.  In case of errors, the method returns
   * `f(Failure(exception))`.
   * 
   * @param atMost Maximum wait time
   */
  def ??[R](f: Try[T] => R)(implicit atMost: Duration): R = {
    f(tryReceive)
  }
}

/** Base abstract class for output channel endpoints. */
abstract class Out[-T] extends Channel[Send] {
  private var _promise: Promise[_] = null // Will actually be Promise[T]
  /** Return a promise that, once completed with a value `v`,
   *  causes `v` to be sent along this channel endpoint. */
  def promise[U <: T] = synchronized {
    if (_promise == null) {
      _promise = Promise[Any] // Will be actually used as a Promise[T]
    }
    // The following cast is safe: the returned promise can only
    // be completed with U-typed values, which are also T-typed
    _promise.asInstanceOf[Promise[U]]
  }
  
  /** Return a pair of input/output linear channel endpoints */
  def create[U](): (In[U], Out[U])
  
/** Return a pair of input/output linear channel endpoints,
 *  with the ''input'' one meant to be ''used'' to receive a value,
 *  and the ''output'' meant to be ''sent'' as continuation
 *  within a session.
 *  
 *  This method is mainly used internally,
 *  e.g. by `!!`.
 *  The main difference wrt. [[create]] is that
 *  the returned channel endpoints might be optimized
 *  for the use described above; as a consequence,
 *  their behavior is undefined for other uses
 *  (e.g., if the input endpoint is sent as continuation in a session).
 *  
 *  The default implementation is an alias for [[create[U]()*]].
 *  Only use and/or override this method if you really know what you are doing.
 */
  def createContIn[U](): (In[U], Out[U]) = create[U]()
  
 /** Return a pair of input/output linear channel endpoints,
  *  with the ''input'' one meant to be ''sent'' as continuation
  *  within session,
  *  and the ''output'' meant to be ''used'' to send a value.
  *  
  *  This method is the "dual" of [[createContIn]], and similar remarks hold.
 	*/
  def createContOut[U](): (In[U], Out[U]) = create[U]()
  
  /** Send a message.
   *  
   *  @param msg Message to be sent
   *  
   *  @throws AlreadyUsed if the channel endpoint was already used for output
   */
  def send(msg: T): Unit
  
  /** Alias for [[send]]. */
  def !(msg: T) = send(msg)
  
  /** Create a pair of `U`-typed channel endpoints `(i,o)`,
   *  send the return value of `f(i)`, and return `o`.
   *  
   * This method automates channel creation in continuation-passing-style
   * protocols,
   * when the ''input'' endpoint must be sent as part of a `T`-typed message
   * (as returned by `f(i)`), 
   * and the ``output`` endpoint is later used to continue the interaction.
   * 
   * @param f Function that, when applied to an input channel endpoint,
   *          returns the message to be sent.
   */
  def !![U](f: In[U] => T): Out[U] = {
    val (cin, cout) = createContOut[U]()
    this ! f(cin)
    cout
  }
  
  /** Create a pair of `U`-typed channel endpoints `(i,o)`,
   *  send the return value of `f(o)`, and return `i`.
   *  
   * This method automates channel creation in continuation-passing-style
   * protocols,
   * when the ''output'' endpoint must be sent as part of a `T`-typed message
   * (as returned by `f(i)`), 
   * and the ``input`` endpoint is later used to continue the interaction.
   * 
   * @param f Function that, when applied to an output channel endpoint,
   *          returns the message to be sent.
   */
  def !![U](f: Out[U] => T): In[U] = {
    val (cin, cout) = createContIn[U]()
    this ! f(cout)
    cin
  }
}

/** Linear channel endpoint without input/output capabilities.
 * 
 * For most practical purposes, occurrences of this object can be often
 * replaced with `()`.
 * */
case object End extends Channel[None]
