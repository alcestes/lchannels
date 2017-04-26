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
import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.scalalogging.StrictLogging

import lchannels._
import lchannels.util.Fifo

import lchannels.examples.sleepingbarber.customer.{
  WaitingRoom, Full, Seat, Ready
}

class Shop(nSeats: Int,
           bfactory: () => (In[Available], Out[Available]),
           cfactory: () => (In[WaitingRoom], Out[WaitingRoom]))
          (implicit timeout: Duration) extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"${msg}")
  private def logDebug(msg: String) = logger.debug(f"${msg}")
  private def logInfo(msg: String) = logger.info(f"${msg}")
  private def logWarn(msg: String) = logger.warn(f"${msg}")
  private def logError(msg: String) = logger.error(f"${msg}")
  
  var barber: Barber = null // Will be initialized in run()
  
  private val seats: Fifo[Out[Ready]] = Fifo()
  private val peopleWaiting = new AtomicInteger(0) // Keep in synch. with seats
  
  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t}
  def quit() = thread.interrupt()
  
  def enter(): In[WaitingRoom] = {
    logDebug("a customer is entering")
    val (in, out) = cfactory()
    val nPeople = peopleWaiting.getAndIncrement()
    if (nPeople >= nSeats) {
      logDebug(f"waiting room is full: ${nPeople+1} customers, ${nSeats} seats")
      peopleWaiting.getAndDecrement()
      out ! Full()
    } else {
      logDebug(f"a seat is available (${nPeople} customer(s) sitting)")
      val r = out !! Seat()_
      logDebug(f"seat taken (${nPeople+1} customer(s) sitting")
      seats.write(r)
    }
    in
  }
  
  override def run(): Unit = {
    logDebug("started")
    assert(nSeats > 0)
    
    logDebug("starting barber")
    val (barberIn, barberOut) = bfactory()
    barber = new Barber(barberOut)
    
    logDebug("entering main loop")
    
    loop(barberIn)
    
    logDebug("terminating barber")
    barber.quit()
    
    logInfo("quitting")
  }
  
  private def loop(barber: In[Available]): Unit = try {
    logDebug("waiting for barber availability...")
    barber ? { avail =>
      logDebug("waiting for customer")
      val cust = seats.read
      peopleWaiting.getAndDecrement()
      logDebug("notifying the customer that the barber is ready")
      val custd = cust !! Ready()_
      logDebug("forwarding customer to barber, and looping")
      loop(avail.cont !! Serve(custd)_)
    }
  } catch {
    case _: InterruptedException => logInfo("interrupted, leaving main loop")
  }
}

object Shop {
  def apply(maxSeats: Int)(implicit d: Duration) = {
    new Shop(maxSeats, () => LocalChannel.factory(), LocalChannel.factory)
  }
}
