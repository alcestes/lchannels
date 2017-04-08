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
package lchannels.examples.threebuyer.bob

import lchannels._
import lchannels.examples.threebuyer.protocol.binary
import lchannels.examples.threebuyer.protocol.bob._

import scala.concurrent.duration._

import com.typesafe.scalalogging.StrictLogging
  
class Bob(s: In[binary.PlayBob],
          carolConnector: (String => Unit) => Out[binary.Contrib])
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
  
  override def run() = {
    val c = MPPlayBob(s) // Wrap the channel in a multiparty session obj
    
    logInfo("Started.  Waiting for multiparty session...")
    val c2 = c.receive.p
    
    logInfo("Waiting for book quote...")
    val quote = c2.receive
    logInfo(f"Got quote: ${quote.p}.  Waiting to know Alice's share...")
    val contrib = quote.cont.receive
    logInfo(f"Got Alice's share: ${contrib.p}")
    
    val myShare = quote.p - contrib.p
    assert(myShare >= 0)
    
    val budget = 25
    
    logInfo(f"My share is: ${myShare}; my maximum budget is: ${budget}")
    if (myShare > budget) {
      val needed = myShare - budget
      logInfo(f"I need ${needed} more.  Involving Carol...")
      delegateCarol(contrib.cont, needed)
    } else {
      logInfo("Accepting proposal, sending address, waiting delivery date")
      val delivery = contrib.cont.send(OkA(()))
        .send(OkS(()))
        .send(Address("Bob Smith, 221B Baker Street, London, UK"))
        .receive
      logInfo(f"Got delivery date: ${delivery.p}")
    }
    
    logInfo("Terminating.")
  }
  
  private def delegateCarol(s: MPOkAOrQuitA, needed: Int) = {
    // Wrap Carol's channel in a session object
    val carol = MPContrib(carolConnector(logInfo))
    
    logInfo(f"Telling Carol to contribute ${needed}; delegating session with Alice and Seller")
    val ccont = carol.send(Contrib(needed)).send(Delegate(s))
    logInfo(f"Waiting for Carol's decision...")
    ccont.receive match {
      case OkC(()) => logInfo("Carol accepted")
      case QuitC(()) => logInfo("Carol declined")
    }
  }
}

object Actor extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import scala.concurrent.duration._
  import scala.concurrent.ExecutionContext.Implicits.global
  import com.typesafe.config.ConfigFactory
  import akka.actor.ActorSystem
  
  import binary.actor.{ConnectBob, ConnectCarol}
  
  val config = ConfigFactory.load() // Loads resources/application.conf
  implicit val as = ActorSystem("ThreeBuyerBobSys",
                          config = Some(config.getConfig("ThreeBuyerBobSys")),
                          defaultExecutionContext = Some(global))
  
  ActorChannel.setDefaultEC(global)
  ActorChannel.setDefaultAS(as)
  
  implicit val timeout = 60.seconds
  
  val sellerPath = "akka.tcp://ThreeBuyerSellerSys@127.0.0.1:31350/user/bob"
  println(f"[*] Connecting to ${sellerPath}...")
  val c: Out[ConnectBob] = ActorOut[ConnectBob](sellerPath)
  val c2 = c !! ConnectBob()_
  
  def connector(logger: String => Unit) = {
    // Path where Carol is waiting for Bob's connection
    val carolPath = "akka.tcp://ThreeBuyerCarolSys@127.0.0.1:31353/user/bob"
    logger(f"Connecting to ${carolPath}...")
    val c: Out[ConnectCarol] = ActorOut[ConnectCarol](carolPath)
    c !! ConnectCarol()_
  }
  
  val bob = new Bob(c2, connector)(30.seconds)
  
  bob.join()
  Thread.sleep(2000) // Just to deliver pending actor messages
  as.terminate()
}
