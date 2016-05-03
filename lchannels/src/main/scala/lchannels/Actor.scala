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

import scala.annotation.meta.field
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Try, Success}

import java.util.concurrent.{LinkedTransferQueue => Fifo}

import akka.typed.{ActorRef, Props}
import akka.typed.ScalaDSL.{Stopped, Partial, Total}

/** The medium of actor-based channels. */
case class Actor()

protected[lchannels] object Defaults {
  import java.util.concurrent.atomic.AtomicReference
  import akka.actor.ActorSystem
  
  private val exCtx = new AtomicReference[ExecutionContext]()
  private val actorSys = new AtomicReference[akka.actor.ActorSystem]()
  
  def setExCtx(ec: ExecutionContext): Unit = exCtx.set(ec)
  
  def setExCtxIfUnset(ec: ExecutionContext): Boolean = {
    exCtx.compareAndSet(null, ec)
  }
  
  def getExCtx(ec: ExecutionContext): ExecutionContext = {
    if (ec == null) {
      // Try to use the default
      exCtx.get() match {
        case null => {
          // FIXME: more descriptive exception here
          throw new IllegalStateException("Cannot determine ExecutionContext")
        }
        case ctx => ctx
      }
    } else {
      ec
    }
  }
  
  def setActorSys(as: ActorSystem): Unit = actorSys.set(as)
  
  def setActorSysIfUnset(as: ActorSystem): Boolean = {
    actorSys.compareAndSet(null, as)
  }
  
  def getActorSys(as: ActorSystem): ActorSystem = {
    if (as == null) {
      // Try to use the default
      actorSys.get() match {
        case null => {
          // FIXME: more descriptive exception here
          throw new IllegalStateException("Cannot determine ActorSystem")
        }
        case sys => sys
      }
    } else {
      as
    }
  }
}

/** Channels that implement message delivery by automatically spawning
 *  Akka Typed actors. */
object ActorChannel {
  /** Set the default execution context for actor-based channels.
   * 
   * This default must be provided before using `ActorChannel`s over a network.
   */
  def setDefaultEC(ec: ExecutionContext) = Defaults.setExCtx(ec)
  
  /** Set the default execution context for actor-based channels,
   *  if it was not already set.
   * 
   * This default must be provided before using `ActorChannel`s over a network.
   * 
   * @return `true` if the default is updated, `false` if it was already set
   */
  def setDefaultECIfUnset(ec: ExecutionContext): Boolean = {
    Defaults.setExCtxIfUnset(ec)
  }
  
  /** Set the default actor system for actor-based channels.
   * 
   * This default must be provided before using `ActorChannel`s over a network.
   */
  def setDefaultAS(as: akka.actor.ActorSystem) = Defaults.setActorSys(as)
  
  /** Set the default execution context for actor-based channels,
   *  if it was not already set.
   * 
   * This default must be provided before using `ActorChannel`s over a network.
   * 
   * @return `true` if the default is updated, `false` if it was already set
   */
  def setDefaultASIfUnset(as: akka.actor.ActorSystem): Boolean = {
    Defaults.setActorSysIfUnset(as)
  }
  
  /** Create a pair of actor-based I/O channel endpoints.
   *  
   *  @param ec Execution context for internal `Promise`/`Future` handling
   *  @param as Actor context for internal actor management
   */
  def factory[T]()(implicit ec: ExecutionContext,
                            as: akka.actor.ActorSystem): (ActorIn[T],
                                                          ActorOut[T]) = {
    import akka.typed.Ops
    val bref = Ops.ActorSystemOps(as).spawn(
      Props(behaviors.bridgeBeh[T]))
    
    (new ActorIn[T](bref), new ActorOut[T](bref)(Defaults.getExCtx(ec),
                                                 Defaults.getActorSys(as)))
  }
  
  /** Create a pair of actor-based I/O channel endpoints, with a specific name.
   *  
   *  Unlike [[factory[T]()*]], this method allows to assign a meaningful name
   *  to the actor giving access to returned I/O channel endpoints: this is
   *  reflected in their Actor Paths.
   *  
   *  @see [[ActorIn.path]] and [[ActorOut.path]]
   *  
   *  @param name Name of the Akka actor giving access to the returned
   *              actor endpoints.
   *  @param ec Execution context for internal `Promise`/`Future` handling
   *  @param as Actor context for internal actor management
   */
  def factory[T](name: String)
                (implicit ec: ExecutionContext,
                          as: akka.actor.ActorSystem): (ActorIn[T],
                                                        ActorOut[T]) = {
    import akka.typed.Ops
    val bref = Ops.ActorSystemOps(as).spawn(
      Props(behaviors.bridgeBeh[T]), name)
    
    (new ActorIn[T](bref), new ActorOut[T](bref)(Defaults.getExCtx(ec),
                                                 Defaults.getActorSys(as)))
  }
  
