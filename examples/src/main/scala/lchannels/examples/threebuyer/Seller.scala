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
package lchannels.examples.threebuyer.seller

import lchannels._
import lchannels.examples.threebuyer.protocol.binary
import lchannels.examples.threebuyer.protocol.alice
import lchannels.examples.threebuyer.protocol.bob
import lchannels.examples.threebuyer.protocol.seller._

import scala.concurrent.duration._
import java.time.ZonedDateTime

import com.typesafe.scalalogging.StrictLogging
  
class Seller(ca: Out[binary.PlayAlice],
             cb: Out[binary.PlayBob])
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
    logInfo("Starting.  Creating binary channels for multiparty session...")

    // We need to create a pair of channel endpoints for each pair of
    // connected roles in the multiparty session.  We use the create()
    // method because:
    //
    //   (1) ca.create() ensures that the returned channel endpoints use the
    //       same message transport of ca (and similary for cb);
    //
    //   (2) we need *both* input/output endpoints, so that later we can
    //       instantiate alice.MPTitle and bob.MPQuoteB, and send
    //       them to the session participants;
    //
    //   (3) we do not *want* to use e.g. ca.!!(...), because it would only
    //       return *one* endpoint, and later we would not be able to
    //       instantiate alice.MPTitle and bob.MPQuoteB;
    //
    //   (3) anyway, we *cannot* meaningfully use e.g. ca.!!(...): since
    //       the carried type has no continuation, it is quite difficult to
    //       come up with a "reasonable" value for "..." that is accepted by
    //       the Scala compiler.  E.g., the following is accepted, but
    //       is visibly bogus:   ca !! ((_:In[Int]) => null)
    val (abi, abo) = ca.create[binary.ShareA] // Used between Alice and Bob
    val (sai, sao) = ca.create[binary.Title] // Used between Seller and Alice
    val (sbi, sbo) = cb.create[binary.QuoteB] // Used between Seller and Bob
    
    
    // We now instantiate multiparty session objects (i.e., n-uples of
    // binary linear channels), and send them to our clients via channels
    // ca, cb, cc.
    //
    // Note that the types of the arguments of a.MPInfoCA, bMPInfoBC and
    // c.MPInfoCA ensure that the channel endpoints above are used in the
    // correct way: if the wrong channel is used somewhere, the resulting
    // code does not compile
    logInfo("...and sending multiparty objects to clients.")
    ca ! binary.PlayAlice(alice.MPTitle(abo, sao))
    cb ! binary.PlayBob(bob.MPQuoteB(abi, sbi))
    
    // Wrap binary channels into a multiparty session object
    val s = MPTitle(sai, sbo)
    sell(s)
    
    logInfo("Terminating.")
  }
  
  private def sell(s: MPTitle) = {
    logInfo("Waiting for order...")
    val order = s.receive
    logInfo(f"Received order: '${order.p}'")
    val quote = order.p match {
      case "Alice in Wonderland" => 10
      case "War and Peace" => 100
      case _ => 1000 // We can find any book, but it will be expensive...
    }
    logInfo(f"Sending quote: ${quote} --- then waiting for answer...")
    order.cont.send(QuoteA(quote)).send(QuoteB(quote)).receive match {
      case OkS((), cont) => {
        logInfo("Quote accepted, waiting for address")
        val address = cont.receive
        logInfo(f"Got delivery address: '${address.p}'")
        
        val deliveryDate = ZonedDateTime.now().plusDays(7)
        logInfo(f"Sending delivery date (7 days from now): ${deliveryDate}")
        address.cont.send(Deliver(deliveryDate))
      }
      case QuitS(()) => {
        logInfo("Quote rejected")
      }
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
  
  val config = ConfigFactory.load() // Loads resources/application.conf
  implicit val as = ActorSystem("ThreeBuyerSellerSys",
                          config = Some(config.getConfig("ThreeBuyerSellerSys")),
                          defaultExecutionContext = Some(global))
  
  ActorChannel.setDefaultEC(global)
  ActorChannel.setDefaultAS(as)
  
  implicit val timeout = 120.seconds
  
  // We give a human-readable name to the connection endpoints
  val (ai, ao) = ActorChannel.factory[binary.actor.ConnectAlice]("alice");
  val (bi, bo) = ActorChannel.factory[binary.actor.ConnectBob]("bob");
  println(f"[*] Waiting connections on: ${ao.path}, ${bo.path}")
  
  val ac = ai.receive
  println(f"[*] Alice connected")
  val bc = bi.receive
  println(f"[*] Bob connected")
  
  val seller = new Seller(ac.cont, bc.cont)(30.seconds)
  
  seller.join()
  Thread.sleep(2000) // Just to deliver pending actor messages
  println(f"[*] Quitting")
  as.terminate()
}
