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
package lchannels.examples.game.b
  
import lchannels._
import lchannels.examples.game.protocol.binary
import lchannels.examples.game.protocol.b._

import scala.concurrent.duration._

import com.typesafe.scalalogging.StrictLogging
  
class Client(name: String, s: In[binary.PlayB], wait: Duration)
            (implicit timeout: Duration)
    extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"${name}: ${msg}")
  private def logDebug(msg: String) = logger.debug(f"${name}: ${msg}")
  private def logInfo(msg: String) = logger.info(f"${name}: ${msg}")
  private def logWarn(msg: String) = logger.warn(f"${name}: ${msg}")
  private def logError(msg: String) = logger.error(f"${name}: ${msg}")

  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def join() = thread.join()
  
  override def run() = {
    val c = MPPlayB(s) // Wrap the channel in a multiparty session obj
    
    logInfo("Started.  Waiting for multiparty session...")
    val game = c.receive().p
    logInfo("...done.  Sending name to C, and waiting for A's info...")
    val info = game.send(InfoBC(name)).receive()
    logInfo(f"...got InfoCA(${info.p}).  Starting game loop.")
    loop(info.cont)
  }
  
  @scala.annotation.tailrec
  private def loop(g: MPMov1ABOrMov2AB): Unit = {
    logInfo(f"Delay: ${wait}")
    Thread.sleep(wait.toMillis)
    logInfo("Waiting for A's move...")
    g.receive() match {
      case Mov1AB(p, cont) => {
        logInfo(f"Got Mov1AB(${p}), sending Mov1BC(${p} and looping")
        val g2 = cont.send(Mov1BC(p))
        loop(g2)
      }
      case Mov2AB(p, cont) => {
        logInfo(f"Got Mov2AB(${p}), sending Mov2BC(${p} and looping")
        val g2 = cont.send(Mov2BC(p))
        loop(g2)
      } 
    }
  }
}
