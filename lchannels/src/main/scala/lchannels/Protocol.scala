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
package lchannels.protocol

import lchannels.{Channel, Direction, Send, Receive}
import lchannels.medium.{In, Out, End}

abstract class Message[M, D <: Direction] {
  val cont: Channel[_]
}

protected[lchannels] trait MessageSeq[M, D <: Direction, C <: Channel[_]] {
  self: Message[M, D] =>
  val cont: C
}

trait SeqSendSend[M, T1 <: Message[M, Send]]
    extends MessageSeq[M, Send, In[M, T1]] {
  self: Message[M, Send] =>
  override val cont: In[M, T1]
}
trait SeqSendReceive[M, T1 <: Message[M, Receive]]
    extends MessageSeq[M, Send, Out[M, T1]] {
  self: Message[M, Send] =>
  override val cont: Out[M, T1]
}
trait SeqSendEnd[M]
    extends MessageSeq[M, Send, End[M]] {
  self: Message[M, Send] =>
  override val cont = End[M]()
}
trait SeqReceiveSend[M, T1 <: Message[M, Send]]
    extends MessageSeq[M, Receive, Out[M, T1]] {
  self: Message[M, Receive] =>
  override val cont: Out[M, T1]
}
trait SeqReceiveReceive[M, T1 <: Message[M, Receive]]
    extends MessageSeq[M, Receive, In[M, T1]] {
  self: Message[M, Receive] =>
  override val cont: In[M, T1]
}

trait SeqReceiveEnd[M]
    extends MessageSeq[M, Receive, End[M]] {
  self: Message[M, Receive] =>
  override val cont = End[M]()
}

object Protocol {
  import lchannels.{Local, LocalChannel}
  import scala.concurrent.{blocking, ExecutionContext, Future}
  import scala.concurrent.duration.Duration
  
  def serve[Msg <: Message[Local, Send]](
        handler: (In[Local, Msg], Duration) => Unit
      )(implicit ctx: ExecutionContext,
                 timeout: Duration): Out[Local, Msg] = {
    serve(LocalChannel.factory[Msg], handler)
  }
  
  def serve[M, Msg <: Message[M, Send]](
        factory: () => (In[M, Msg], Out[M, Msg]),
        handler: (In[M, Msg], Duration) => Unit
      )(implicit ctx: ExecutionContext,
                 timeout: Duration): Out[M, Msg] = {
    val (cin, cout) = factory()
    Future { blocking { handler(cin, timeout) } }
    cout
  }
  
  def serve[Msg <: Message[Local, Receive]](
        handler: (Out[Local, Msg], Duration) => Unit
      )(implicit ctx: ExecutionContext,
                 timeout: Duration): In[Local, Msg] = {
    serve(LocalChannel.factory[Msg], handler)
  }
  def serve[M, Msg <: Message[M, Receive]](
        factory: () => (In[M, Msg], Out[M, Msg]),
        handler: (Out[M, Msg], Duration) => Unit
      )(implicit ctx: ExecutionContext,
                 timeout: Duration): In[M, Msg] = {
    val (cin, cout) = factory()
    Future { blocking { handler(cout, timeout) } }
    cin
  }
}
