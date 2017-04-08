// lchannels - session programming in Scala
// Copyright (c) 2017, Alceste Scalas and Imperial College London
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
package lchannels.examples.threebuyer.carol

import lchannels._
import lchannels.examples.threebuyer.protocol.binary
import lchannels.examples.threebuyer.protocol.bob
import lchannels.examples.threebuyer.protocol.carol._

import scala.concurrent.duration._

import com.typesafe.scalalogging.StrictLogging
  
class Carol(bobConnector: () => In[binary.Contrib])
           (implicit timeout: Duration)
    extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(msg)
  private def logDebug(msg: String) = logger.debug(msg)
  private def logInfo(msg: String) = logger.info(msg)
  private def logWarn(msg: String) = logger.warn(msg)
  private def logError(msg: String) = logger.error(msg)

  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def join() = thread.join()
  def interrupt() = thread.interrupt()
  
  override def run(): Unit = {
    logInfo("Started.  Ready to connect with Bob...")
    val bobc = try {
      // Wrap the binary channel with bob in a session object
      MPContrib(bobConnector())
    } catch {
      case e: InterruptedException => {
        // We were interrupted without being involved in the interaction
        logInfo("Interrupted.  Terminating.")
        return
      }
    }
    
    logInfo("Bob connected.  Waiting for his contribution request...")
    val contrib = bobc.receive
    logInfo(f"Bob is asking for: ${contrib.p}") 
    
    logInfo("Receiving delegated session with Seller and Alice")
    val deleg = contrib.cont.receive
    
    val budget = 100
    logInfo(f"My budget is: ${budget}")
    if (contrib.p > budget) {
      logInfo(f"Budget is not sufficient: declining")
      deleg.cont.send(QuitC(()))
      deleg.p.send(bob.QuitA(())).send(bob.QuitS(()))
    } else {
      logInfo(f"Budget OK: accepting, sending address, waiting delivery date")
      deleg.cont.send(OkC(()))
      val delivery = deleg.p.send(bob.OkA(()))
        .send(bob.OkS(()))
        .send(bob.Address("Carol Jones, 17 Cherry Tree Lane, London, UK"))
        .receive
      logInfo(f"Got delivery date: ${delivery.p}")
    }
    
    logInfo("Terminating.")
  }
}

object Actor extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global
  import com.typesafe.config.ConfigFactory
  import akka.actor.ActorSystem
  
  val config = ConfigFactory.load() // Loads resources/application.conf
  implicit val as = ActorSystem("ThreeBuyerCarolSys",
                          config = Some(config.getConfig("ThreeBuyerCarolSys")),
                          defaultExecutionContext = Some(global))
  
  ActorChannel.setDefaultEC(global)
  ActorChannel.setDefaultAS(as)
  
  implicit val timeout = 120.seconds
  
  // We give a human-readable name to the connection endpoints
  val (bi, bo) = ActorChannel.factory[binary.actor.ConnectCarol]("bob");
  println(f"[*] Waiting Bob's connections on: ${bo.path}")
  
  def connector() = {
    val conn = bi.receive
    println(f"[*] Bob connected")
    conn.cont
  }
  
  val alice = new Carol(connector)(30.seconds)
  
  alice.join()
  Thread.sleep(2000) // Just to deliver pending actor messages
  as.terminate()
}
