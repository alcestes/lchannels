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
package lchannels.examples.chat.protocol.public

import lchannels._

///////////////////////////////////////////////////////////////////////////////
// Session type S_front:
// ?GetSession() . (!Active(S_act).end (+) !New(S_auth).end)
///////////////////////////////////////////////////////////////////////////////
case class GetSession(id: Int)(val cont:Out[GetSessionResult])

sealed abstract class GetSessionResult
case class Active(chat: Out[session.Command]) extends GetSessionResult
case class New(authc: Out[auth.Authenticate]) extends GetSessionResult

package session {
  /////////////////////////////////////////////////////////////////////////////
  // Session type S_act:
  // rec X . &( ?GetId().!Id(Int).X,
  //            ?Ping().!Pong().X,
  //            ?Join(String).!ChatRoom(S_cmsgs, S_cctl).X,
  //            ?Quit.end )
  /////////////////////////////////////////////////////////////////////////////
  sealed abstract class Command
  case class GetId()(val cont: Out[Id])                 extends session.Command
  case class Ping(msg: String)(val cont: Out[Pong])     extends session.Command
  case class Join(room: String)(val cont: Out[ChatRoom]) extends session.Command
  case class Quit()                                     extends session.Command
  
  case class Id(id: Int)(val cont: Out[Command])
  
  case class Pong(msg: String)(val cont: Out[Command])
  
  case class ChatRoom(msgs: In[room.Messages], ctl: Out[roomctl.Control])
                     (val cont: Out[Command])
}

package room { 
  /////////////////////////////////////////////////////////////////////////////
  // Session type S_r:
  // rec X . !NewMessage(String, String).X  (+)  !Ping().?Pong().X (+) !Quit()
  /////////////////////////////////////////////////////////////////////////////
  sealed abstract class Messages
  case class NewMessage(username: String, text: String)
                       (val cont: In[Messages])                extends Messages
  case class Ping(msg: String)(val cont: Out[Pong])            extends Messages
  case class Quit()                                            extends Messages

  case class Pong(msg: String)(val cont: Out[Messages])
}

package roomctl {
  /////////////////////////////////////////////////////////////////////////////
  // Session type S_rctl:
  // rec X . !NewMessage(String).X  (+)  !Ping().?Pong().X (+) !Quit().end
  /////////////////////////////////////////////////////////////////////////////
  sealed abstract class Control
  case class SendMessage(text: String)(val cont: In[Control]) extends Control
  case class Ping(msg: String)(val cont: Out[Pong])           extends Control
  case class Quit()                                           extends Control
  
  case class Pong(msg: String)(val cont: Out[Control])
}

package auth {
  /////////////////////////////////////////////////////////////////////////////
  // Session type S_auth:
  // ?Authenticate(Credentials) . (!Success(S_act).end (+) !Failure().end)
  /////////////////////////////////////////////////////////////////////////////
  case class Authenticate(username: String, password: String)
                         (val cont: Out[AuthenticateResult])

  sealed abstract class AuthenticateResult
  case class Success(service: Out[session.Command]) extends AuthenticateResult
  case class Failure()                              extends AuthenticateResult
}
