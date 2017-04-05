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

import scala.concurrent.duration.Duration

import java.net.{Socket => JSocket}
import java.io.{InputStream, OutputStream}
    
/** The medium of socket-based channel endpoints. */
case class Socket()

/** Base class for socket management and (de)serialization of messages.
 *  
 *  This class assumes to have exclusive control over the given socket.
 *  
 *  @param socket Socket for sending/receiving data
 */
abstract class SocketManager(socket: JSocket) {
  /** The `InputStream` of `socket`. */
  protected val in: InputStream = {
    socket.setSoTimeout(0) // Make the socket blocking by default
    socket.getInputStream()
  }
  
  /** The `OutputStream` of `socket`. */
  protected val out: OutputStream = socket.getOutputStream()
  
  /** Read data from [[in]], deserialize an object and return it.
   *  
   *  @param atMost Maximum wait time
   *  
   *  @throws java.util.concurrent.TimeoutException if after waiting for `atMost`, no message arrives
   *  @throws Exception if a deserialization error occurs.
   */
  protected[lchannels] final def destreamer(atMost: Duration): Any = {
    if (atMost.isFinite) {
      socket.setSoTimeout(java.lang.Math.toIntExact(atMost.toMillis))
    }
    val recvd = destreamer()
    if (atMost.isFinite) {
      socket.setSoTimeout(0)
    }
    recvd
  }
  
  /** Read data from [[in]], deserialize an object and return it.
   *  
   *  @throws Exception if a deserialization error occurs.
   */
  def destreamer(): Any
  
  /** Serialize an object and write it into [[out]].
   *  
   *  @param x Object to serialize.
   */
  def streamer(x: Any): Unit
  
  /** Close the socket.
   *  
   *  You could derive this method to perform additional cleanup
   *  when closing the `StreamManager`.
   */
  def close(): Unit = { socket.close() }
  
  /** Alias for [[close]]. */
  final override def finalize() = close()
  
  /** Create a pair of I/O socket-based channel endpoints,
   *  reading from `in` and writing to `out`.
   *  
   *  @param ec Execution context for internal `Promise`/`Future` handling
   */
  def factory[T](): (SocketIn[T], SocketOut[T]) = {
    (SocketIn[T](this), SocketOut[T](this))
  }
}

/** Stream-based input channel endpoint, usually created
 *  through the [[[StreamIn$.apply* companion object]]]
 *  or via [[StreamManager.factory]].
 */
protected[lchannels] class SocketIn[T](sktm: SocketManager)
    extends medium.In[Socket, T] {
  override def receive() = {
    sktm.destreamer().asInstanceOf[T]
  }
  
  override def receive(implicit atMost: Duration) = {
    try {
      sktm.destreamer(atMost).asInstanceOf[T]
    } catch {
      case e: java.net.SocketTimeoutException => {
        throw new java.util.concurrent.TimeoutException(e.getMessage())
      }
    }
  }
}

/** Stream-based input channel endpoint. */
object SocketIn {
  /** Return a socket-based input channel endpoint.
   * 
   * @param strm Socket manager owning the input/output data streams
   */
  def apply[T](sktm: SocketManager) = {
    new SocketIn[T](sktm)
  }
}

/** Stream-based input channel endpoint, usually created
 *  through the [[[StreamOut$.apply* companion object]]]
 *  or via [[StreamManager.factory]].
 */
class SocketOut[-T](sktm: SocketManager)
    extends medium.Out[Socket, T] {
  override def send(x: T) = sktm.streamer(x)

  override def create[U]() = sktm.factory()
}

/** Stream-based output channel endpoint. */
object SocketOut {
  /** Return a socket-based output channel endpoint.
   * 
   * @param strm Socket manager owning the input/output data streams
   */
  def apply[T](sktm: SocketManager) = {
    new SocketOut[T](sktm)
  }
}
