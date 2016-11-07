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
/** Multiparty protocol classes for game player C.
 *  The classes in this package have been automatically generated from the
 *  multiparty game protocol:
 *  https://github.com/alcestes/scribble-java/blob/linear-channels/modules/linmp-scala/src/test/scrib/Game3.scr
 *  
 * @author Alceste Scalas <alceste.scalas@imperial.ac.uk> */
package lchannels.examples.game.protocol.c

import lchannels._
import lchannels.examples.game.protocol.binary

// Input message types for multiparty sessions
case class PlayC(p: MPInfoBC)
case class InfoBC(p: String, cont: MPInfoCA)
sealed abstract class MsgMPMov1BCOrMov2BC
case class Mov1BC(p: Int, cont: MPMov1CAOrMov2CA) extends MsgMPMov1BCOrMov2BC
case class Mov2BC(p: Boolean, cont: MPMov1CAOrMov2CA) extends MsgMPMov1BCOrMov2BC

// Output message types for multiparty sessions
case class InfoCA(p: String)
case class Mov1CA(p: Int)
case class Mov2CA(p: Boolean)

// Multiparty session classes
case class MPPlayC(q: In[binary.PlayC]) {
  def receive() = {
    q.receive() match {
      case m @ binary.PlayC(p) => {
        PlayC(p)
      }
    }
  }
}
case class MPInfoBC(a: Out[binary.InfoCA], b: In[binary.InfoBC]) {
  def receive() = {
    b.receive() match {
      case m @ binary.InfoBC(p) => {
        InfoBC(p, MPInfoCA(a, m.cont))
      }
    }
  }
}
case class MPInfoCA(a: Out[binary.InfoCA], b: In[binary.Mov1BCOrMov2BC]) {
  def send(v: InfoCA) = {
    val cnt = a !! binary.InfoCA(v.p)_
    MPMov1BCOrMov2BC(cnt, b)
  }
}
case class MPMov1BCOrMov2BC(a: Out[binary.Mov1CAOrMov2CA], b: In[binary.Mov1BCOrMov2BC]) {
  def receive() = {
    b.receive() match {
      case m @ binary.Mov1BC(p) => {
        Mov1BC(p, MPMov1CAOrMov2CA(a, m.cont))
      }
      case m @ binary.Mov2BC(p) => {
        Mov2BC(p, MPMov1CAOrMov2CA(a, m.cont))
      }
    }
  }
}
case class MPMov1CAOrMov2CA(a: Out[binary.Mov1CAOrMov2CA], b: In[binary.Mov1BCOrMov2BC]) {
  def send(v: Mov1CA) = {
    val cnt = a !! binary.Mov1CA(v.p)_
    MPMov1BCOrMov2BC(cnt, b)
  }
  def send(v: Mov2CA) = {
    val cnt = a !! binary.Mov2CA(v.p)_
    MPMov1BCOrMov2BC(cnt, b)
  }
}
