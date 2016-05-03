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
package lchannels.examples.sleepingbarber.customer

import scala.concurrent.duration.Duration

import com.typesafe.scalalogging.StrictLogging

import lchannels._

import lchannels.examples.sleepingbarber.barbershop

//////////////////////////////////////////////////////////////////////////////
// Session type:
// S_cust = ?Full.end & ?Seat.?Ready.S_cut
//                      where  S_cut  = !Description(String).?Haircut.!Pay.end
//////////////////////////////////////////////////////////////////////////////
sealed abstract class WaitingRoom
case class Full()                      extends WaitingRoom
case class Seat()(val cont: In[Ready]) extends WaitingRoom

case class Ready()(val cont: Out[Description])

case class Description(text: String)(val cont: Out[Cut])

case class Cut()(val cont: Out[Pay])

case class Pay(amount: Int)
////////////////////////////////////////////////////////////////////////////

class Customer(name: String, shop: barbershop.Shop)
              (implicit d: Duration) extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"${name}: ${msg}")
  private def logDebug(msg: String) = logger.debug(f"${name}: ${msg}")
  private def logInfo(msg: String) = logger.info(f"${name}: ${msg}")
  private def logWarn(msg: String) = logger.warn(f"${name}: ${msg}")
  private def logError(msg: String) = logger.error(f"${name}: ${msg}")
  
  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def join() = thread.join()
  
  override def run(): Unit = {
    logInfo("started, entering in shop")
    loop()
    logInfo("leaving the shop")
  }
  
  private def loop(): Unit = {
    shop.enter() ? {
      case Full() => {
        logInfo("waiting is room full, will retry within 3 seconds")
        Thread.sleep(new scala.util.Random().nextInt(30) * 100)
        loop()
      }
      case m @ Seat() => {
        logInfo("got a seat, waiting...")
        m.cont ? { ready =>
          logInfo("barber is ready, describing cut")
          (ready.cont !! Description("Fancy hairdo")_) ? { cut =>
            logInfo("cut done, paying")
            cut.cont ! Pay(42)
          }
        }
      }
    }
  }
}
