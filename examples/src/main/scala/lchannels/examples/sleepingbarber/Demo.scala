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
package lchannels.examples.sleepingbarber.demo

object Local extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import scala.concurrent.duration._
  
  implicit val timeout = 10.seconds
  
  val customers = List(
    "Alice", "Bob", "Carol", "Dave", "Erin", "Frank", "George", "Horace",
    "Isabelle", "John", "Karen", "Louis", "Mark", "Nick", "Oliver", "Paul",
    "Quentin", "Rebecca", "Sarah", "Thomas", "Ursula", "Valeri", "William",
    "Xavier", "Yasemin", "Zacharias"
  )
  
  val maxSeats = 4
  
  val shop = lchannels.examples.sleepingbarber.barbershop.Shop(maxSeats)
  
  val customerObjs = for (name <- customers)
    yield new lchannels.examples.sleepingbarber.customer.Customer(name, shop)
  
  for (c <- customerObjs) c.join()
  println("*** All customers served - shutting down barbershop")
  
  shop.quit()
}
