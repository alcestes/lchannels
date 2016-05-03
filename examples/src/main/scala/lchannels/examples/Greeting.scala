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
package lchannels.examples.greeting

import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.concurrent.duration.{Duration, DurationInt}
import lchannels._

/////////////////////////////////////////////////////////////////////////////
// Session type:
// rec X . (+) { !Greet(String) . & { ?Hello(String) . X , ?Bye(String) } ,
//               !Quit() }
/////////////////////////////////////////////////////////////////////////////
sealed abstract class Start
case class Greet(whom: String)(val cont: Out[Greeting]) extends Start
case class Quit()                                       extends Start

sealed abstract class Greeting
case class Hello(who:String)(val cont: Out[Start]) extends Greeting
case class Bye(who:String)                         extends Greeting
/////////////////////////////////////////////////////////////////////////////

/** Greeting protocol server. */
object Server {  
  def apply(c: In[Start])
           (implicit timeout: Duration): Unit = {
    println(f"[S] Awaiting request from ${c}...")
    c ? {
      case m @ Greet(whom) => {
        println(f"[S] Got 'Greet(${whom})', answering Hello")
        val c2in = m.cont !! Hello(whom)_
        println("[S] Performing recursion...")
        apply(c2in)
      }
      case Quit() => {
        println(f"[S] Got Quit(), finishing")
      }
    }
  }
  
  def serve(factory: () => (In[Start], Out[Start]))
           (implicit ctx: ExecutionContext,
                     timeout: Duration): Out[Start] = {
    val (cin, cout) = factory()
    Future { blocking { apply(cin)(timeout) } }
    cout
  }
  def serve()(implicit ctx: ExecutionContext,
                       timeout: Duration): Out[Start] = {
    serve(LocalChannel.factory)
  }
}

object Client1 {
  def apply(c: Out[Start])
           (implicit timeout: Duration): Unit = {
    println("[C1] Sending Greet(\"Jack\")...")
    val repc = c !! Greet("Jack")_
    println("[C1] ...done.  Now waiting for answer...")
    repc ? {
      case m @ Hello(who) => {
        println(f"[C1] Received 'Hello(${who})', now quitting...")
        m.cont ! Quit()
        println("[C1] ...done.")
      }
      case Bye(who) => {
        println(f"[C1] Received 'Bye(${who})', doing nothing")
      }
    }
  }
}

object Client2 {
  def apply(c: Out[Quit])
           (implicit timeout: Duration): Unit = {
    println(f"[C2] Sending ${Quit()}")
    c ! Quit()
  }
}

object Local extends App {
  // Helper method to ease external invocation
  def run() = main(Array())

  import lchannels.LocalChannel.parallel
  import scala.concurrent.Await
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._

  implicit val timeout = 10.seconds
  
  println("[*] Spawning local server and client 1...")
  val (c1, s1) = parallel[Start, Unit, Unit](
    Server(_), Client1(_)
  )

  Await.result(s1, 10.seconds) // Wait for server termination

  println("\n[*] Spawning local server and client 2...")
  val (c2, s2) = parallel[Quit, Unit, Unit](
    Server(_), Client2(_)
  )

  Await.result(s2, 10.seconds) // Wait for server termination
}

object Queue extends App {
  // Helper method to ease external invocation
  def run() = main(Array())

  import lchannels.QueueChannel.parallel
  import scala.concurrent.Await
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._

  implicit val timeout = 10.seconds
  
  println("[*] Spawning local server and client 1 (using queue-based channels)...")
  val (c1, s1) = parallel[Start, Unit, Unit](
    Server(_), Client1(_)
  )

  Await.result(s1, 10.seconds) // Wait for server termination

  println("\n[*] Spawning local server and client 2 (using queue-based channels)...")
  val (c2, s2) = parallel[Quit, Unit, Unit](
    Server(_), Client2(_)
  )

  Await.result(s2, 10.seconds) // Wait for server termination
}

object StreamClient extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import java.io.{
    InputStream, OutputStream,
    BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter
  }
  import java.net.Socket
  
  import scala.util.{Success, Failure}
  
  import scala.concurrent.ExecutionContext.Implicits.global
  implicit val timeout = 30.seconds
    
  class HelloStreamManager(in: InputStream, out: OutputStream)
        extends StreamManager(in, out) {  
    private val outb = new BufferedWriter(new OutputStreamWriter(out))
    
    override def streamer(x: scala.util.Try[Any]) = x match {
      case Failure(e) => close() // StreamManager.close() closes in & out
      case Success(v) => v match {
        case Greet(name) => outb.write(f"GREET ${name}\n"); outb.flush()
        case Quit() => outb.write("QUIT\n"); outb.flush(); close() // End
      }
    }
    
    private val inb = new BufferedReader(new InputStreamReader(in))
    private val helloR = """HELLO (.+)""".r // Matches Hello(name)
    private val byeR = """BYE (.+)""".r     // Matches Bye(name)
    
    override def destreamer() = inb.readLine() match {
      case helloR(name) => Hello(name)(StreamOut[Start](this))
      case byeR(name) => close(); Bye(name) // Session end: close streams
      case e => { close(); throw new Exception(f"Bad message: '${e}'") }
    }
  }
  
  println("[*] Connecting to 127.0.0.1:1337...")
  val conn = new Socket("127.0.0.1", 1337) // Host & port of greeting server
  val strm = new HelloStreamManager(conn.getInputStream, conn.getOutputStream)
  val c = StreamOut[Start](strm) // Output endpoint, towards greeting server
  Client1(c)
}

object ActorServer extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import scala.concurrent.duration.Duration
  import scala.concurrent.ExecutionContext.Implicits.global
  import com.typesafe.config.ConfigFactory
  import akka.actor.ActorSystem
  
  val config = ConfigFactory.load() // Loads resources/application.conf
  implicit val as = ActorSystem("GreetingServerSys",
                          config = Some(config.getConfig("GreetingServerSys")),
                          defaultExecutionContext = Some(global))
  
  ActorChannel.setDefaultEC(global)
  ActorChannel.setDefaultAS(as)
  
  implicit val timeout = Duration.Inf
  
  // We give a human-readable name ("greeting") to the server actor
  val (in, out) = ActorChannel.factory[Start]("start");
  println(f"[*] Greeting server listening on: ${out.path}")
  Server(in)
  
  as.terminate()
}

object ActorClient extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import scala.concurrent.ExecutionContext.Implicits.global
  import com.typesafe.config.ConfigFactory
  import akka.actor.ActorSystem
  
  val config = ConfigFactory.load() // Loads resources/application.conf
  implicit val as = ActorSystem("GreetingClientSys",
                          config = Some(config.getConfig("GreetingClientSys")),
                          defaultExecutionContext = Some(global))
  
  ActorChannel.setDefaultEC(global)
  ActorChannel.setDefaultAS(as)
  
  implicit val timeout = 10.seconds
  
  val serverPath = "akka.tcp://GreetingServerSys@127.0.0.1:31337/user/start"
  println(f"[*] Connecting to ${serverPath}...")
  val c = ActorOut[Start](serverPath)
  Client1(c)
  
  as.terminate()
}
