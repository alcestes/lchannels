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
package lchannels.benchmarks.ring

import lchannels._
import scala.concurrent.{blocking, Promise, Future}
import scala.concurrent.duration.Duration

/** lchannels-based implementation (medium-independent) */
object LChannelsImpl {
  ////////////////////////////////////////////////////////////////////////////
  // Session type:
  // rec X . !Token(String) . X  (+)  !Stop() . End 
  ////////////////////////////////////////////////////////////////////////////
  sealed abstract class Command
  case class Fwd(msg: String)(val cont: In[Command]) extends Command
  case class Stop() extends Command
  ////////////////////////////////////////////////////////////////////////////
  
  @scala.annotation.tailrec
  def forwarder(in: In[Command],
                   out: Out[Command])
                  (implicit d: Duration): Unit = {
    in.receive match {
      case Stop() => out ! Stop()
      case m @ Fwd(msg) => forwarder(m.cont, out !! Fwd(msg)_)
    }
  }
  
  @scala.annotation.tailrec
  def master(in: In[Command],
             out: Out[Command],
             msg: String, ts: Long, n: Int)
            (implicit d: Duration): Long = {
    val ts2 = if (ts == 0) System.nanoTime() else ts
    if (n > 0) {
      val outCont = out !! Fwd(msg)_
      in.receive match {
        case Stop() => throw new RuntimeException("BUG in ring benchmark!")
        case m @ Fwd(_) => master(m.cont, outCont, msg, ts2, n-1)
      }
    } else {
      out ! Stop()
      in.receive match {
        case Stop() => System.nanoTime() - ts2
        case Fwd(_) => throw new RuntimeException("BUG in ring benchmark!")
      }
    }
  }
  
  @scala.annotation.tailrec
  def masterStream(in: In[Command],
                   out: Out[Command],
                   msg: String, ts: Long, msgCount: Int, sent: Int, recvd: Int)
                  (implicit d: Duration): Long = {
    val ts2 = if (ts == 0) System.nanoTime() else ts
    
    if (sent < msgCount) {
      masterStream(in, out !! Fwd(msg)_, msg, ts2, msgCount, sent+1, recvd)
    } else if (recvd < msgCount) {
      in.receive match {
        case m @ Fwd(_) => {
          masterStream(m.cont, out, msg, ts2, msgCount, sent, recvd+1)
        }
        case Stop() => throw new RuntimeException("BUG in ring benchmark!")
      }
    } else {
      out ! Stop()
      in.receive match {
        case Fwd(_) => throw new RuntimeException("BUG in ring benchmark!")
        case Stop() => System.nanoTime() - ts2
      }
    }
  }
}

/** Promise/Future-based implementation */
object PromiseFutureImpl {
  import scala.concurrent.Await

  sealed abstract class Command
  case class Fwd(msg: String, cont: Future[Command])
    extends Command
  case class Stop()
    extends Command
  
  @scala.annotation.tailrec
  def forwarder(in: Future[Command],
                out: Promise[Command])
               (implicit d: Duration): Unit = {
    Await.result(in, d) match {
      case Stop() => out.success(Stop())
      case Fwd(msg, cont) => {
        val p = Promise[Command]; val f = p.future
        out.success(Fwd(msg, f))
        forwarder(cont, p)
      }
    }
  }
  
  @scala.annotation.tailrec
  def master(in: Future[Command],
             out: Promise[Command],
             msg: String, ts: Long, n: Int)
            (implicit d: Duration): Long = {
    val ts2 = if (ts == 0) System.nanoTime() else ts
    if (n > 0) {
      val p = Promise[Command]; val f = p.future
      out.success(Fwd(msg, f))
      Await.result(in, d) match {
        case Stop() => throw new RuntimeException("BUG in ring benchmark!")
        case Fwd(_, cont) => {
          master(cont, p, msg, ts2, n-1)
        }
      }
    } else {
      out.success(Stop())
      Await.result(in, d) match {
        case Stop() => System.nanoTime() - ts2
        case Fwd(_, _) => throw new RuntimeException("BUG in ring benchmark!")
      }
    }
  }
  
  @scala.annotation.tailrec
  def masterStream(in: Future[Command],
                   out: Promise[Command],
                   msg: String, ts: Long, msgCount: Int, sent: Int, recvd: Int)
                  (implicit d: Duration): Long = {
    val ts2 = if (ts == 0) System.nanoTime() else ts
    
    if (sent < msgCount) {
      val cout = Promise[Command]; val cin = cout.future
      out.success(Fwd(msg, cin))
      masterStream(in, cout, msg, ts2, msgCount, sent+1, recvd)
    } else if (recvd < msgCount) {
      Await.result(in, d) match {
        case Fwd(_msg, cont) => {
          masterStream(cont, out, msg, ts2, msgCount, sent, recvd+1)
        }
        case Stop() => throw new RuntimeException("BUG in ring benchmark!")
      }
    } else {
      out.success(Stop())
      Await.result(in, d) match {
        case Fwd(_,_) => throw new RuntimeException("BUG in ring benchmark!")
        case Stop() => System.nanoTime() - ts2
      }
    }
  }
}

