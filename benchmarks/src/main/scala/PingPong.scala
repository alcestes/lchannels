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
package lchannels.benchmarks.pingpong

import lchannels._
import scala.concurrent.{Await, blocking, Promise, Future}
import scala.concurrent.duration.Duration

/** lchannels-based implementation (medium-independent). */
object LChannelsImpl {
  ////////////////////////////////////////////////////////////////////////////
  // Session type:
  // rec X . ?Ping(String) . (!Pong(String). X (+) Stop())
  ////////////////////////////////////////////////////////////////////////////
  sealed abstract class Request
  case class Ping(msg: String)(val cont: Out[Pong]) extends Request
  case class Stop() extends Request
 
  case class Pong(msg: String)(val cont: Out[Request])
  ////////////////////////////////////////////////////////////////////////////
  
  @scala.annotation.tailrec
  def pinger(c: Out[Request], msg: String, ts: Long, n: Int): Long = {
    // Update timestamp if not yet set, thus registering actual start time
    val ts2 = if (ts == 0) System.nanoTime() else ts
    if (n > 0) {
      val pingResponse = c !! Ping(msg)_
      pingResponse.receive() match {
        case pong => pinger(pong.cont, msg, ts2, n-1)
      }
    } else {
      c ! Stop()
      ts2
    }
  }
  
  @scala.annotation.tailrec
  def ponger(c: In[Request]): Long = {
    c.receive() match {
      case ping @ Ping(msg) => ponger(ping.cont !! Pong(msg)_) 
      case Stop() => System.nanoTime()
    }
  }
}

/** Promise/Future-based implementation. */
object PromiseFutureImpl {
  import scala.concurrent.Await
  
  ////////////////////////////////////////////////////////////////////////////
  // Session type:
  // rec X . ?Ping(String) . !Pong(String). X
  ////////////////////////////////////////////////////////////////////////////
  sealed abstract class Request
  case class Ping(msg: String)(val cont: Promise[Pong]) extends Request
  case class Stop() extends Request
  case class Pong(msg: String)(val cont: Promise[Request])
  ////////////////////////////////////////////////////////////////////////////
  
  @scala.annotation.tailrec
  def pinger(p: Promise[Request], msg: String, ts: Long, n: Int)
            (implicit d: Duration): Long = {
    // Update timestamp if not yet set, thus registering actual start time
    val ts2 = if (ts == 0) System.nanoTime() else ts 
    if (n > 0) {
      val p2 = Promise[Pong]; val f2 = p2.future
      p success Ping(msg)(p2)
      val pong = Await.result(f2, d) 
      pinger(pong.cont, msg, ts2, n-1)
    } else {
      p success Stop()
      ts2
    }
  }
  
  @scala.annotation.tailrec
  def ponger(f: Future[Request])
            (implicit d: Duration): Long = {
    Await.result(f, d) match {
      case ping @ Ping(msg) => {
        val p2 = Promise[Request]; val f2 = p2.future
        ping.cont success Pong(msg)(p2)
        ponger(f2) 
      }
      case Stop() => System.nanoTime()
    }
  }
}

/** Scala Channel-based implementation. */
object ScalaChannelsImpl {
  import scala.concurrent.Channel
  
  sealed abstract class Request
  case class Ping(msg: String) extends Request
  case class Stop() extends Request
  
  case class Pong(msg: String)
  
  @scala.annotation.tailrec
  def pinger(in: Channel[Pong], out: Channel[Request],
                msg: String, ts: Long, n: Int): Long = {
    // Update timestamp if not yet set, thus registering actual start time
    val ts2 = if (ts == 0) System.nanoTime() else ts
    if (n > 0) {
      out.write(Ping(msg))
      in.read
      pinger(in, out, msg, ts2, n-1)
    } else {
      out.write(Stop())
      ts2
    }
  }
  
  @scala.annotation.tailrec
  def ponger(in: Channel[Request], out: Channel[Pong]): Long = {
    in.read match {
      case Ping(msg) => out.write(Pong(msg)); ponger(in, out)
      case Stop() => System.nanoTime()
    }
  }
}

/** Java blocking queues-based implementation */
object JavaBlockingQueuesImpl {
  import java.util.concurrent.BlockingQueue
  
  sealed abstract class Request
  case class Ping(msg: String) extends Request
  case class Stop() extends Request
  
  case class Pong(msg: String)
  
  @scala.annotation.tailrec
  def pinger(in: BlockingQueue[Pong], out: BlockingQueue[Request],
             msg: String, ts: Long, n: Int): Long = {
    // Update timestamp if not yet set, thus registering actual start time
    val ts2 = if (ts == 0) System.nanoTime() else ts
    if (n > 0) {
      out.put(Ping(msg))
      in.take()
      pinger(in, out, msg, ts2, n-1)
    } else {
      out.put(Stop())
      ts2
    }
  }
  
