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
package lchannels.examples.game.demo

import lchannels._
import lchannels.examples.game.protocol.binary

object Local extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import scala.concurrent.duration._
  
  implicit val timeout = 10.seconds
  
  // Client/server channels for players A, B, C
  val (ca, sa) = LocalChannel.factory[binary.PlayA]()
  val (cb, sb) = LocalChannel.factory[binary.PlayB]()
  val (cc, sc) = LocalChannel.factory[binary.PlayC]()
  
  val server = new lchannels.examples.game.server.Server(sa, sb, sc)
  val a = new lchannels.examples.game.a.Client("Alice", ca, 3.seconds)
  val b = new lchannels.examples.game.b.Client("Bob", cb, 1.second)
  val c = new lchannels.examples.game.c.Client("Carol", cc, 2.seconds)
}