/** Scala Channel-based implementation */
object ScalaChannelsImpl {
  import scala.concurrent.Channel

  sealed abstract class Command
  case class Fwd(msg: String)
    extends Command
  case class Stop()
    extends Command
  
  @scala.annotation.tailrec
  def forwarder(in: Channel[Command],
                out: Channel[Command]): Unit = {
    in.read match {
      case Stop() => out.write(Stop())
      case Fwd(msg) => {
        out.write(Fwd(msg))
        forwarder(in, out)
      }
    }
  }
  
  @scala.annotation.tailrec
  def master(in: Channel[Command],
             out: Channel[Command],
             msg: String, ts: Long, n: Int): Long = {
    val ts2 = if (ts == 0) System.nanoTime() else ts
    if (n > 0) {
      out.write(Fwd(msg))
      in.read match {
        case Stop() => throw new RuntimeException("BUG in ring benchmark!")
        case Fwd(_) => {
          master(in, out, msg, ts2, n-1)
        }
      }
    } else {
      out.write(Stop())
      in.read match {
        case Stop() => System.nanoTime() - ts2
        case Fwd(_) => throw new RuntimeException("BUG in ring benchmark!")
      }
    }
  }
  
  @scala.annotation.tailrec
  def masterStream(in: Channel[Command],
                   out: Channel[Command],
                   msg: String, ts: Long, msgCount: Int, sent: Int, recvd: Int)
                  (implicit d: Duration): Long = {
    val ts2 = if (ts == 0) System.nanoTime() else ts
    
    if (sent < msgCount) {
      out.write(Fwd(msg))
      masterStream(in, out, msg, ts2, msgCount, sent+1, recvd)
    } else if (recvd < msgCount) {
      in.read match {
        case Fwd(_) => {
          masterStream(in, out, msg, ts2, msgCount, sent, recvd+1)
        }
        case Stop() => throw new RuntimeException("BUG in ring benchmark!")
      }
    } else {
      out.write(Stop())
      in.read match {
        case Fwd(_) => throw new RuntimeException("BUG in ring benchmark!")
        case Stop() => System.nanoTime() - ts2
      }
    }
  }
}

/** Java blocking queues-based implementation */
object JavaBlockingQueuesImpl {
  import java.util.concurrent.BlockingQueue

  sealed abstract class Command
  case class Fwd(msg: String)
    extends Command
  case class Stop()
    extends Command
  
  @scala.annotation.tailrec
  def forwarder(in: BlockingQueue[Command],
                out: BlockingQueue[Command]): Unit = {
    in.take() match {
      case Stop() => out.put(Stop())
      case Fwd(msg) => {
        out.put(Fwd(msg))
        forwarder(in, out)
      }
    }
  }
  
  @scala.annotation.tailrec
  def master(in: BlockingQueue[Command],
             out: BlockingQueue[Command],
             msg: String, ts: Long, n: Int): Long = {
    val ts2 = if (ts == 0) System.nanoTime() else ts
    if (n > 0) {
      out.put(Fwd(msg))
      in.take() match {
        case Stop() => throw new RuntimeException("BUG in ring benchmark!")
        case Fwd(_) => {
          master(in, out, msg, ts2, n-1)
        }
      }
    } else {
      out.put(Stop())
      in.take() match {
        case Stop() => System.nanoTime() - ts2
        case Fwd(_) => throw new RuntimeException("BUG in ring benchmark!")
      }
    }
  }
  
  @scala.annotation.tailrec
  def masterStream(in: BlockingQueue[Command],
                   out: BlockingQueue[Command],
                   msg: String, ts: Long, msgCount: Int, sent: Int, recvd: Int)
                  (implicit d: Duration): Long = {
    val ts2 = if (ts == 0) System.nanoTime() else ts
    
    if (sent < msgCount) {
      out.put(Fwd(msg))
      masterStream(in, out, msg, ts2, msgCount, sent+1, recvd)
    } else if (recvd < msgCount) {
      in.take() match {
        case Fwd(_) => {
          masterStream(in, out, msg, ts2, msgCount, sent, recvd+1)
        }
        case Stop() => throw new RuntimeException("BUG in ring benchmark!")
      }
    } else {
      out.put(Stop())
      in.take() match {
        case Fwd(_) => throw new RuntimeException("BUG in ring benchmark!")
        case Stop() => System.nanoTime() - ts2
      }
    }
  }
}

