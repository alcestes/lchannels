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
package lchannels.benchmarks.chameneos

import lchannels._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import java.util.concurrent.{LinkedBlockingQueue => Fifo}

/** The color of a chameneos */
sealed abstract class Color { def next: Color }
case class Red()   extends Color { override def next = Green() }
case class Green() extends Color { override def next = Blue() }
case class Blue()  extends Color { override def next = Red() }

/** lchannels-based implementation (medium-independent). */
object LChannelsImpl {
  ////////////////////////////////////////////////////////////////////////////
  // Session type for chameneos-broker interaction (S_cham is defined below):
  // S = ?Start(S_cham).end & ?Wait(dual(S_Cham)).end & Closed()
  ////////////////////////////////////////////////////////////////////////////
  sealed abstract class Response
  case class Start(c: Out[Greeting]) extends Response
  case class Wait(c: In[Greeting])   extends Response
  case class Closed()                extends Response
  
  ////////////////////////////////////////////////////////////////////////////
  // Session type for chameneos interaction:
  // S_cham =       !Greet((String, Color)) . ?Answer((String, Color)) . end
  // dual(S_cham) = ?Greet((String, Color)) . !Answer((String, Color)) . end
  ////////////////////////////////////////////////////////////////////////////
  case class Greeting(name: String, color: Color)(val cont: Out[Answer])
  case class Answer(name: String, color: Color)
  ////////////////////////////////////////////////////////////////////////////
  
  class Broker(meetings: Int,
               rfactory: () => (In[Response], Out[Response]),
               cfactory: () => (In[Greeting], Out[Greeting]))
              (implicit ec: ExecutionContext) extends Runnable {
    // Queue of requests from chameneos
    private val requests = new Fifo[Out[Response]]()
    
    // Own thread
    private val thread = { val t = new Thread(this); t.start(); t }
    def quit() = thread.interrupt()
    
    private var active = false
    /** Allow the broker to provide connections */
    def activate() = synchronized {
      active = true
      notifyAll()
    }
    
    /** If [[activate]] was called, return a connection to the broker. */
    def connect(): In[Response] = synchronized {
      while (!active) wait() // Wait for activate()
      val (in, out) = rfactory()
      requests.put(out)
      in
    }
    
    override def run() = loop(meetings)
    
    @scala.annotation.tailrec
    private def loop(meetings: Int): Unit = {
      try {
        val r1 = requests.take()
        val r2 = requests.take()
        
        if (meetings == 0) {
          r1 ! Closed(); r2 ! Closed()
        } else {
          val (cin, cout) = cfactory() // Used for interaction btwn chameneos
          r1 ! Start(cout)
          r2 ! Wait(cin)
        }
      } catch {
        case _: InterruptedException => return // quit() was invoked
      }
      loop(if (meetings == 0) 0 else meetings-1)
    }
  }
  
  class Chameneos(name: String, var color: Color, broker: Broker)
                 (implicit ec: ExecutionContext) extends Runnable {
    // Own thread
    private val thread = { val t = new Thread(this); t.start(); t }
    def join() = thread.join()
    
    @scala.annotation.tailrec
    override final def run() = {
      broker.connect().receive() match {
        case Start(c) => {
          (c !! Greeting(name, color)_).receive()
          color = color.next
          run()
        }
        case Wait(c)  => {
          val m = c.receive(); m.cont ! Answer(name, color)
          color = color.next
          run()
        }
        case Closed() => ()
      }
    } 
  }
}

/** Promise/Future-based implementation */
object PromiseFutureImpl {
  import scala.concurrent.{Await, Future, Promise}
  
  ////////////////////////////////////////////////////////////////////////////
  // Session type for chameneos-broker interaction (T_cham is defined below):
  // T = ?Start(T_cham).end & ?Wait(dual(T_Cham)).end & Closed()
  ////////////////////////////////////////////////////////////////////////////
  sealed abstract class Response
  case class Start(c: Promise[Greeting]) extends Response
  case class Wait(c: Future[Greeting])   extends Response
  case class Closed()                    extends Response
  
  ////////////////////////////////////////////////////////////////////////////
  // Session type for chameneos interaction:
  // T_cham =       !Greet((String, Color)) . ?Answer((String, Color)) . end
  // dual(T_cham) = ?Greet((String, Color)) . !Answer((String, Color)) . end
  ////////////////////////////////////////////////////////////////////////////
  case class Greeting(name: String, color: Color, cont: Promise[Answer])
  case class Answer(name: String, color: Color)
  ////////////////////////////////////////////////////////////////////////////
  
