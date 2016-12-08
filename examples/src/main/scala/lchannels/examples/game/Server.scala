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
package lchannels.examples.game.server

import lchannels._
import lchannels.examples.game.protocol.binary
import lchannels.examples.game.protocol.a
import lchannels.examples.game.protocol.b
import lchannels.examples.game.protocol.c

import scala.concurrent.duration._

import com.typesafe.scalalogging.StrictLogging
  
class Server(ca: Out[binary.PlayA],
             cb: Out[binary.PlayB],
             cc: Out[binary.PlayC])
            (implicit timeout: Duration)
    extends Runnable with StrictLogging {
    private def logTrace(msg: String) = logger.trace(f"Server: ${msg}")
  private def logDebug(msg: String) = logger.debug(f"Server: ${msg}")
  private def logInfo(msg: String) = logger.info(f"Server: ${msg}")
  private def logWarn(msg: String) = logger.warn(f"Server: ${msg}")
  private def logError(msg: String) = logger.error(f"Server: ${msg}")

  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def join() = thread.join()
  
  override def run() = {
    logInfo("Starting.  Creating binary channels for multiparty game...")

    // We need to create a pair of channel endpoints for each pair of
    // connected roles in the multiparty game session.  We use the create()
    // method because:
    //
    //   (1) ca.create() ensures that the returned channel endpoints use the
    //       same message transport of ca (and similarly for cb and cc);
    //
    //   (2) we need *both* input/output endpoints, so that later we can
    //       instantiate a.MPInfoCA, b.MPInfoBC and c.MPInfoBC, and send
    //       them to the session participants;
    //
    //   (3) we do not *want* to use e.g. ca.!!(...), because it would only
    //       return *one* endpoint, and later we would not be able to
    //       instantiate MPInfoCA, b.MPInfoBC, c.MPInfoBC;
    //
    //   (3) anyway, we *cannot* meaningfully use e.g. ca.!!(...): since
    //       the carried type has no continuation, it is quite difficult to
    //       come up with a "reasonable" value for "..." that is accepted by
    //       the Scala compiler.  E.g., the following is accepted, but
    //       is visibly bogus:   ca !! ((_:In[Int]) => null)
    val (abi, abo) = ca.create[binary.InfoAB]
    val (bci, bco) = cb.create[binary.InfoBC]
    val (cai, cao) = cc.create[binary.InfoCA]
    
    // We now instantiate multiparty session objects (i.e., n-uples of
    // binary linear channels), and send them to our clients via channels
    // ca, cb, cc.
    //
    // Note that the types of the arguments of a.MPInfoCA, bMPInfoBC and
    // c.MPInfoCA ensure that the channel endpoints above are used in the
    // correct way: if the wrong channel is used somewhere, the resulting
    // code does not compile
    logInfo("...and sending multiparty objects to clients.")
    ca ! binary.PlayA(a.MPInfoCA(abo, cai))
    cb ! binary.PlayB(b.MPInfoBC(abi, bco))
    cc ! binary.PlayC(c.MPInfoBC(cao, bci))
    
    logInfo("Quitting.")
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
  implicit val as = ActorSystem("GameServerSys",
                          config = Some(config.getConfig("GameServerSys")),
                          defaultExecutionContext = Some(global))
  
  ActorChannel.setDefaultEC(global)
  ActorChannel.setDefaultAS(as)
  
  implicit val timeout = 120.seconds
  
  // We give a human-readable name to the connection endpoints
  val (ai, ao) = ActorChannel.factory[binary.actor.ConnectA]("a");
  val (bi, bo) = ActorChannel.factory[binary.actor.ConnectB]("b");
  val (ci, co) = ActorChannel.factory[binary.actor.ConnectC]("c");
  println(f"[*] Waiting connections on: ${ao.path}, ${bo.path}, ${co.path}")
  
  val ac = ai.receive
  println(f"[*] Player A connected")
  val bc = bi.receive
  println(f"[*] Player B connected")
  val cc = ci.receive
  println(f"[*] Player C connected.  Launching server thread...")
  
  val server = new Server(ac.cont, bc.cont, cc.cont)
  
  server.join()
  println(f"[*] Delaying termination to complete game delegation")
  Thread.sleep(10000)
  println(f"[*] Quitting")
  as.terminate()
}