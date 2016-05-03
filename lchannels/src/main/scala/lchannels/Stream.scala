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
import scala.util.Try

import java.io.{InputStream, OutputStream}
    
/** The medium of stream-based channel endpoints. */
case class Stream()

/** Base class for stream management and (de)serialization of messages.
 *  
 *  @param in Stream of input data
 *  @param out Stream of output data
 */
abstract class StreamManager(in: InputStream, out: OutputStream) {
  /** Read data from `in`, deserialize an object and return it.
   *  
   *  @throws Exception if a deserialization error occurs.
   */
  def destreamer(): Any
  
  /** Serialize an object and write it into `out`.
   *  
   *  @param x Object to serialize.
   */
  def streamer(x: Try[Any]): Unit
  
  /** Close `in` and `out` streams.
   *  
   *  You could derive this method to perform additional cleanup
   *  when closing the `StreamManager`.
   */
  def close(): Unit = { in.close(); out.close() }
  
  /** Alias for [[close]]. */
  final override def finalize() = close()
  
  /** Create a pair of I/O stream-based channel endpoints,
   *  reading from `in` and writing to `out`.
   *  
   *  @param ec Execution context for internal `Promise`/`Future` handling
   */
  def factory[T]()(implicit ec: ExecutionContext) = {
    (StreamIn[T](this), StreamOut[T](this))
  }
}

/** Stream-based input channel endpoint, usually created
 *  through the [[[StreamIn$.apply* companion object]]]
 *  or via [[StreamManager.factory]].
 */
protected[lchannels] class StreamIn[T](strm: StreamManager)
                                      (implicit ec: ExecutionContext)
    extends medium.In[Stream, T] {
  private var _future: Future[T] = null // Only destream when using future
  override def future = synchronized {
    if (_future == null) {
      // The following cast might fail on "bad" messages: this is what we want
      _future = Future[T] { strm.destreamer().asInstanceOf[T] }
    }
    _future
  }
}

/** Stream-based input channel endpoint. */
object StreamIn {
  /** Return a stream-based input channel endpoint.
   * 
   * @param strm Stream manager owning the input/output data streams
   * @param ec Execution context for internal `Promise`/`Future` handling
   */
  def apply[T](strm: StreamManager)
              (implicit ec: ExecutionContext) = {
    new StreamIn[T](strm)
  }
}

/** Stream-based input channel endpoint, usually created
 *  through the [[[StreamOut$.apply* companion object]]]
 *  or via [[StreamManager.factory]].
 */
class StreamOut[-T](strm: StreamManager)
                   (implicit ec: ExecutionContext)
    extends medium.Out[Stream, T] {
  private def streamerT(x: Try[T]): Unit = strm.streamer(x) // Poor man's safety

  private var _promise: Promise[_] = null // Will actually be Promise[T]

  override def promise[U <: T] = synchronized {
    if (_promise == null) {
      _promise = Promise[Any] // Will be actually used as a Promise[T]

      // The following cast is safe: the returned promise can only
      // be completed with U-typed values, which are also T-typed
      _promise.future.asInstanceOf[Future[T]] onComplete { x => streamerT(x) }
    }
    // The following cast is safe: the returned promise can only
    // be completed with U-typed values, which are also T-typed
    _promise.asInstanceOf[Promise[U]]
  }

  override def create[U]() = strm.factory()
}

/** Stream-based output channel endpoint. */
object StreamOut {
  /** Return a stream-based output channel endpoint.
   * 
   * @param strm Stream manager owning the input/output data streams
   * @param ec Execution context for internal `Promise`/`Future` handling
   */
  def apply[T](strm: StreamManager)
              (implicit ec: ExecutionContext) = {
    new StreamOut[T](strm)
  }
}
