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
/** Internal definitions, e.g. protocols not intended for client usage
 * 
 * @author Alceste Scalas <alceste.scalas@imperial.ac.uk>
 */
package lchannels.examples.chat.protocol.internal

import lchannels._

package session {
  ////////////////////////////////////////////////////////////////////////////
  // Type for retrieving existing chat sessions from session server:
  // rec X . ?GetSession(Int) . (!Session(S_act).X (+) Failure().end)
  ////////////////////////////////////////////////////////////////////////////
  import lchannels.examples.chat.protocol.public.session.{
    Command => PubCommand
  }
  
  case class GetSession(id: Int)(val cont: Out[GetSessionResult])
  
  sealed abstract class GetSessionResult
  case class Success(channel: Out[PubCommand])
                    (val cont: Out[GetSession])   extends GetSessionResult
  case class Failure()(val cont: Out[GetSession]) extends GetSessionResult
  
  ////////////////////////////////////////////////////////////////////////////
  // Type for creating chat sessions on chat server (used by auth server):
  // rec X . ?CreateSession(String).!NewSession(S_act).X
  ////////////////////////////////////////////////////////////////////////////
  case class CreateSession(username: String)(val cont: Out[NewSession])
  
  case class NewSession(channel: Out[PubCommand])(val cont: Out[CreateSession])
}

package auth {
  ////////////////////////////////////////////////////////////////////////////
  // Type for getting authentication channels from auth server:
  // rec X . ?NewAuth() . !Session(S_auth).X
  ////////////////////////////////////////////////////////////////////////////
  import lchannels.examples.chat.protocol.public.auth.{
    Authenticate => PubAuthenticate
  }
  
  case class GetAuthentication()(val cont: Out[Authentication])

  case class Authentication(channel: Out[PubAuthenticate])
                           (val cont: Out[GetAuthentication])
}