/** Akka Typed implementation, optimized with actor reusage. */
object AkkaTypedImpl {
  import akka.typed.{Behavior, ActorRef, Props}
  import akka.typed.ScalaDSL.{Same, Stopped, Total}
  
  sealed abstract class Command
  case class Fwd(msg: String) extends Command
  case class Stop() extends Command
  
  def forwarderBeh(fwdTo: ActorRef[Command]) = Total[Command] {
    case m @ Fwd(_) => { fwdTo ! m; Same }
    case Stop() => { fwdTo ! Stop(); Stopped }
  }
  
  def masterBeh(fwdToP: Future[ActorRef[Command]],
                n: Int, endTS: Promise[Long])
               (implicit d: Duration): Behavior[Command] = Total[Command] {
    case m @ Fwd(msg) => {
      val fwdTo = scala.concurrent.Await.result(fwdToP, d) // FIXME: optimize?
      if (n > 0) {
        fwdTo ! m
        masterBeh(fwdToP, n-1, endTS)
      } else {
        fwdTo ! Stop()
        Same
      }
    }
    case Stop() => {
      endTS success System.nanoTime()
      Stopped
    }
  }
  
  def benchmark(msg: String, exchanges: Int, ringSize: Int,
                maxDuration: Duration)
               (implicit as: akka.actor.ActorSystem) = {
    import akka.typed.Ops
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import scala.collection.mutable.{Buffer => MBuffer}
    
    // implicit val timeout = 10.seconds
    implicit val timeout = Duration.Inf
    
    val endTS = Promise[Long]
    val fwdToP = Promise[ActorRef[Command]]
    
    val masterRef =  Ops.ActorSystemOps(as).spawn(
        Props(masterBeh(fwdToP.future, exchanges, endTS))
    )
    
    val forwarderRefs = MBuffer[ActorRef[Command]](masterRef)
    
    for (j <- 1 until ringSize) {
      val r = Ops.ActorSystemOps(as).spawn(
        Props(forwarderBeh(forwarderRefs(j-1)))
      )
      forwarderRefs += r
    }
    
    fwdToP success forwarderRefs(ringSize-1)
    
    val startTS = System.nanoTime()
    masterRef ! Fwd(msg)
    
    Await.result(endTS.future, maxDuration) - startTS
  }
}

object Benchmark {
  import benchmarks.{BenchmarkResult, BenchmarkResults}
  import scala.concurrent.Await
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._
  
  import java.util.concurrent.BlockingQueue
  
  import LChannelsImpl.Command
  
  /** Type of ring benchmark. */
  sealed abstract class RingType
  /** Standard ring benchmark. */
  case class Standard() extends RingType {
    override def toString = "Standard"
  }
  /** Ring benchmark where all messages are sent at once. */
  case class Streaming() extends RingType {
    override def toString = "Streaming"
  }
  
  /** Perform benchmarks. */
  def apply(which: RingType, msgCount: Int, ringSize: Int,
            repetitions: Int, msg: String): BenchmarkResults = {
    import scala.collection.mutable.{Buffer => MBuffer}
    case class Bench(title: String, f: () => Long, results: MBuffer[Long])
    
    implicit val as = akka.actor.ActorSystem("RingBenchmark",
                                        defaultExecutionContext = Some(global))
                             
    implicit val timeout = 3600.seconds
    val maxWait = 3600.seconds
    val loops = msgCount / ringSize
    
    println(f"*** Ring benchmark (${which}, ${ringSize} processes, ${loops} message loops)")
    
    val benchmarks = List(
      Bench("lchannels (Promise/Future)",
            () => lBenchmark(which, LocalChannel.factory,
                             msg, loops, ringSize, maxWait),
            MBuffer()),
      Bench("Promise/Future",
            () => pfBenchmark(which, msg, loops, ringSize, maxWait),
            MBuffer()),
      Bench("Scala channels",
            () => scBenchmark(which, msg, loops, ringSize, maxWait),
            MBuffer()),
      Bench("ArrayBlockingQueues",
            () => jqBenchmark(which, msg, loops, ringSize,
                              () => new java.util.concurrent.ArrayBlockingQueue[JavaBlockingQueuesImpl.Command](loops+1),
                              maxWait),
            MBuffer()),
      Bench("lchannels (queues)",
            () => lBenchmark(which, QueueChannel.factory,
                             msg, loops, ringSize, maxWait),
            MBuffer()),
      Bench("LinkedTransferQueues",
            () => jqBenchmark(which, msg, loops, ringSize,
                              () => new java.util.concurrent.LinkedTransferQueue[JavaBlockingQueuesImpl.Command](),
                              maxWait),
            MBuffer()),
      Bench("lchannels (actors)",
            () => lBenchmark(which, ActorChannel.factory,
                             msg, loops, ringSize, maxWait),
            MBuffer())
      )
      // Bench("Akka Typed",
      //       () => AkkaTypedImpl.benchmark(msg, exchanges, ringSize, maxWait),
      //       MBuffer())
    
    val rnd = new scala.util.Random()
    
    val pfRes = for (i <- 0 until repetitions) {
      print(f"\r    Repetition: ${i+1}/${repetitions}")
      // Execute benchmarks in random order at each iteration, for fairness
      for (b <- rnd.shuffle(benchmarks)) {
        System.gc(); System.runFinalization() // Best time to garbage collect
        b.results += b.f()
      }
    }
    println(" (Done)")
    
    // Shut down the actor system
    as.terminate()
    
    for (b <- benchmarks) yield BenchmarkResult(b.title, b.results.iterator)
  }
  