  class Broker(meetings: Int)
              (implicit ec: ExecutionContext,
                        timeout: Duration) extends Runnable {
    // Queue of requests from chameneos
    private val requests = new Fifo[Promise[Response]]()
    
    // Own thread
    private val thread = { val t = new Thread(this); t.start(); t }
    def quit() = thread.interrupt()
    
    private var active = false
    /** Allow the broker to provide connections */
    def activate() = synchronized {
      active = true
      notifyAll()
    }
    
    /** If [[activate]] was called, return a connection to the broker. */
    def connect(): Future[Response] = synchronized {
      while (!active) wait() // Wait for activate()
      val out = Promise[Response]; val in = out.future
      requests.put(out)
      in
    }
    
    override def run() = loop(meetings)
    
    @scala.annotation.tailrec
    private def loop(meetings: Int): Unit = {
      try {
        val r1 = requests.take()
        val r2 = requests.take()
        
        if (meetings == 0) {
          r1.success(Closed()); r2.success(Closed())
        } else {
          // Used for interaction btwn chameneos
          val cout = Promise[Greeting]; val cin = cout.future
          r1.success(Start(cout))
          r2.success(Wait(cin))
        }
      } catch {
        case _: InterruptedException => return // quit() was invoked
      }
      loop(if (meetings == 0) 0 else meetings-1)
    }
  }
  
  class Chameneos(name: String, var color: Color, broker: Broker)
                 (implicit ec: ExecutionContext,
                           timeout: Duration) extends Runnable {
    // Own thread
    private val thread = { val t = new Thread(this); t.start(); t }
    def join() = thread.join()
    
    @scala.annotation.tailrec
    override final def run() = {
      Await.result(broker.connect(), timeout) match {
        case Start(c) => {
          val aout = Promise[Answer]; val ain = aout.future
          c.success(Greeting(name, color, aout))
          Await.result(ain, timeout)
          color = color.next
          run()
        }
        case Wait(c)  => {
          val m = Await.result(c, timeout)
          m.cont.success(Answer(name, color))
          color = color.next
          run()
        }
        case Closed() => ()
      }
    } 
  }
}

/** Scala Channel-based implementation */
object ScalaChannelsImpl {
  import scala.concurrent.Channel
  
  ////////////////////////////////////////////////////////////////////////////
  // Session type for chameneos-broker interaction (T_cham is defined below):
  // T = ?Start(T_cham).end & ?Wait(dual(T_Cham)).end & Closed()
  ////////////////////////////////////////////////////////////////////////////
  sealed abstract class Response
  case class Start(cw: Channel[Greeting], cr: Channel[Answer]) extends Response
  case class Wait(cr: Channel[Greeting], cw: Channel[Answer])  extends Response
  case class Closed()               extends Response
  
  ////////////////////////////////////////////////////////////////////////////
  // Session type for chameneos interaction:
  // T_cham =       !Greet((String, Color)) . ?Answer((String, Color)) . end
  // dual(T_cham) = ?Greet((String, Color)) . !Answer((String, Color)) . end
  ////////////////////////////////////////////////////////////////////////////
  case class Greeting(name: String, color: Color)
  case class Answer(name: String, color: Color)
  ////////////////////////////////////////////////////////////////////////////
  
  class Broker(meetings: Int)
              (implicit ec: ExecutionContext,
                        timeout: Duration) extends Runnable {
    // Queue of requests from chameneos
    private val requests = new Fifo[Channel[Response]]()
    
    // Own thread
    private val thread = { val t = new Thread(this); t.start(); t }
    def quit() = thread.interrupt()
    
    private var active = false
    /** Allow the broker to provide connections */
    def activate() = synchronized {
      active = true
      notifyAll()
    }
    
    /** If [[activate]] was called, return a connection to the broker. */
    def connect(): Channel[Response] = synchronized {
      while (!active) wait() // Wait for activate()
      val c = new Channel[Response]()
      requests.put(c)
      c
    }
    
    override def run() = loop(meetings)
    
    @scala.annotation.tailrec
    private def loop(meetings: Int): Unit = {
      try {
        val r1 = requests.take()
        val r2 = requests.take()
        
        if (meetings == 0) {
          r1.write(Closed()); r2.write(Closed())
        } else {
          // Used for interaction btwn chameneos
          val cg = new Channel[Greeting]
          val ca = new Channel[Answer]
          r1.write(Start(cg, ca))
          r2.write(Wait(cg, ca))
        }
      } catch {
        case _: InterruptedException => return // quit() was invoked
      }
      loop(if (meetings == 0) 0 else meetings-1)
    }
  }
  
  class Chameneos(name: String, var color: Color, broker: Broker)
                 (implicit ec: ExecutionContext,
                           timeout: Duration) extends Runnable {
    // Own thread
    private val thread = { val t = new Thread(this); t.start(); t }
    def join() = thread.join()
    
    @scala.annotation.tailrec
    override final def run() = {
     broker.connect().read match {
        case Start(cg, ca) => {
          cg.write(Greeting(name, color))
          ca.read
          color = color.next
          run()
        }
        case Wait(cg, ca)  => {
          cg.read
          ca.write(Answer(name, color))
          color = color.next
          run()
        }
        case Closed() => ()
      }
    } 
  }
}

