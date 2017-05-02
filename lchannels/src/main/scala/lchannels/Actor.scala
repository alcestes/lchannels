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

import java.util.concurrent.TimeoutException

import akka.actor.{ActorPath, ActorRef, ActorSystem}

/** The medium of actor-based channels. */
case class Actor()

/* Type used by dispatcher behaviors, to receive their destination actor and
 * the actual transmitted value (in any order).
 */
private sealed abstract class ValueOrDest[T]
private case class Value[T](v: Try[T]) extends ValueOrDest[T]
private case class Dispatch[T](dest: ActorRef) extends ValueOrDest[T]

protected[lchannels] object Defaults {
  import java.util.concurrent.atomic.AtomicReference
  import java.util.concurrent.{LinkedTransferQueue => LTQueue}
  import akka.actor.{ActorSystem, Props}
  
  private val exCtx = new AtomicReference[ExecutionContext]()
  private val actorSys = new AtomicReference[ActorSystem]()

  // Dispatcher actors will receive messages and forward them to retrievers
  private val dispatchers = new LTQueue[ActorRef]()
  
  // Retriever actors will receive messages and write them in companion queue
  private val retrievers = new LTQueue[(ActorRef, LTQueue[Try[Any]])]()
  
  // This set tracks all actors that have been created thus far.
  // It will be used to kill active actors: see ActorChannels.cleanup()
  private val actors = scala.collection.mutable.Set[ActorRef]()

  
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
  
  class Dispatcher(stayAlive: Boolean) extends akka.actor.Actor {
    private var recvdValue: Option[Try[Any]] = None
    private var recvdDest: Option[ActorRef] = None
    
    override def receive = {
      case Value(v) => recvdDest match {
        case Some(ref) => {
          ref ! v
          if (!stayAlive) {
            actors.remove(self)
            self ! akka.actor.PoisonPill
          } else {
            // We can be reused
            recvdDest = None
            dispatchers.put(self)
          }
        }
        case None => recvdValue = Some(v) // We should reiceve dest later
      }
      case Dispatch(dest) => recvdValue match {
        case Some(v) => {
          dest ! v
          if (!stayAlive) {
            actors.remove(self)
            self ! akka.actor.PoisonPill
          } else {
             // We can be reused
            recvdValue = None
            dispatchers.put(self)
          }
        }
        case None => recvdDest = Some(dest) // We should receive the value later
      }
    }
  }
  
  class Retriever(queue: LTQueue[Try[Any]]) extends akka.actor.Actor {
    override def receive = {
      case v: Try[Any] => {
        queue.put(v)
        // We can be reused
        retrievers.put((self, queue))
      }
    }
  }
  
  def getDispatcher(as: ActorSystem, name: Option[String]): ActorRef = dispatchers.poll match {
    case ref: ActorRef => ref // We can reuse a dispatcher
    case null => {
      // No dispatcher is available: let's create a new one
      // TODO: allow to limit queue size and/or the number of live dispatchers
      // Note that if the dispatcher is given a name, it will NOT stay alive
      val ref = name match {
        case None => as.actorOf(Props(new Dispatcher(true)))
        case Some(name) => as.actorOf(Props(new Dispatcher(false)), name)
      }
      actors.add(ref) // Keep track of the actor
      ref
    }
  }
  
  def getRetriever(as: ActorSystem): (ActorRef, LTQueue[Try[Any]]) = retrievers.poll match {
    case (ref: ActorRef, q: LTQueue[Try[Any]]) => (ref, q) // We can reuse a retriever
    case null => {
      // No retriever is available: let's create a new one
      // TODO: allow to limit queue size and/or the number of live dispatchers
      val q = new LTQueue[Try[Any]]()
      val ref = as.actorOf(Props(new Retriever(q)))
      actors.add(ref) // Keep track of the actor
      (ref, q)
    }
  }
  
  def killActors() = {
    // TODO: should we forbid spawning new actors?  Or just leave it to user?
    actors.foreach { ref => ref ! akka.actor.PoisonPill }
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
  def setDefaultAS(as: ActorSystem) = Defaults.setActorSys(as)
  
  /** Set the default execution context for actor-based channels,
   *  if it was not already set.
   * 
   * This default must be provided before using `ActorChannel`s over a network.
   * 
   * @return `true` if the default is updated, `false` if it was already set
   */
  def setDefaultASIfUnset(as: ActorSystem): Boolean = {
    Defaults.setActorSysIfUnset(as)
  }
  
  /** Release the resources used by actor-based channels, resetting the
   *  default `ExecutionContext` and `ActorSystem`.
   *  
   *  Invoke this method before shutting down a previously used `ActorSystem`.
   */
  def cleanup() = {
    setDefaultEC(null)
    setDefaultAS(null)
    Defaults.killActors()
  }
  
  /** Create a pair of actor-based I/O channel endpoints.
   *  
   *  @param ec Execution context for internal `Promise`/`Future` handling
   *  @param as Actor context for internal actor management
   */
  def factory[T]()(implicit ec: ExecutionContext,
                            as: ActorSystem): (ActorIn[T], ActorOut[T]) = {
    val dec = Defaults.getExCtx(ec)
    val das = Defaults.getActorSys(as)
    val dref = Defaults.getDispatcher(das, None)
    
    (ActorIn[T](dref)(dec, das),
     ActorOut[T](dref)(dec, das))
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
                          as: ActorSystem): (ActorIn[T], ActorOut[T]) = {
    assert(name != "")
    val dec = Defaults.getExCtx(ec)
    val das = Defaults.getActorSys(as)
    val dref = Defaults.getDispatcher(das, Some(name))
    
    (ActorIn[T](dref)(dec, das),
     ActorOut[T](dref)(dec, das))
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
                                   as: ActorSystem): (Future[R1],
                                                                 Future[R2]) ={
    val (in, out) = factory[T]()
    ( Future { blocking { p1(in) } }, Future { blocking { p2(out) } } )
  }
}

/** Actor-based input channel endpoint,
 *  usually created through the [[[ActorIn$.apply* companion object]]]
 *  or via [[ActorChannel.factory]].
 */
// FIXME: with some fiddling, we should make ActorIn covariant on T
@SerialVersionUID(1L)
protected[lchannels] class ActorIn[T](dref: ActorRef  )
                                     (@(transient @field) implicit val ec: ExecutionContext,
                                      @(transient @field) implicit val as: ActorSystem)
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
  def path: ActorPath = dref.path
  
  override def receive(implicit atMost: Duration) = {
    _markAsUsed()
    // FIXME: the following can/should be optimised
    
    val das = Defaults.getActorSys(as)
    val (retr, q) = Defaults.getRetriever(das)
    
    val fv = dref ! Dispatch(retr)

    if (atMost.isFinite) {
      val v = q.poll(atMost.length, atMost.unit)
      if (v == null) {
        throw new TimeoutException(f"Input timed out after ${atMost}")
      }
      v.get.asInstanceOf[T] // recvd value has type Try[T]
    } else {
      q.take().get.asInstanceOf[T] // recvd value has type Try[T]
    } 
  }

  override def toString() = {
    f"ActorIn@${hashCode} (dispatcher: ${dref.path})"
  }
}