  private def lBenchmark(which: RingType,
                         factory: () => (In[Command], Out[Command]),
                         msg: String, exchanges: Int, ringSize: Int,
                         maxWait: Duration)
                        (implicit d: Duration): Long = {
    val channels = for (j <- 0 until ringSize) yield factory()
    for (j <- 0 until ringSize-1) yield Future {
      blocking { LChannelsImpl.forwarder(channels(j)._1, channels(j+1)._2) }
    }
    val res = Future {
      blocking {
        which match {
          case Standard() => LChannelsImpl.master(channels(ringSize-1)._1,
                                                  channels(0)._2,
                                                  msg, 0, exchanges)
          case Streaming() => LChannelsImpl.masterStream(channels(ringSize-1)._1,
                                                         channels(0)._2,
                                                         msg, 0, exchanges, 0, 0)
        }
      }
    }
    Await.result(res, maxWait)
  }
  
  private def pfBenchmark(which: RingType, msg: String,
                          exchanges: Int, ringSize: Int, maxWait: Duration)
                         (implicit d: Duration): Long = {
    val channels = for (j <- 0 until ringSize) yield {
      val p = Promise[PromiseFutureImpl.Command]; val f = p.future
      (f, p)
    }
    for (j <- 0 until ringSize-1) Future {
      blocking {
        PromiseFutureImpl.forwarder(channels(j)._1, channels(j+1)._2)
      }
    }
    val res = Future {
      blocking {
        which match {
          case Standard() => PromiseFutureImpl.master(channels(ringSize-1)._1,
                                                      channels(0)._2,
                                                      msg, 0, exchanges)
          case Streaming() => PromiseFutureImpl.masterStream(channels(ringSize-1)._1,
                                                      channels(0)._2,
                                                      msg, 0, exchanges, 0, 0)
        }
      }
    }
    Await.result(res, maxWait)
  }
  
  private def scBenchmark(which: RingType, msg: String,
                          exchanges: Int, ringSize: Int, maxWait: Duration)
                         (implicit d: Duration): Long = {
    import scala.concurrent.Channel
    
    val channels = for (j <- 0 until ringSize) yield {
      new Channel[ScalaChannelsImpl.Command]
    }
    for (j <- 0 until ringSize-1) Future {
      blocking { ScalaChannelsImpl.forwarder(channels(j), channels(j+1)) }
    }
    val res = Future {
      blocking {
        which match {
          case Standard() => ScalaChannelsImpl.master(channels(ringSize-1),
                                                      channels(0),
                                                      msg, 0, exchanges)
          case Streaming() => ScalaChannelsImpl.masterStream(channels(ringSize-1),
                                                             channels(0),
                                                             msg, 0, exchanges, 0, 0)
        }
      }
    }
    Await.result(res, maxWait)
  }
  
  private def jqBenchmark(which: RingType, msg: String, exchanges: Int,
                          ringSize: Int,
                          qFactory: () => BlockingQueue[JavaBlockingQueuesImpl.Command],
                          maxWait: Duration)
                         (implicit d: Duration): Long = {
    val channels = for (j <- 0 until ringSize) yield {
      // NOTE: qFactory must return queues big enough for all message exchanges
      qFactory()
    }
    for (j <- 0 until ringSize-1) yield Future {
      blocking {
        JavaBlockingQueuesImpl.forwarder(channels(j), channels(j+1))
      }
    }
    val res = Future {
      blocking {
        which match {
          case Standard() =>  JavaBlockingQueuesImpl.master(channels(ringSize-1),
                                                            channels(0),
                                                            msg, 0, exchanges)
          case Streaming() =>  JavaBlockingQueuesImpl.masterStream(channels(ringSize-1),
                                                                   channels(0),
                                                                   msg, 0, exchanges, 0, 0)
        }
      }
    }
    Await.result(res, maxWait)
  }
}