object JavaBlockingQueuesImpl {
  import java.util.concurrent.{BlockingQueue =>  BQueue}
  
  ////////////////////////////////////////////////////////////////////////////
  // Session type for chameneos-broker interaction (T_cham is defined below):
  // T = ?Start(T_cham).end & ?Wait(dual(T_Cham)).end & Closed()
  ////////////////////////////////////////////////////////////////////////////
  sealed abstract class Response
  case class Start(cw: BQueue[Greeting], cr: BQueue[Answer]) extends Response
  case class Wait(cr: BQueue[Greeting], cw: BQueue[Answer])  extends Response
  case class Closed()               extends Response
  
  ////////////////////////////////////////////////////////////////////////////
  // Session type for chameneos interaction:
  // T_cham =       !Greet((String, Color)) . ?Answer((String, Color)) . end
  // dual(T_cham) = ?Greet((String, Color)) . !Answer((String, Color)) . end
  ////////////////////////////////////////////////////////////////////////////
  case class Greeting(name: String, color: Color)
  case class Answer(name: String, color: Color)
  ////////////////////////////////////////////////////////////////////////////
  
  class Broker(meetings: Int,
               rfactory: () => BQueue[Response],
               gfactory: () => BQueue[Greeting],
               afactory: () => BQueue[Answer])
              (implicit ec: ExecutionContext,
                        timeout: Duration) extends Runnable {
    // Queue of requests from chameneos
    private val requests = new Fifo[BQueue[Response]]()
    
    // Own thread
    private val thread = { val t = new Thread(this); t.start(); t }
    def quit() = thread.interrupt()
    
    private var active = false
    /** Allow the broker to provide connections */
    def activate() = synchronized {
      active = true
      notifyAll()
    }
    
    /** If [[activate]] was called, return a connection to the broker. */
    def connect(): BQueue[Response] = synchronized {
      while (!active) wait() // Wait for activate()
      val c = rfactory()
      requests.put(c)
      c
    }
    
    override def run() = loop(meetings)
    
    @scala.annotation.tailrec
    private def loop(meetings: Int): Unit = {
      try {
        val r1 = requests.take()
        val r2 = requests.take()
        
        if (meetings == 0) {
          r1.put(Closed()); r2.put(Closed())
        } else {
          // Used for interaction btwn chameneos
          val cg = gfactory()
          val ca = afactory()
          r1.put(Start(cg, ca))
          r2.put(Wait(cg, ca))
        }
      } catch {
        case _: InterruptedException => return // quit() was invoked
      }
      loop(if (meetings == 0) 0 else meetings-1)
    }
  }
  
  class Chameneos(name: String, var color: Color, broker: Broker)
                 (implicit ec: ExecutionContext,
                           timeout: Duration) extends Runnable {
    // Own thread
    private val thread = { val t = new Thread(this); t.start(); t }
    def join() = thread.join()
    
    @scala.annotation.tailrec
    override final def run() = {
      broker.connect().take() match {
        case Start(cg, ca) => {
          cg.put(Greeting(name, color))
          ca.take()
          color = color.next
          run()
        }
        case Wait(cg, ca)  => {
          cg.take()
          ca.put(Answer(name, color))
          color = color.next
          run()
        }
        case Closed() => ()
      }
    } 
  }
}

object Benchmark {
  import benchmarks.{BenchmarkResult, BenchmarkResults}
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._
  
  import java.util.concurrent.BlockingQueue
  
  import LChannelsImpl.{Response => LResponse, Greeting => LGreeting}
  import JavaBlockingQueuesImpl.{
    Response => JQResponse, Greeting => JQGreeting, Answer => JQAnswer
  }
  
