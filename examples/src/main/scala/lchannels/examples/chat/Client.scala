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

import lchannels._
import lchannels.examples.chat.protocol.public._

import com.typesafe.scalalogging.StrictLogging

class Client(frontend: Out[GetSession], val spec: ClientSpec)
            (implicit timeout: Duration)
    extends Runnable with StrictLogging {
  import scala.concurrent.duration._
  
  private def logTrace(msg: String) = logger.trace(f"${spec.username}: ${msg}")
  private def logDebug(msg: String) = logger.debug(f"${spec.username}: ${msg}")
  private def logInfo(msg: String) = logger.info(f"${spec.username}: ${msg}")
  private def logWarn(msg: String) = logger.warn(f"${spec.username}: ${msg}")
  private def logError(msg: String) = logger.error(f"${spec.username}: ${msg}")
  
  private val roomName = "TestRoom"
  
  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def join() = thread.join()
  
  override def run() = {
    logInfo("started, getting session")
    (frontend !! GetSession(0)_) ? { // Session 0 should not exist
      case Active(srv) => loggedIn(srv)
      case New(authSrv) => authenticate(authSrv)
    }
    logInfo("terminating")
  }
  
  private def loggedIn(srv: Out[session.Command]): Unit = {
    (srv !! session. Join(roomName)_) ? { case session.ChatRoom(msgc, ctl) =>
      logInfo(f"joined chatroom ${roomName}")
      chat(msgc, ctl, spec.msgCount)
    }
  }
  
  private def chat(msgc: In[room.Messages],
                   ctlc: Out[roomctl.Control], msgCount: Int): Unit = {
    if (msgCount <= 0) {
      logInfo(f"leaving chatroom ${roomName}")
      ctlc ! roomctl.Quit()
      receiveMessages(msgc, Duration.Inf) // Receive until Quit()
    } else {
      val text = f"This is message no. ${(spec.msgCount - msgCount) + 1}"
      logInfo(f"sending message: '${text}'")
      val ctlc2 = ctlc !! roomctl.SendMessage(text)_
      receiveMessages(msgc, spec.msgDelay) match {
        case Some(msgc2) => chat(msgc2, ctlc2, msgCount - 1)
        case None => {
          logInfo(f"leaving chatroom ${roomName}")
          ctlc2 ! roomctl.Quit()
        }
      }
    }
  }
  
  private def receiveMessages(msgc: In[room.Messages],
                              maxWait: Duration): Option[In[room.Messages]] = {
    logDebug({
      val wait = if (maxWait.isFinite) f"${maxWait.toMillis/1000.0}" else "Inf"
      f"waiting for messages (maxWait: ${wait} secs)"
    })
    val tStart = System.nanoTime()
    try {
      msgc.receive(maxWait) match {
        case room.Quit() => {
          logInfo("received Quit() from message channel")
          None
        }
        case m @ room.Ping(msg) => {
          logInfo(f"received Ping(${msg})")
          val msgc2 = m.cont !! room.Pong(msg)_
          val elapsed = System.nanoTime - tStart
          receiveMessages(msgc2, maxWait - Duration.fromNanos(elapsed))
        }
        case m @ room.NewMessage(username, text) => {
          logInfo(f"received message: ${username} says '${text}'")
          val elapsed = System.nanoTime - tStart
          receiveMessages(m.cont, maxWait - Duration.fromNanos(elapsed))
        }
      }
    } catch {
      case e: scala.concurrent.TimeoutException => {
        logDebug("stopped waiting for messages")
        Some(msgc) // We keep receiving on the same channel 
      }
    }
  }
  
  private def authenticate(srv: Out[auth.Authenticate]): Unit = {
    logInfo("authenticating")
    (srv !! auth.Authenticate(spec.username, spec.password)_) ? {
      case auth.Failure() => logError("authentication failed")
      case auth.Success(srv) => {
        logInfo("authentication successful")
        loggedIn(srv)
      }
    }
  }
}