  @scala.annotation.tailrec
  def ponger(in: BlockingQueue[Request], out: BlockingQueue[Pong]): Long = {
    in.take() match {
      case Ping(msg) => out.put(Pong(msg)); ponger(in, out)
      case Stop() => System.nanoTime()
    }
  }
}

/** Akka Typed implementation, optimized with actor reusage. */
object AkkaTypedImpl {
  import akka.typed.{ActorRef, Behavior}
  import akka.typed.ScalaDSL.{
    ContextAware, Same, Stopped, Total
  }
  import akka.typed.adapter.ActorSystemOps
  
  sealed abstract class Request
  case class Ping(msg: String, replyTo: ActorRef[Pong]) extends Request
  case class Stop() extends Request
  
  case class Pong(msg: String, replyTo: ActorRef[Request])
  
  def pingerBeh(msg: String, n: Int): Behavior[Pong] = {
    ContextAware[Pong] { ctx =>
      Total[Pong] {
        case Pong(_, replyTo) => {
          if (n > 0) {
            val contBeh = ctx.spawnAnonymous(pingerBeh(msg, n-1))
            replyTo ! Ping(msg, contBeh)
            Same
          } else {
            replyTo ! Stop()
            Stopped
          }
        }
      }
    }
  }
  
  def pongerBeh(endTS: Promise[Long]): Behavior[Request] = {
    ContextAware[Request] { ctx =>
      Total[Request] {
        case Ping(msg, replyTo) => {
          val contBeh = ctx.spawnAnonymous(pongerBeh(endTS))
          replyTo ! Pong(msg, contBeh)
          Same
        }
        case Stop() => {
          endTS success System.nanoTime()
          Stopped
        }
      }
    }
  }
  
  def pingerBehOpt(msg: String, n: Int): Behavior[Pong] = {
    ContextAware[Pong] { ctx =>
      Total[Pong] {
        case Pong(_, replyTo) => {
          if (n > 0) {
            replyTo ! Ping(msg, ctx.self)
            pingerBehOpt(msg, n-1)
          } else {
            replyTo ! Stop()
            Stopped
          }
        }
      }
    }
  }
  
  def pongerBehOpt(endTS: Promise[Long]): Behavior[Request] = {
    ContextAware[Request] { ctx =>
      Total[Request] {
        case Ping(msg, replyTo) => {
          replyTo ! Pong(msg, ctx.self)
          pongerBehOpt(endTS)
        }
        case Stop() => {
          endTS success System.nanoTime()
          Stopped
        }
      }
    }
  }
  
  def benchmark(msg: String, exchanges: Int, maxDuration: Duration)
               (implicit as: akka.actor.ActorSystem): Long = {
    benchmarkImpl(msg, exchanges, maxDuration, pingerBeh, pongerBeh)
  }
  
  def benchmarkOpt(msg: String, exchanges: Int, maxDuration: Duration)
                  (implicit as: akka.actor.ActorSystem): Long = {
    benchmarkImpl(msg, exchanges, maxDuration, pingerBehOpt, pongerBehOpt)
  }
  
  private def benchmarkImpl(msg: String, exchanges: Int, maxDuration: Duration,
                            pingerB: (String, Int) => Behavior[Pong],
                            pongerB: Promise[Long] => Behavior[Request])
                           (implicit as: akka.actor.ActorSystem)= {
    val endTS = Promise[Long]
    
    val pingRef = ActorSystemOps(as).spawnAnonymous(
      pingerB(msg, exchanges - 1)) // We will send the first Ping
    val pongRef = ActorSystemOps(as).spawnAnonymous(
      pongerB(endTS))
    
    val startTS = System.nanoTime()
    pongRef ! Ping(msg, pingRef)
    
    Await.result(endTS.future, maxDuration) - startTS
  }
}

object Benchmark {
  import lchannels.benchmarks.{BenchmarkResult, BenchmarkResults}
  import scala.concurrent.Await
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._
  
  import java.util.concurrent.BlockingQueue
  
  import LChannelsImpl.Request
  