/** Actor-based input channel endpoint. */
object ActorIn {
  private[lchannels] def apply[T](dref: ActorRef)
                                 (implicit ec: ExecutionContext, as: ActorSystem): ActorIn[T] = {
    new ActorIn(dref)(ec, as)
  }
  
  /** Proxy an [[ActorIn]] instance reachable through the given Akka actor path
   *  
   *  @param path Actor path, matching the value of some [[ActorIn.path]]
   *  @param ec Execution context for internal `Promise`/`Future` handling
   *  @param as Actor system for internal actor handling
   *  @param timeout Max wait time for path resolution
   */
  def apply[T](path: ActorPath)
              (implicit ec: ExecutionContext,
                        as: ActorSystem,
                        timeout: FiniteDuration): ActorIn[T] = {
    apply(ActorIn.resolvePath(path, timeout))
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
                        as: ActorSystem,
                        timeout: FiniteDuration): ActorIn[T] = {
    apply(akka.actor.ActorPaths.fromString(path))
  }

  private[lchannels] def resolvePath(path: ActorPath,
                                     timeout: FiniteDuration)
                                    (implicit ec: ExecutionContext,
                                              as: ActorSystem): ActorRef = {
    import akka.actor.{ActorIdentity, Identify}
    import akka.pattern.ask
    import scala.concurrent.Await

    val sel = Await.result(
      as.actorSelection(path).resolveOne(timeout), timeout)
    val ref = for {
      ans  <- ask(sel, Identify(1))(timeout).mapTo[ActorIdentity]
    } yield ans.getActorRef.get // TODO: fail fast or propagate option?

    Await.result(ref, timeout)
  }
}

/** Actor-based output channel endpoint,
 *  usually created through the [[[ActorOut$.apply* companion object]]]
 *  or via [[ActorChannel.factory]].
 */
@SerialVersionUID(1L)
protected[lchannels] class ActorOut[-T](val dref: ActorRef)
                                       (implicit @(transient @field) val ec: ExecutionContext,
                                                 @(transient @field) val as: ActorSystem)
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
  def path: ActorPath = dref.path
  
  override def send(v: T) = synchronized {
    _markAsUsed()
    dref ! Value(Success(v))
  }

  override def create[U](): (ActorIn[U], ActorOut[U]) = {
    ActorChannel.factory[U]()(Defaults.getExCtx(ec), Defaults.getActorSys(as))
  }
  
  override def toString() = {
    f"ActorOut@${hashCode} → ${dref.path}"
  }
}

/** Actor-based output channel endpoint. */
object ActorOut {
  private[lchannels] def apply[T](dref: ActorRef)
                                 (implicit ec: ExecutionContext,
                                           as: ActorSystem): ActorOut[T] = {
    new ActorOut(dref)(ec, as)
  }
  
  /** Proxy an [[ActorOut]] instance reachable through the given Akka actor path
   *  
   *  @param path Actor path, matching the value of some [[ActorIn.path]]
   *  @param ec Execution context for internal `Promise`/`Future` handling
   *  @param as Actor system for internal actor handling
   *  @param timeout Max wait time for path resolution
   */
  def apply[T](path: ActorPath)
              (implicit ec: ExecutionContext,
                        as: ActorSystem,
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
                        as: ActorSystem,
                        timeout: FiniteDuration): ActorOut[T] = {
    apply(akka.actor.ActorPaths.fromString(path))
  }
}
