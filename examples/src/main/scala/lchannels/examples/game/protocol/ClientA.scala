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

/** Multiparty protocol classes for game player A.
 *  The classes in this package have been automatically generated from the
 *  multiparty game protocol:
 *  https://github.com/alcestes/scribble-java/blob/linear-channels/modules/linmp-scala/src/test/scrib/Game3.scr
 *  
 * @author Alceste Scalas <alceste.scalas@imperial.ac.uk> */
package lchannels.examples.game.protocol.a

import scala.concurrent.duration.Duration
import lchannels._
import lchannels.examples.game.protocol.binary

// Input message types for multiparty sessions
case class PlayA(p: MPInfoCA)
case class InfoCA(p: String, cont: MPInfoAB)

sealed abstract class MsgMPMov1CAOrMov2CA
case class Mov1CA(p: Int, cont: MPMov1ABOrMov2AB) extends MsgMPMov1CAOrMov2CA
case class Mov2CA(p: Boolean, cont: MPMov1ABOrMov2AB) extends MsgMPMov1CAOrMov2CA

// Output message types for multiparty sessions
case class InfoAB(p: String)
case class Mov1AB(p: Int)
case class Mov2AB(p: Boolean)

// Multiparty session classes
case class MPPlayA(q: In[binary.PlayA]) {
	def receive(implicit timeout: Duration = Duration.Inf) = {
		q.receive(timeout) match {
		  case m @ binary.PlayA(p) => {
			  PlayA(p)
		  }
		}
	}
}

case class MPInfoCA(b: Out[binary.InfoAB], c: In[binary.InfoCA]) {
	def receive(implicit timeout: Duration = Duration.Inf) = {
		c.receive(timeout) match {
		  case m @ binary.InfoCA(p) => {
			  InfoCA(p, MPInfoAB(b, m.cont))
		  }
		}
	}
}

case class MPInfoAB(b: Out[binary.InfoAB], c: In[binary.Mov1CAOrMov2CA]) {
	def send(v: InfoAB) = {
		val cnt = b !! binary.InfoAB(v.p)_
		MPMov1ABOrMov2AB(cnt, c)
	}
}

case class MPMov1ABOrMov2AB(b: Out[binary.Mov1ABOrMov2AB], c: In[binary.Mov1CAOrMov2CA]) {
	def send(v: Mov1AB) = {
		val cnt = b !! binary.Mov1AB(v.p)_
		MPMov1CAOrMov2CA(cnt, c)
	}
	def send(v: Mov2AB) = {
		val cnt = b !! binary.Mov2AB(v.p)_
		MPMov1CAOrMov2CA(cnt, c)
	}
}

case class MPMov1CAOrMov2CA(b: Out[binary.Mov1ABOrMov2AB], c: In[binary.Mov1CAOrMov2CA]) {
	def receive(implicit timeout: Duration = Duration.Inf) = {
		c.receive(timeout) match {
		  case m @ binary.Mov1CA(p) => {
			  Mov1CA(p, MPMov1ABOrMov2AB(b, m.cont))
		  }
		  case m @ binary.Mov2CA(p) => {
			  Mov2CA(p, MPMov1ABOrMov2AB(b, m.cont))
		  }
		}
	}
}