  /** Perform benchmarks. */
  def apply(msgCount: Int, reps: Int, msg: String): BenchmarkResults = {
    import scala.collection.mutable.{Buffer => MBuffer}
    
    case class Bench(title: String, f: () => Long, results: MBuffer[Long])
    
    implicit val as = akka.actor.ActorSystem("PingPongBenchmark",
                                        defaultExecutionContext = Some(global))
                                        
    // implicit val timeout = 5.seconds
    implicit val timeout = Duration.Inf
    
    val maxWait = 3600.seconds
    val exchanges = msgCount / 2
    
    println(f"*** Ping-Pong benchmark (${exchanges} message exchanges)")
    
    val benchmarks = List(
      Bench("lchannels (Promise/Future)",
        () => lBench(LocalChannel.parallel, msg, exchanges, maxWait),
        MBuffer()),
      Bench("Promise/Future", () => pfBench(msg, exchanges, maxWait),
            MBuffer()),
      Bench("Scala channels", () => scBench(msg, exchanges, maxWait),
            MBuffer()),
      Bench("ArrayBlockingQueues", () => {
                jqBench(msg, exchanges,
                        () => new java.util.concurrent.ArrayBlockingQueue[JavaBlockingQueuesImpl.Pong](exchanges+1),
                        () => new java.util.concurrent.ArrayBlockingQueue[JavaBlockingQueuesImpl.Request](exchanges+1),
                        maxWait)
            },
            MBuffer()),
      Bench("lchannels (queues)",
        () => lBench(QueueChannel.parallel, msg, exchanges, maxWait),
        MBuffer()),
      Bench("LinkedTransferQueues", () => {
                jqBench(msg, exchanges,
                        () => new java.util.concurrent.LinkedTransferQueue[JavaBlockingQueuesImpl.Pong](),
                        () => new java.util.concurrent.LinkedTransferQueue[JavaBlockingQueuesImpl.Request](),
                        maxWait)
            },
            MBuffer()),
      Bench("lchannels (actors)",
        () => lBench(ActorChannel.parallel, msg, exchanges, maxWait),
        MBuffer())
      // Bench("Akka Typed",
      //       () => AkkaTypedImpl.benchmark(msg, exchanges, maxWait),
      //       MBuffer()),
      // Bench("Akka Typed (optim)",
      //       () => AkkaTypedImpl.benchmarkOpt(msg, exchanges, maxWait),
      //       MBuffer())
    )
    
    val rnd = new scala.util.Random()
    
    val pfRes = for (i <- 0 until reps) yield {
      print(f"\r    Repetition: ${i+1}/${reps}")
      // Execute benchmarks in random order at each iteration, for fairness
      for (b <- rnd.shuffle(benchmarks)) {
        System.gc(); System.runFinalization() // Best time to garbage collect
        b.results += b.f()
      }
    }
    println(" (Done)")
    
    // Cleanup and hut down the actor system
    ActorChannel.cleanup()
    as.terminate()
    
    for (b <- benchmarks) yield BenchmarkResult(b.title, b.results.iterator)
  }
  
  private def lBench(parallel: (In[Request] => Long,
                                Out[Request] => Long) => (Future[Long], Future[Long]),
                        msg: String,
                        exchanges: Int,
                        maxWait: Duration)
                       (implicit d: Duration): Long = {
    val (pong, ping) = parallel(
      LChannelsImpl.ponger(_), LChannelsImpl.pinger(_, msg, 0, exchanges)
    )
    val startTS = Await.result(ping, maxWait)
    val endTS = Await.result(pong, maxWait)
    endTS - startTS
  }
  
  private def pfBench(msg: String, exchanges: Int, maxWait: Duration)
                     (implicit d: Duration): Long = {
    val p = Promise[PromiseFutureImpl.Request]; val f = p.future
      
    val ping = Future {
      blocking { PromiseFutureImpl.pinger(p, msg, 0, exchanges) }
    }
    val pong = Future {
      blocking { PromiseFutureImpl.ponger(f) }
    }
      
    val startTS = Await.result(ping, maxWait)
    val endTS = Await.result(pong, maxWait)
    endTS - startTS
  }
  
  private def scBench(msg: String, exchanges: Int, maxWait: Duration): Long = {
    import scala.concurrent.Channel
    import ScalaChannelsImpl.{Request, Pong, pinger, ponger}
    
    val c1 = new Channel[Pong]()
    val c2 = new Channel[Request]()
    
    val ping = Future { blocking { pinger(c1, c2, msg, 0, exchanges) } }
    val pong = Future { blocking { ponger(c2, c1) } }
    
    val startTS = Await.result(ping, maxWait)
    val endTS = Await.result(pong, maxWait)
    endTS - startTS
  }
  
  private def jqBench(msg: String, exchanges: Int,
                      qFactoryPong: () => BlockingQueue[JavaBlockingQueuesImpl.Pong],
                      qFactoryRequest: () => BlockingQueue[JavaBlockingQueuesImpl.Request],
                      maxWait: Duration): Long = {
    import JavaBlockingQueuesImpl.{pinger, ponger}
    
    // Make the queues big enough to contain all the message exchanges
    val c1 = qFactoryPong()
    val c2 = qFactoryRequest()
    
    val ping = Future { blocking { pinger(c1, c2, msg, 0, exchanges) } }
    val pong = Future { blocking { ponger(c2, c1) } }
    
    val startTS = Await.result(ping, maxWait)
    val endTS = Await.result(pong, maxWait)
    endTS - startTS
  }
}
