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
/** Binary protocol classes for the multiparty game.
 *  The classes in this package have been automatically generated from the
 *  multiparty game protocol:
 *  https://github.com/alcestes/scribble-java/blob/linear-channels/modules/linmp-scala/src/test/scrib/Game3.scr
 *  
 * @author Alceste Scalas <alceste.scalas@imperial.ac.uk> */
package lchannels.examples.game.protocol.binary

import lchannels._
import lchannels.examples.game.protocol.a
import lchannels.examples.game.protocol.b
import lchannels.examples.game.protocol.c

case class InfoAB(p: String)(val cont: In[Mov1ABOrMov2AB])

case class PlayA(p: a.MPInfoCA)

sealed abstract class Mov1ABOrMov2AB
case class Mov1AB(p: Int)(val cont: In[Mov1ABOrMov2AB]) extends Mov1ABOrMov2AB
case class Mov2AB(p: Boolean)(val cont: In[Mov1ABOrMov2AB]) extends Mov1ABOrMov2AB
case class InfoCA(p: String)(val cont: In[Mov1CAOrMov2CA])

sealed abstract class Mov1CAOrMov2CA
case class Mov1CA(p: Int)(val cont: In[Mov1CAOrMov2CA]) extends Mov1CAOrMov2CA
case class Mov2CA(p: Boolean)(val cont: In[Mov1CAOrMov2CA]) extends Mov1CAOrMov2CA

case class PlayB(p: b.MPInfoBC)

case class InfoBC(p: String)(val cont: In[Mov1BCOrMov2BC])
sealed abstract class Mov1BCOrMov2BC
case class Mov1BC(p: Int)(val cont: In[Mov1BCOrMov2BC]) extends Mov1BCOrMov2BC
case class Mov2BC(p: Boolean)(val cont: In[Mov1BCOrMov2BC]) extends Mov1BCOrMov2BC

case class PlayC(p: c.MPInfoBC)
// No more protocol classes needed for player C: they are shared with A and B

/** Binary protocol classes for establishing a client/server connection
 *  (used e.g. in the actor-based demo)
 */
package object actor {
  case class ConnectA()(val cont: Out[PlayA])
  case class ConnectB()(val cont: Out[PlayB])
  case class ConnectC()(val cont: Out[PlayC])
}
