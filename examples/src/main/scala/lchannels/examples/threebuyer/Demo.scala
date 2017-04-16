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
package lchannels.examples.threebuyer.demo

import lchannels._
import lchannels.examples.threebuyer.protocol.binary.{PlayAlice, PlayBob, Contrib}
import lchannels.examples.threebuyer.alice.Alice
import lchannels.examples.threebuyer.bob.Bob
import lchannels.examples.threebuyer.carol.Carol
import lchannels.examples.threebuyer.seller.Seller

import scala.concurrent.{Await, duration, Promise}

object Local extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  Demo.start(LocalChannel.factory[PlayAlice],
             LocalChannel.factory[PlayBob],
             LocalChannel.factory[Contrib])
}

object Demo {
  def start(afactory: () => (In[PlayAlice], Out[PlayAlice]),
            bfactory: () => (In[PlayBob], Out[PlayBob]),
            cfactory: () => (In[Contrib], Out[Contrib])) = {
    import scala.concurrent.duration._
    implicit val timeout = 60.seconds
    
    // Client/server channels towards Alice and Bob
    val (ca, sa) = afactory()
    val (cb, sb) = bfactory()
    
    // Promise/future pair used to "connect" Bob and Carol
    val bcp = Promise[In[Contrib]]
    val bcf = bcp.future

    // Here, the connector to Bob (used by Carol) will not return
    // until the connector to Carol (used by Bob) is invoked
    // (but this will only happen if Bob actually decides to involve Carol)
    def carolConnector(_logger: String => Unit) = {
      val (bci, bco) = cfactory() // Channel between Bob and Carol

      bcp.success(bci) // Complete the Promise with one channel endpoint...
      bco // ...and return the other endpoint
    }
    def bobConnector() = Await.result(bcf, duration.Duration.Inf)
    
    val seller = new Seller(sa, sb)
    val alice = new Alice(ca)
    val bob = new Bob(cb, carolConnector)
    val carol = new Carol(bobConnector)
    
    // Wait for Alice, Bob, Seller to quit (NOTE: Carol might not be involved)
    alice.join()
    bob.join()
    seller.join()
    
    Thread.sleep(1000) // Just to let Carol terminate, if involved 
    carol.interrupt()
  }
}
