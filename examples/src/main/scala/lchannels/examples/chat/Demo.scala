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
package lchannels.examples.chat.demo

import scala.concurrent.duration.Duration

protected case class ClientSpec(username: String, password: String,
                                msgDelay: Duration, msgCount: Int)

object Local extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import scala.concurrent.duration._
  import lchannels.examples.chat.server.ChatServer
  import scala.concurrent.ExecutionContext.Implicits.global
  
  val timeout = 30.seconds
  
  val clientSpecs = List(
    ClientSpec("alice", "password", 1.seconds, 10),
    ClientSpec("bob", "password", 2.seconds, 8),
    ClientSpec("carol", "password", 3.seconds, 6)
  )
  
  val frontend = ChatServer()(global, timeout)
  
  println("***Spawning clients...")
  val clients = for (cs <- clientSpecs) yield {
    new Client(frontend.connect(), cs)(timeout)
  }
  
  println("*** Waiting for clients to terminate...")
  for (c <- clients) {
    c.join();
    println(f"    **** ${c.spec.username} has terminated")
  }
  println("*** All clients have terminated.  Now closing the frontend...")
  frontend.quit()
  println("*** ...done.")
}
