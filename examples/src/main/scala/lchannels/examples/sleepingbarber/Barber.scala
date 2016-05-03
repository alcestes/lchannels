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
package lchannels.examples.sleepingbarber.barbershop

import scala.concurrent.duration.Duration

import com.typesafe.scalalogging.StrictLogging

import lchannels._

import lchannels.examples.sleepingbarber.customer.{
  Description => CustDescription, Cut => CustCut
}

//////////////////////////////////////////////////////////////////////////////
// Session type:
// S_barber = rec X.!Available.?Customer(S_cut).X  (S_cut: see Customer.scala)
//////////////////////////////////////////////////////////////////////////////
protected[barbershop] case class Available()(val cont: Out[Serve])

protected[barbershop] case class Serve(chan: In[CustDescription])
                                      (val cont: Out[Available])
//////////////////////////////////////////////////////////////////////////////

class Barber(shop: Out[Available])
            (implicit d: Duration) extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"${msg}")
  private def logDebug(msg: String) = logger.debug(f"${msg}")
  private def logInfo(msg: String) = logger.info(f"${msg}")
  private def logWarn(msg: String) = logger.warn(f"${msg}")
  private def logError(msg: String) = logger.error(f"${msg}")
  
  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def quit() = thread.interrupt()
  
  override def run(): Unit = {
    logInfo("started, entering main loop")
    loop(shop)
    logInfo("quitting")
  }
  
  // NOTE: loop() is not tail-recursive --- left as it is for simplicity
  private def loop(shop: Out[Available]): Unit= try {
    logInfo("signaling availability, and waiting for customer")
    (shop !! Available()_) ? { customer =>
      logInfo("got customer, waiting for haircut description")
      customer.chan ? { descr =>
        logInfo("performing haircut, and waiting for payment...")
        (descr.cont !! CustCut()_) ? { pay =>
          logInfo("payment received, customer dismissed")
        }
      }
      loop(customer.cont)
    }
  } catch {
    case _: InterruptedException => logInfo("interrupted, leaving main loop")
  }
}
