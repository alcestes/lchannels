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
package lchannels.examples.calc

import scala.concurrent.{ExecutionContext}
import scala.concurrent.duration.Duration

import lchannels._

/////////////////////////////////////////////////////////////////////////////
// Session type:
// ?Welcome(Str) . rec X . (+) { !Negate(Int) . ?Answer(Int) . X ,
//                               !Add(Int) . !Add2(Int) . ?Answer(Int) . X ,
//                               !Quit() }
/////////////////////////////////////////////////////////////////////////////
sealed case class Welcome(message: String)(val cont: Out[Choice])

sealed abstract class Choice
case class Negate(value: Integer)(val cont: Out[Answer]) extends Choice
case class Add(value1: Integer)(val cont: In[Add2])      extends Choice
case class Quit()                                        extends Choice

sealed case class Add2(value2: Integer)(val cont: Out[Answer])

sealed case class Answer(value: Integer)(val cont: Out[Choice])
/////////////////////////////////////////////////////////////////////////////

import lchannels.{StreamOut, StreamManager}
import java.io.{
  BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter,
  InputStream, OutputStream
}

class CalcStreamManager(in: InputStream, out: OutputStream)
                       (implicit ec: ExecutionContext)
    extends StreamManager(in, out) {
  private val inb = new BufferedReader(new InputStreamReader(in))
  private val welcomeR = """WELCOME (.+)""".r
  private val answerR = """ANSWER (-?\d+)""".r
  
  override def destreamer() =  inb.readLine() match {
    case welcomeR(msg) => Welcome(msg)(StreamOut[Choice](this))
    case answerR(n) => Answer(n.toInt)(StreamOut[Choice](this))
    case unknown => {
      close()
      throw new java.net.ProtocolException(f"Unknown message: '${unknown}'")
    }
  }
  
  private val outb = new BufferedWriter(new OutputStreamWriter(out))
  
  override def streamer(x: Any) = x match {
    case Negate(n) => outb.write(f"NEGATE ${n}\n"); outb.flush()
    case Add(n)    => outb.write(f"ADD ${n}\n"); outb.flush()
    case Add2(n)   => outb.write(f"ADD2 ${n}\n"); outb.flush()
    case Quit()    => outb.write("QUIT\n"); outb.flush(); close()
  }
}

class CalcSocketManager(socket: java.net.Socket)
    extends SocketManager(socket) {
  private val inb = new BufferedReader(new InputStreamReader(in))
  private val welcomeR = """WELCOME (.+)""".r
  private val answerR = """ANSWER (-?\d+)""".r
  
  override def destreamer() =  inb.readLine() match {
    case welcomeR(msg) => Welcome(msg)(SocketOut[Choice](this))
    case answerR(n) => Answer(n.toInt)(SocketOut[Choice](this))
    case unknown => {
      close()
      throw new java.net.ProtocolException(f"Unknown message: '${unknown}'")
    }
  }
  
  private val outb = new BufferedWriter(new OutputStreamWriter(out))
  
  override def streamer(x: Any) = x match {
    case Negate(n) => outb.write(f"NEGATE ${n}\n"); outb.flush()
    case Add(n)    => outb.write(f"ADD ${n}\n"); outb.flush()
    case Add2(n)   => outb.write(f"ADD2 ${n}\n"); outb.flush()
    case Quit()    => outb.write("QUIT\n"); outb.flush(); close()
  }
}

object Server {
  def apply(c: Out[Welcome])
           (implicit timeout: Duration): Unit = {
    println(f"[S] Sending welcome to ${c}...")
    val c2 = c !! Welcome("Welcome to SessionCalc 0.1")_
    subHandler(c2)

    def subHandler(c: In[Choice]) {
      println("[S] Now waiting for a choice... ")
      c ? {
        case Quit() => {
          println(f"[S] Got Quit(), finishing")
        }
        case m @ Negate(value) => {
          println(f"[S] Got 'Negate(${value})', answering ${-value}")
          val c2 = m.cont !! Answer(-value)_
          println("[S] Performing recursive call...")
          subHandler(c2)
        }
        case m @ Add(val1) => {
          println(f"[S] Got Add(${val1}), waiting for 2nd value...")
          m.cont.receive match {
            case m @ Add2(val2) => {
              println(f"[S] Got Add2(${val2}), answering ${val1 + val2}...")
              val c3 = m.cont !! Answer(val1 + val2)_
              println("[S] Performing recursive call...")
              subHandler(c3)
            }
          }
        }
      }
    }
  }
}

object Client {
  def apply(c: In[Welcome])
           (implicit timeout: Duration): Unit = {
    val welcome = c.receive
    println(f"[C] Got '${welcome.message}'")
    
    println(f"[C] Sending Negate(42)...")
    val ans = welcome.cont !! Negate(42)_
    println("[C] ...done.  Now waiting for answer...")
    val neg = ans.receive
    println(f"[C] ...done: got ${neg.value}")
  
    println("[C] Now trying to add 7 and 5...")
  
    val ans2 = neg.cont !! Add(7)_ !! Add2(5)_
    println("[C] ...done.  Now waiting for answer...")
    val sum = ans2.receive
    println(f"[C] ...done: got ${sum.value}")
  
    println("[C] Now quitting")
    sum.cont ! Quit()
  }
}

object Local extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import lchannels.LocalChannel.parallel
  import scala.concurrent.Await
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._
  
  implicit val timeout = 5.seconds
  
  println("[*] Spawning local server and client...")
  val (s, c) = parallel[Welcome, Unit, Unit](
    Client(_), Server(_)
  )

  Await.result(s, 10.seconds) // Wait for server termination
}

object Queue extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import lchannels.QueueChannel.parallel
  import scala.concurrent.Await
  import scala.concurrent.ExecutionContext.Implicits.global
  import scala.concurrent.duration._
  
  implicit val timeout = 5.seconds
  
  println("[*] Spawning local server and client (using queue-based channels)...")
  val (s, c) = parallel[Welcome, Unit, Unit](
    Client(_), Server(_)
  )

  Await.result(s, 10.seconds) // Wait for server termination
}