  /** Perform benchmarks. */
  def apply(msgCount: Int, nChameneos: Int, reps: Int): BenchmarkResults = {
    import scala.collection.mutable.{Buffer => MBuffer}
    case class Bench(title: String, f: () => Long, results: MBuffer[Long])
    
    implicit val as = akka.actor.ActorSystem("RingBenchmark",
                                        defaultExecutionContext = Some(global))
                             
    implicit val timeout = 3600.seconds
    
    // Each chameneos involved in a meeting sends/receives 3 messages:
    // 1. Response from broker, with channel towards another chameneos
    // 2. Greeting to/from other chameneos
    // 3. Answer from/to other chameneos
    // NOTE: here we are not counting the final Closed() messages
    val meetings = msgCount / (3 * 2)
    
    println(f"*** Chameneos benchmark (${nChameneos} chameneos, ${meetings} meeting(s))")
    
    val benchmarks = List(
      Bench("lchannels (Promise/Future)",
            () => lBenchmark(() => LocalChannel.factory(),
                             () => LocalChannel.factory(),
                             nChameneos, meetings),
            MBuffer()),
      Bench("Promise/Future",
            () => pfBenchmark(nChameneos, meetings),
            MBuffer()),
      Bench("Scala channels",
           () => scBenchmark(nChameneos, meetings),
           MBuffer()),
      Bench("ArrayBlockingQueues",
            () => jqBenchmark(nChameneos, meetings,
                              () => new java.util.concurrent.ArrayBlockingQueue[JQResponse](1),
                              () => new java.util.concurrent.ArrayBlockingQueue[JQGreeting](1),
                              () => new java.util.concurrent.ArrayBlockingQueue[JQAnswer](1)),
            MBuffer()),
      Bench("lchannels (queues)",
            () => lBenchmark(QueueChannel.factory, QueueChannel.factory,
                             nChameneos, meetings),
            MBuffer()),
      Bench("LinkedTransferQueues",
            () => jqBenchmark(nChameneos, meetings,
                              () => new java.util.concurrent.LinkedTransferQueue[JQResponse](),
                              () => new java.util.concurrent.LinkedTransferQueue[JQGreeting](),
                              () => new java.util.concurrent.LinkedTransferQueue[JQAnswer]()),  
            MBuffer()),
       Bench("lchannels (actors)",
             () => lBenchmark(ActorChannel.factory, ActorChannel.factory,
                              nChameneos, meetings),
             MBuffer())
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
  
  // Convenience function to provide an initial color for a chameneos
  private def colorMap(i: Int) = i % 3 match {
    case 0 => Red()
    case 1 => Green()
    case 2 => Blue()
  }
  
  private def lBenchmark(rfactory: () => (In[LResponse], Out[LResponse]),
                         cfactory: () => (In[LGreeting], Out[LGreeting]),
                         nChameneos: Int, meetings: Int)
                        (implicit ec: ExecutionContext,
                                  d: Duration): Long = {
    import LChannelsImpl.{Broker, Chameneos}
    val broker = new Broker(meetings, rfactory, cfactory)(ec)
    
    val chameneos = for (i <- 0 until nChameneos) yield {
      new Chameneos(f"Chameneos ${i}", colorMap(i), broker)(ec)
    }
    
    val startTime = System.nanoTime()
    broker.activate()
    for (c <- chameneos) c.join()
    val endTime = System.nanoTime() - startTime
    broker.quit()
    
    endTime
  }
  
  private def pfBenchmark(nChameneos: Int, meetings: Int)
                         (implicit ec: ExecutionContext,
                                   d: Duration): Long = {
    import PromiseFutureImpl.{Broker, Chameneos}
    val broker = new Broker(meetings)(ec, d)
    
    val chameneos = for (i <- 0 until nChameneos) yield {
      new Chameneos(f"Chameneos ${i}", colorMap(i), broker)(ec, d)
    }
    
    val startTime = System.nanoTime()
    broker.activate()
    for (c <- chameneos) c.join()
    val endTime = System.nanoTime() - startTime
    broker.quit()
    
    endTime
  }
  
  private def scBenchmark(nChameneos: Int, meetings: Int)
                         (implicit ec: ExecutionContext,
                                   d: Duration): Long = {
    import ScalaChannelsImpl.{Broker, Chameneos}
    val broker = new Broker(meetings)(ec, d)
    
    val chameneos = for (i <- 0 until nChameneos) yield {
      new Chameneos(f"Chameneos ${i}", colorMap(i), broker)(ec, d)
    }
    
    val startTime = System.nanoTime()
    broker.activate()
    for (c <- chameneos) c.join()
    val endTime = System.nanoTime() - startTime
    broker.quit()
    
    endTime
  }
  
    private def jqBenchmark(nChameneos: Int, meetings: Int,
                            rfactory: () => BlockingQueue[JQResponse],
                            gfactory: () => BlockingQueue[JQGreeting],
                            afactory: () => BlockingQueue[JQAnswer])
                           (implicit ec: ExecutionContext,
                                      d: Duration): Long = {
    import JavaBlockingQueuesImpl.{Broker, Chameneos}
    val broker = new Broker(meetings, rfactory, gfactory, afactory)(ec, d)
    
    val chameneos = for (i <- 0 until nChameneos) yield {
      new Chameneos(f"Chameneos ${i}", colorMap(i), broker)(ec, d)
    }
    
    val startTime = System.nanoTime()
    broker.activate()
    for (c <- chameneos) c.join()
    val endTime = System.nanoTime() - startTime
    broker.quit()
    
    endTime
  }
}
