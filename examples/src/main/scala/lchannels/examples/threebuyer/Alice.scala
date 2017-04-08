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
package lchannels.examples.threebuyer.alice

import lchannels._
import lchannels.examples.threebuyer.protocol.binary
import lchannels.examples.threebuyer.protocol.alice._

import scala.concurrent.duration._

import com.typesafe.scalalogging.StrictLogging
  
class Alice(s: In[binary.PlayAlice])
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
    val c = MPPlayAlice(s) // Wrap the channel in a multiparty session obj
    
    logInfo("Started.  Waiting for multiparty session...")
    val c2 = c.receive.p
    
    val choices = Map(
      1 -> ("Alice in Wonderland", "Lewis Carroll"),
      2 -> ("War and Peace", "Lev Nikolajevič Tolstoj"),
      3 -> ("The Art of Computer Programming", "Donald Knuth")
    )
    val title = chooseTitle(choices)
    logInfo(f"Sending title: '${title}' --- and waiting for quote...")
    val quote = c2.send(Title(title)).receive
    logInfo(f"Got quote: ${quote.p}")
    val halfQuote = quote.p / 2
    
    logInfo(f"Telling Bob that we contribute ${halfQuote}; waiting answer...")
    quote.cont.send(ShareA(halfQuote)).receive match {
      case OkA(()) => logInfo("Shared purchase accepted")
      case QuitA(()) => logInfo("Shared purchase rejected")
    }
    
    logInfo("Terminating.")
  }
    
  @scala.annotation.tailrec
  private def chooseTitle(choices: Map[Int, (String, String)]): String = {
    
    println("Which book should Alice choose?  Please select:")
    for (k <- choices.keys) {
      val c = choices.get(k).get
      println(f"    ${k} - ${c._1} (${c._2})")
    }
    print("> ")
    val choice = scala.io.StdIn.readInt
    if (choice >= 1 && choice <= 3) {
      choices.get(choice).get._1
    } else {
      println("Invalid choice, please retry")
      chooseTitle(choices)
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
  
  import binary.actor.ConnectAlice
  
  val config = ConfigFactory.load() // Loads resources/application.conf
  implicit val as = ActorSystem("ThreeBuyerAliceSys",
                          config = Some(config.getConfig("ThreeBuyerAliceSys")),
                          defaultExecutionContext = Some(global))
  
  ActorChannel.setDefaultEC(global)
  ActorChannel.setDefaultAS(as)
  
  implicit val timeout = 60.seconds
  
  val sellerPath = "akka.tcp://ThreeBuyerSellerSys@127.0.0.1:31350/user/alice"
  println(f"[*] Connecting to ${sellerPath}...")
  val c: Out[ConnectAlice] = ActorOut[ConnectAlice](sellerPath)
  val c2 = c !! ConnectAlice()_
  
  val alice = new Alice(c2)(30.seconds)
  
  alice.join()
  Thread.sleep(2000) // Just to deliver pending actor messages
  as.terminate()
}
