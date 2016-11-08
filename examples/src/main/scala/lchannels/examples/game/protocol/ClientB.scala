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

/** Multiparty protocol classes for game player B.
 *  The classes in this package have been automatically generated from the
 *  multiparty game protocol:
 *  https://github.com/alcestes/scribble-java/blob/linear-channels/modules/linmp-scala/src/test/scrib/Game3.scr
 *  
 * @author Alceste Scalas <alceste.scalas@imperial.ac.uk> */
package lchannels.examples.game.protocol.b

import lchannels._
import lchannels.examples.game.protocol.binary

// Input message types for multiparty sessions
case class PlayB(p: MPInfoBC)
case class InfoAB(p: String, cont: MPMov1ABOrMov2AB)
sealed abstract class MsgMPMov1ABOrMov2AB
case class Mov1AB(p: Int, cont: MPMov1BC) extends MsgMPMov1ABOrMov2AB
case class Mov2AB(p: Boolean, cont: MPMov2BC) extends MsgMPMov1ABOrMov2AB

// Output message types for multiparty sessions
case class InfoBC(p: String)
case class Mov1BC(p: Int)
case class Mov2BC(p: Boolean)

// Multiparty session classes
case class MPPlayB(q: In[binary.PlayB]) {
  def receive() = {
    q.receive() match {
      case m @ binary.PlayB(p) => {
        PlayB(p)
      }
    }
  }
}
case class MPInfoBC(a: In[binary.InfoAB], c: Out[binary.InfoBC]) {
  def send(v: InfoBC) = {
    val cnt = c !! binary.InfoBC(v.p)_
    MPInfoAB(a, cnt)
  }
}
case class MPInfoAB(a: In[binary.InfoAB], c: Out[binary.Mov1BCOrMov2BC]) {
  def receive() = {
    a.receive() match {
      case m @ binary.InfoAB(p) => {
        InfoAB(p, MPMov1ABOrMov2AB(m.cont, c))
      }
    }
  }
}
case class MPMov1ABOrMov2AB(a: In[binary.Mov1ABOrMov2AB], c: Out[binary.Mov1BCOrMov2BC]) {
  def receive() = {
    a.receive() match {
      case m @ binary.Mov1AB(p) => {
        Mov1AB(p, MPMov1BC(m.cont, c))
      }
      case m @ binary.Mov2AB(p) => {
        Mov2AB(p, MPMov2BC(m.cont, c))
      }
    }
  }
}
case class MPMov1BC(a: In[binary.Mov1ABOrMov2AB], c: Out[binary.Mov1BCOrMov2BC]) {
  def send(v: Mov1BC) = {
    val cnt = c !! binary.Mov1BC(v.p)_
    MPMov1ABOrMov2AB(a, cnt)
  }
}
case class MPMov2BC(a: In[binary.Mov1ABOrMov2AB], c: Out[binary.Mov1BCOrMov2BC]) {
  def send(v: Mov2BC) = {
    val cnt = c !! binary.Mov2BC(v.p)_
    MPMov1ABOrMov2AB(a, cnt)
  }
}
