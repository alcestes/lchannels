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
    val (abi, abo) = ca.create[binary.InfoAB]
    val (bci, bco) = cb.create[binary.InfoBC]
    val (cai, cao) = cc.create[binary.InfoCA]
    
    logInfo("...and sending multiparty objects to clients.")
    ca ! binary.PlayA(a.MPInfoCA(abo, cai))
    cb ! binary.PlayB(b.MPInfoBC(abi, bco))
    cc ! binary.PlayC(c.MPInfoBC(cao, bci))
    
    logInfo("Quitting.")
  }
}
