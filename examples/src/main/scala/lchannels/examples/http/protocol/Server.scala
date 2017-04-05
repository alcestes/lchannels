// lchannels - session programming in Scala
// Copyright (c) 2017, Alceste Scalas and Imperial College London
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

/** HTTP protocol server.
 *  The classes in this package have been automatically generated from the
 *  Scribble HTTP protocol definition:
 *  https://github.com/alcestes/scribble-java/blob/linear-channels/modules/linmp-scala/src/test/scrib/Http.scr
 *  
 * @author Alceste Scalas <alceste.scalas@imperial.ac.uk> */
package lchannels.examples.http.protocol.server

import scala.concurrent.duration.Duration
import lchannels._
import lchannels.examples.http.protocol.binary
import lchannels.examples.http.protocol.types._

import java.time.ZonedDateTime;

// Input message types for multiparty sessions
case class Request(p: RequestLine, cont: MPRequestChoice)

sealed abstract class MsgMPRequestChoice
case class AcceptEncodings(p: String, cont: MPRequestChoice) extends MsgMPRequestChoice
case class Accept(p: String, cont: MPRequestChoice) extends MsgMPRequestChoice
case class DoNotTrack(p: Boolean, cont: MPRequestChoice) extends MsgMPRequestChoice
case class UpgradeIR(p: Boolean, cont: MPRequestChoice) extends MsgMPRequestChoice
case class Connection(p: String, cont: MPRequestChoice) extends MsgMPRequestChoice
case class UserAgent(p: String, cont: MPRequestChoice) extends MsgMPRequestChoice
case class AcceptLanguage(p: String, cont: MPRequestChoice) extends MsgMPRequestChoice
case class Host(p: String, cont: MPRequestChoice) extends MsgMPRequestChoice
case class RequestBody(p: Body, cont: MPHttpVersion) extends MsgMPRequestChoice

// Output message types for multiparty sessions
case class HttpVersion(p: Version)
case class Code404(p: String)
case class Code200(p: String)
case class ETag(p: String)
case class Server(p: String)
case class ContentLength(p: Int)
case class ContentType(p: String)
case class Vary(p: String)
case class Via(p: String)
case class StrictTS(p: String)
case class ResponseBody(p: Body)
case class AcceptRanges(p: String)
case class LastModified(p: ZonedDateTime)
case class Date(p: ZonedDateTime)

// Multiparty session classes
case class MPRequest(C: In[binary.Request]) {
  def receive(implicit timeout: Duration = Duration.Inf) = {
    C.receive(timeout) match {
      case m @ binary.Request(p) => {
        Request(p, MPRequestChoice(m.cont))
      }
    }
  }
}

case class MPRequestChoice(C: In[binary.RequestChoice]) {
  def receive(implicit timeout: Duration = Duration.Inf) = {
    C.receive(timeout) match {
      case m @ binary.Accept(p) => {
        Accept(p, MPRequestChoice(m.cont))
      }
      case m @ binary.AcceptEncodings(p) => {
        AcceptEncodings(p, MPRequestChoice(m.cont))
      }
      case m @ binary.AcceptLanguage(p) => {
        AcceptLanguage(p, MPRequestChoice(m.cont))
      }
      case m @ binary.Connection(p) => {
        Connection(p, MPRequestChoice(m.cont))
      }
      case m @ binary.DoNotTrack(p) => {
        DoNotTrack(p, MPRequestChoice(m.cont))
      }
      case m @ binary.Host(p) => {
        Host(p, MPRequestChoice(m.cont))
      }
      case m @ binary.RequestBody(p) => {
        RequestBody(p, MPHttpVersion(m.cont))
      }
      case m @ binary.UpgradeIR(p) => {
        UpgradeIR(p, MPRequestChoice(m.cont))
      }
      case m @ binary.UserAgent(p) => {
        UserAgent(p, MPRequestChoice(m.cont))
      }
    }
  }
}

case class MPHttpVersion(C: Out[binary.HttpVersion]) {
  def send(v: HttpVersion) = {
    val cnt = C !! binary.HttpVersion(v.p)_
    MPCode200OrCode404(cnt)
  }
}

case class MPCode200OrCode404(C: Out[binary.Code200OrCode404]) {
  def send(v: Code200) = {
    val cnt = C !! binary.Code200(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: Code404) = {
    val cnt = C !! binary.Code404(v.p)_
    MPResponseChoice(cnt)
  }
}

case class MPResponseChoice(C: Out[binary.ResponseChoice]) {
  def send(v: AcceptRanges) = {
    val cnt = C !! binary.AcceptRanges(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: ContentLength) = {
    val cnt = C !! binary.ContentLength(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: ContentType) = {
    val cnt = C !! binary.ContentType(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: Date) = {
    val cnt = C !! binary.Date(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: ETag) = {
    val cnt = C !! binary.ETag(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: LastModified) = {
    val cnt = C !! binary.LastModified(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: ResponseBody) = {
    val cnt = C ! binary.ResponseBody(v.p)
    ()
  }
  def send(v: Server) = {
    val cnt = C !! binary.Server(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: StrictTS) = {
    val cnt = C !! binary.StrictTS(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: Vary) = {
    val cnt = C !! binary.Vary(v.p)_
    MPResponseChoice(cnt)
  }
  def send(v: Via) = {
    val cnt = C !! binary.Via(v.p)_
    MPResponseChoice(cnt)
  }
}