  /** Spawn two functions as threads communicating via a pair of actor-based
   *  channel endpoints.
   *  
   *  This method invokes [[factory[T]()*]] to create a pair of channel endpoints
   *  `(in,out)`, and then spawns `p1(in)` and `p2(out)`.
   *  
   *  @return A pair of `Future`s `(f1, f2)`, completed respectively when
   *  `p1(in)` and `p2(out)` terminate.
   *  
   *  @param p1 Function using the input channel endpoint
   *  @param p2 Function using the output channel endpoint
   *  @param ec Execution context where the `p1` and `p2` will run
   *  @param as Actor context for internal actor management
   */
  def parallel[T, R1, R2](p1: ActorIn[T] => R1,
                          p2: ActorOut[T] => R2)
                         (implicit ec: ExecutionContext,
                                   as: akka.actor.ActorSystem): (Future[R1],
                                                                 Future[R2]) ={
    val (in, out) = factory[T]()
    ( Future { blocking { p1(in) } }, Future { blocking { p2(out) } } )
  }
}

package object behaviors {
  protected[lchannels] def bridgeBeh[T] = Total[ValueOrDest[T]] {
    case Value(v) => Partial {
      case Dest(ref) => {
        ref ! v
        Stopped
      }
    }
    case Dest(ref) => Partial {
      case Value(v) => {
        ref ! v
        Stopped
      }
    }
  }
}

private sealed abstract class ValueOrDest[T]
private case class Value[T](v: Try[T]) extends ValueOrDest[T]
private case class Dest[T](ref: ActorRef[Try[T]]) extends ValueOrDest[T]

/** Actor-based input channel endpoint,
 *  usually created through the [[[ActorIn$.apply* companion object]]]
 *  or via [[ActorChannel.factory]].
 */
// FIXME: with some fiddling, we should make ActorIn covariant on T
@SerialVersionUID(1L)
protected[lchannels] class ActorIn[T](bref: ActorRef[Dest[T]])
                                     (@(transient @field) implicit val as: akka.actor.ActorSystem)
    extends medium.In[Actor, T] with Serializable {
  // We have to reimplement usage flags in a serializable way
  private var used = false
  protected final def _markAsUsed() : Unit = synchronized {
    if (used) {
      throw new lchannels.AlreadyUsed()
    }
    used = true
  }
  
  /** Return the path of the Akka actor giving access to the channel endpoint
   *  
   *  The path allows to (remotely) proxy the channel endpoint,
   *  via [[ActorIn	$.apply]].
   */
  def path: akka.actor.ActorPath = bref.path
  
  @transient
  private var _ref: ActorRef[Try[T]] = null

  override def receive(implicit atMost: Duration) = {
    _markAsUsed()
    // FIXME: can be definitely optimised
    import akka.typed.Ops
    val (fifo, hT) = buildPFBeh
    _ref = Ops.ActorSystemOps(Defaults.getActorSys(as)).spawn(Props(hT))
    bref ! Dest(_ref)
    if (atMost.isFinite) {
      // recvd value has type Try[T]
      fifo.poll(atMost.length, atMost.unit).get.asInstanceOf[T]
    } else {
      fifo.take().get.asInstanceOf[T] // recvd value has type T
    }
  }

  private def buildPFBeh: (Fifo[Try[T]], Total[Try[T]]) = {
    val fifo = new Fifo[Try[T]]()
    val hT = Total[Try[T]]( v => { fifo.put(v); Stopped } )
    (fifo, hT)
  }

  override def toString() = {
    f"ActorIn@${hashCode} ← ${if (_ref != null) _ref.path else null} (bridge: ${bref.path})"
  }
}

/** Actor-based input channel endpoint. */
object ActorIn {
  private[lchannels] def apply[T](ref: ActorRef[Dest[T]])
                                 (implicit as: akka.actor.ActorSystem): ActorIn[T] = {
    new ActorIn(ref)
  }
  private[lchannels] def apply[T](ref: akka.actor.ActorRef)
                                 (implicit as: akka.actor.ActorSystem): ActorIn[T] = {
    apply(ActorRef[Dest[T]](ref))
  }
  /** Proxy an [[ActorIn]] instance reachable through the given Akka actor path
   *  
   *  @param path Actor path, matching the value of some [[ActorIn.path]]
   *  @param ec Execution context for internal `Promise`/`Future` handling
   *  @param as Actor system for internal actor handling
   *  @param timeout Max wait time for path resolution
   */
  def apply[T](path: akka.actor.ActorPath)
              (implicit ec: ExecutionContext,
                        as: akka.actor.ActorSystem,
                        timeout: FiniteDuration): ActorIn[T] = {
    apply(resolvePath(path, timeout))
  }
  
