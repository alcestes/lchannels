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
package lchannels.examples.chat.auth

import lchannels._

import lchannels.examples.chat.protocol.public._

import scala.concurrent.{Channel, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.{Try, Success, Failure}

import com.typesafe.scalalogging.StrictLogging

import lchannels.examples.chat.protocol.public.auth._
import lchannels.examples.chat.protocol.internal.auth.{
  GetAuthentication => IntGetAuth, Authentication => IntAuth
}
import lchannels.examples.chat.protocol.internal.session.{
  CreateSession => IntCreateSession
}

/** Chat server frontend */
class AuthServer(chan: In[IntGetAuth], sessionSrv: Out[IntCreateSession],
                 factory: () => (In[Authenticate], Out[Authenticate]))
                (implicit ec: ExecutionContext, timeout: Duration)
    extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"${msg}")
  private def logDebug(msg: String) = logger.debug(f"${msg}")
  private def logInfo(msg: String) = logger.info(f"${msg}")
  private def logWarn(msg: String) = logger.warn(f"${msg}")
  private def logError(msg: String) = logger.error(f"${msg}")
  
  // FIFO queue with requests from clients
  private val requests: Channel[Authenticate] = new Channel()
  
  private val authenticator = new Authenticator(requests, sessionSrv)
  
  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def quit() = thread.interrupt()
  
  override def run(): Unit = {
    logDebug("started")
    serverLoop(chan)
    logDebug("quitting authenticator")
    authenticator.quit()
  }
  
  // Server loop for frontend interaction
  private def serverLoop(chan: In[IntGetAuth])
                        (implicit timeout: Duration): Unit = {
    try {
      chan ? { req =>
        logDebug(f"got ${req}, creating new authentication channels")
        val (in, out) = factory()
        in.future.onComplete(queueRequest) // Async. add request to FIFO
        logDebug("sending authentication channel")
        serverLoop(req.cont !! IntAuth(out)_)
      }
    } catch {
      case _: InterruptedException => {
        logDebug("interrupted, leaving main loop")
      }
    }
  }
  
  private def queueRequest(req: Try[Authenticate]): Unit = req match {
    case Success(r) => {
      logDebug(f"queueing ${r}")
      requests.write(r)
    }
    case Failure(e) => logDebug("got failure, not enqueuing")
  }
}

private class Authenticator(requests: Channel[Authenticate],
                            sessionSrv: Out[IntCreateSession])
                           (implicit timeout: Duration)
    extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"${msg}")
  private def logDebug(msg: String) = logger.debug(f"${msg}")
  private def logInfo(msg: String) = logger.info(f"${msg}")
  private def logWarn(msg: String) = logger.warn(f"${msg}")
  private def logError(msg: String) = logger.error(f"${msg}")
  
  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def quit() = thread.interrupt()
  
  private val accounts = List(
    ("alice", "password"), ("bob", "password"), ("carol", "password")
  )
  
  override def run() = {
    logDebug("started")
    serverLoop(sessionSrv)
  }
  
  def serverLoop(sessionSrv: Out[IntCreateSession]): Unit = {
    try {
      serverLoop(serve(requests.read, sessionSrv))
    } catch {
      case _: InterruptedException => {
        logDebug("interrupted, leaving main loop")
      }
    }
  }
  
  private def serve(req: Authenticate,
                    sessionSrv: Out[IntCreateSession])
                   (implicit timeout: Duration): Out[IntCreateSession] = {
    if (accounts.contains((req.username, req.password))) {
      logDebug(f"login successful for ${req.username}, getting new session")
      (sessionSrv !! IntCreateSession(req.username)_) ? { newSess =>
        logDebug(f"forwarding new session")
        req.cont ! auth.Success(newSess.channel)
        newSess.cont
      }
    } else {
      logDebug(f"login failed for ${req.username}")
      req.cont ! auth.Failure()
      sessionSrv
    }
  }
}