  /** Proxy an [[ActorIn]] instance reachable through the given Akka actor path
   *  (given as a string). 
   *  
   *  @param path Actor path, matching the value of some [[ActorIn.path]]
   *  @param ec Execution context for internal `Promise`/`Future` handling
   *  @param as Actor system for internal actor handling
   *  @param timeout Max wait time for path resolution
   */
  def apply[T](path: String)
              (implicit ec: ExecutionContext,
                        as: akka.actor.ActorSystem,
                        timeout: FiniteDuration): ActorIn[T] = {
    apply(akka.actor.ActorPaths.fromString(path))
  }

  private[lchannels] def resolvePath(path: akka.actor.ActorPath,
                                     timeout: FiniteDuration)
                                    (implicit ec: ExecutionContext,
                                              as: akka.actor.ActorSystem): akka.actor.ActorRef = {
    import akka.actor.{ActorIdentity, Identify}
    import akka.pattern.ask
    import scala.concurrent.Await

    val sel = Await.result(
      as.actorSelection(path).resolveOne(timeout), timeout)
    val ref = for {
      ans  <- ask(sel, Identify(1))(timeout).mapTo[ActorIdentity]
    } yield ans.getRef

    Await.result(ref, timeout)
  }
}

/** Actor-based output channel endpoint,
 *  usually created through the [[[ActorOut$.apply* companion object]]]
 *  or via [[ActorChannel.factory]].
 */
@SerialVersionUID(1L)
protected[lchannels] class ActorOut[-T](bref: ActorRef[Value[T]])
                                       (implicit @(transient @field) val ec: ExecutionContext,
                                                 @(transient @field) val as: akka.actor.ActorSystem)
    extends medium.Out[Actor, T] with Serializable {
  // We have to reimplement usage flags in a serializable way
  private var used = false
  protected final def _markAsUsed() : Unit = synchronized {
    if (used) {
      throw new lchannels.AlreadyUsed()
    }
    used = true
  }
  
  /** Return the path of the Akka actor giving access to the channel endpoint
   *  
   *  The path allows to (remotely) proxy the channel endpoint,
   *  via [[ActorOut$.apply]].
   */
  def path: akka.actor.ActorPath = bref.path
  
  override def send(v: T) = synchronized {
    _markAsUsed()
    bref ! Value(Success(v))
  }

  override def create[U](): (ActorIn[U], ActorOut[U]) = {
    ActorChannel.factory[U]()(Defaults.getExCtx(ec), Defaults.getActorSys(as))
  }
  
  override def toString() = {
    f"ActorOut@${hashCode} → ${bref.path}"
  }
}

/** Actor-based output channel endpoint. */
object ActorOut {
  private[lchannels] def apply[T](ref: ActorRef[Value[T]])
                                 (implicit ec: ExecutionContext,
                                           as: akka.actor.ActorSystem): ActorOut[T] = {
    new ActorOut(ref)
  }
  private[lchannels] def apply[T](ref: akka.actor.ActorRef)
                                 (implicit ec: ExecutionContext,
                                           as: akka.actor.ActorSystem): ActorOut[T] = {
    apply(ActorRef[Value[T]](ref))
  }
  
  /** Proxy an [[ActorOut]] instance reachable through the given Akka actor path
   *  
   *  @param path Actor path, matching the value of some [[ActorIn.path]]
   *  @param ec Execution context for internal `Promise`/`Future` handling
   *  @param as Actor system for internal actor handling
   *  @param timeout Max wait time for path resolution
   */
  def apply[T](path: akka.actor.ActorPath)
              (implicit ec: ExecutionContext,
                        as: akka.actor.ActorSystem,
                        timeout: FiniteDuration): ActorOut[T] = {
    apply(ActorIn.resolvePath(path, timeout))
  }
  /** Proxy an [[ActorOut]] instance reachable through the given Akka actor path
   *  (given as a string). 
   *  
   *  @param path Actor path, matching the value of some [[ActorIn.path]]
   *  @param ec Execution context for internal `Promise`/`Future` handling
   *  @param as Actor system for internal actor handling
   *  @param timeout Max wait time for path resolution
   */
  def apply[T](path: String)
              (implicit ec: ExecutionContext,
                        as: akka.actor.ActorSystem,
                        timeout: FiniteDuration): ActorOut[T] = {
    apply(akka.actor.ActorPaths.fromString(path))
  }
}
