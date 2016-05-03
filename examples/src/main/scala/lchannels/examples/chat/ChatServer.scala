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
package lchannels.examples.chat.server

import lchannels._

import scala.collection.mutable.{Map => MMap}
import scala.concurrent.{Channel, ExecutionContext}
import scala.concurrent.duration.Duration
import scala.util.{Try, Success, Failure}

import com.typesafe.scalalogging.StrictLogging

import lchannels.examples.chat.protocol.public._
import lchannels.examples.chat.protocol.internal.session.{
  GetSession => IntGetSession, GetSessionResult => IntGetSessionResult,
  Success => IntSuccess, Failure => IntFailure,
  CreateSession => IntCreateSession, NewSession => IntNewSession
}

object ChatServer {
  import lchannels.examples.chat.frontend.Frontend
  import lchannels.examples.chat.auth.AuthServer
  import lchannels.examples.chat.protocol.internal.auth.{
    GetAuthentication => IntGetAuth
  }
  def apply(isfactory: () => (In[IntGetSession], Out[IntGetSession]),
            ffactory: () => (In[GetSession], Out[GetSession]),
            iafactory: () => (In[IntGetAuth], Out[IntGetAuth]),
            afactory: () => (In[auth.Authenticate], Out[auth.Authenticate]),
            lfactory: () => (In[IntCreateSession], Out[IntCreateSession]),
            sfactory: () => (In[session.Command], Out[session.Command]),
            rfactory: () => (In[room.Messages], Out[room.Messages]),
            cfactory: () => (In[roomctl.Control], Out[roomctl.Control]))
           (implicit ec: ExecutionContext, timeout: Duration): Frontend = {
    val (isin, isout) = isfactory() // Frontend - chat server channels
    val (ain, aout) = iafactory() // Frontend - auth server channels
    val (lin, lout) = lfactory() // Auth - chat server channels
    
    val chat = new ChatServer(isin, lin, sfactory, rfactory, cfactory)
    val auth = new AuthServer(ain, lout, afactory)
    
    class OverFrontend(isout: Out[IntGetSession], aout: Out[IntGetAuth], 
                       ffactory: () => (In[GetSession], Out[GetSession]))
      extends Frontend(isout, aout, ffactory) {
      override def quit() = {
        super.quit()
        chat.quit()
        auth.quit()
      }
    }
    
    new OverFrontend(isout, aout, ffactory)
  }
  
  def apply()(implicit ec: ExecutionContext, timeout: Duration): Frontend = {
    apply(LocalChannel.factory,
          LocalChannel.factory,
          LocalChannel.factory,
          LocalChannel.factory,
          LocalChannel.factory,
          LocalChannel.factory,
          LocalChannel.factory,
          LocalChannel.factory)
  }
}

protected case class RoomSubscription(id: Int, // Random
                                      msgc: Out[room.Messages])
protected case class Session(username: String,
                                chan: In[session.Command],
                                rooms: MMap[String, // Room joined by username
                                            RoomSubscription])

private case class SessionCommand(id: Int, cmd: session.Command)

/** Chat server frontend */
class ChatServer(frontend: In[IntGetSession],
                 authSrv: In[IntCreateSession],
                 sfactory: () => (In[session.Command], Out[session.Command]),
                 rfactory: () => (In[room.Messages], Out[room.Messages]),
                 cfactory: () => (In[roomctl.Control], Out[roomctl.Control]))
                (implicit ec: ExecutionContext, timeout: Duration)
    extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"${msg}")
  private def logDebug(msg: String) = logger.debug(f"${msg}")
  private def logInfo(msg: String) = logger.info(f"${msg}")
  private def logWarn(msg: String) = logger.warn(f"${msg}")
  private def logError(msg: String) = logger.error(f"${msg}")
  
  // Server name, used for system messages
  protected[server] val serverName = "ChatServer"
  
  // Active sessions: id -> session_info
  type SessionsMap = MMap[Int, Session]
  private var sessions: SessionsMap = MMap()
  
  private val requests: Channel[SessionCommand] = new Channel()
  
  private val sessionHandler = new SessionHandler(this, requests,
                                                  rfactory, cfactory)
  
  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def quit() = {
    thread.interrupt()
    createSessionThread.interrupt()
  }
  
  // Internal session handling thread
  private val createSessionThread = {
    val t = new Thread {
      override def run {
        logDebug("running createSessionLoop")
        createSessionLoop(authSrv)
      }
    }
    t.start
    t
  }
  
  override def run(): Unit = {
    logDebug("started")
    frontendLoop(frontend)
    logDebug("terminating session handler thread")
    sessionHandler.quit()
  }
  
  // NOTE: frontendLoop is not tail-recursive --- left as is for simplicity
  private def frontendLoop(frontend: In[IntGetSession])
                          (implicit timeout: Duration): Unit = {
    try {
      frontend ? { req =>
        logDebug(f"got ${req}")
        // Will be initialized later
        var res: Out[IntGetSession] => IntGetSessionResult = null
        
        sessions.synchronized {
          if (sessions.keySet.contains(req.id)) {
            logDebug(f"active session found, recovering")
            val sessc = recoverSession(req.id)
            logDebug(f"answering with active session")
            res = IntSuccess(sessc)_
          } else {
            logDebug(f"no active session found")
            res = IntFailure()
          }
        }
        
        frontendLoop(req.cont !! res)
      }
    } catch {
      case _: InterruptedException => {
        logDebug("interrupted, leaving frontend loop")
      }
    }
  }
  
  private val rnd = new scala.util.Random()
  
  private def createSessionLoop(authSrv: In[IntCreateSession])
                               (implicit timeout: Duration): Unit = {
    try {
      authSrv ? { req =>
        logDebug(f"got ${req}, preparing new session")
        // Will be initialized later
        var res: Out[IntCreateSession] => IntNewSession = null
        
        sessions.synchronized {
          // Recover old sessions owned by the same user
          val cleanedChans = {
            for (id <- sessions.keys if (sessions(id).username == req.username))
              yield recoverSession(id)
          }
          assert(cleanedChans.size <= 1) // At most 1 session should be recovered
          if (cleanedChans == 1) {
            // Some session was cleaned and replaced, let's use the channel
            res = IntNewSession(cleanedChans.iterator.next())_
          } else {
            // No session was cleaned, let's start a new one from scratch
            val outc = createSession(req.username)
            res = IntNewSession(outc)_
          }
        }
        logDebug(f"sending new session ${res}")
        createSessionLoop(req.cont !! res)
      }
    } catch {
      case _: InterruptedException => {
        logDebug("interrupted, leaving session creation loop")
      }
    }
  }
  
  private def createSession(username: String): Out[session.Command] = {
    val id = allocUniqueSessionId()
    val (in, out) = sfactory()
    // Async. add request to FIFO
    in.future.onComplete { 
      case Success(cmd) => queueRequest(Success(SessionCommand(id, cmd)))
      case Failure(e) => queueRequest(Failure(e))
    }
    // Add the new session to the list of known sessions
    sessions(id) = Session(username, in,
                           MMap[String, RoomSubscription]())
    out
  }
  
  protected[server] def queueRequest(req: Try[SessionCommand]): Unit = req match {
    case Success(r) => {
      logDebug(f"queueing ${r}")
      requests.write(r)
    }
    case Failure(e) => logDebug("got failure, not enqueuing")
  }
  
  // Clean the given *existing* session, assigning its content to a new id.
  // Return the new output channel
  private def recoverSession(id: Int): Out[session.Command] = {
    // NOTE: for simplicity, we just delete and recreate the session
    val username = sessions(id).username
    deleteSession(id)
    createSession(username)
  }
  
  protected[server] def deleteSession(id: Int) = {
    sessions.remove(id) match {
      case Some(session) => {
        // Close all message channels, before getting rid of them
        for (room <- session.rooms.keys) {
          deleteChatRoom(id, room)
        }
      }
      case None => ()
    }
  }
  
  // Throw a NoSuchElementException if the given session id does not exist
  protected[server] def assertSessionId(id: Int): Unit = {
    sessions(id)
  }
  
  // Throw a NoSuchElementException if session id, chat and subscr. don't exist
  protected[server] def assertSessionIdAndChat(id: Int, roomName: String,
                                               subscrId: Int): Unit = {
    sessions.synchronized {
      if (sessions(id).rooms(roomName).id == subscrId) {
        ()
      } else {
        throw new NoSuchElementException()
      }
    }
  }
  
  private def allocUniqueSessionId(): Int = sessions.synchronized {
    val id = rnd.nextInt()
    if (sessions.keySet.contains(id)) {
      allocUniqueSessionId()
    } else {
      sessions(id) = null // FIXME: kludge: "lock" the ID with a temp entry 
      id
    }
  }
  
  // Add a chat room subscription to the given session.
  // Return the subscription id
  protected[server] def addChatRoom(id: Int, rname: String,
                                    msgc: Out[room.Messages]): Int = {
    val subscrId = rnd.nextInt()
    sessions.synchronized {
      sessions(id).rooms(rname) = RoomSubscription(subscrId, msgc)
    }
    subscrId
  }
  
  // Remove the given chat room from the given session
  protected[server] def deleteChatRoom(id: Int,
                                       rname: String) = sessions.synchronized {
    val rSub = sessions(id).rooms.remove(rname).get // Session+room must exist
    rSub.msgc ! room.Quit()
  }
  
  // Return the username owning the given (existing) session id
  protected[server] def getUsername(id: Int): String = {
    sessions(id).username
  }
  
  // Dispatch a message to all users of the given chatroom
  protected[server] def dispatchMessage(rName: String, username: String,
                                        text: String): Unit = {
    sessions.synchronized {
      val subscrS = for ((_, s) <- sessions if s.rooms.keySet.contains(rName))
        yield s
      for (subS <- subscrS) {
        val subC = subS.rooms(rName)
        // Dispatch the message to the subscribed room channel...
        val msgc2 = subC.msgc !! room.NewMessage(username, text)_
        // ... and update the subscription with the continuation
        subS.rooms(rName) = RoomSubscription(subC.id, msgc2)
      }
    }
  }
}

private case class RoomCtlRequest(id: Int, // Session id
                                     room: String,
                                     subscrId: Int, // Room subscription id
                                     cmd: roomctl.Control)

private class SessionHandler(chatServer: ChatServer,
                                requests: Channel[SessionCommand],
                                rfactory: () => (In[room.Messages],
                                                 Out[room.Messages]),
                                cfactory: () => (In[roomctl.Control],
                                                 Out[roomctl.Control]))
                               (implicit ec: ExecutionContext,
                                         timeout: Duration)
    extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"${msg}")
  private def logDebug(msg: String) = logger.debug(f"${msg}")
  private def logInfo(msg: String) = logger.info(f"${msg}")
  private def logWarn(msg: String) = logger.warn(f"${msg}")
  private def logError(msg: String) = logger.error(f"${msg}")
  
  // Server name, used for system messages
  protected[server] val serverName = chatServer.serverName
  
  private val roomRequests: Channel[RoomCtlRequest] = new Channel()
  
  private val roomHandler = new RoomHandler(this, roomRequests)
  
  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def quit() = thread.interrupt()
  
  override def run() = {
    logDebug("started")
    serverLoop()
    logDebug("quitting room handler")
    roomHandler.quit()
  }
  
  def serverLoop(): Unit = {
    try {
      val req = requests.read
      logDebug(f"dequeued ${req}")
      try {
        chatServer.assertSessionId(req.id)
        req.cmd match {
          case session.Quit() => {
            chatServer.deleteSession(req.id)
            serverLoop()
          }
          case m @ session.Ping(msg) => {
            val c = m.cont !! session.Pong(msg)_
            reschedule(req.id, c)
          }
          case m @ session.GetId() => {
            val c = m.cont !! session.Id(req.id)_
            reschedule(req.id, c)
          }
          case m @ session.Join(room) => {
            val (rin, cout) = joinRoom(req.id, room)
            val c = m.cont !! session.ChatRoom(rin, cout)_
            chatServer.dispatchMessage(room, chatServer.serverName,
                f"${chatServer.getUsername(req.id)} joined ${room}")
            reschedule(req.id, c)
          }
        }
      } catch {
        case e: NoSuchElementException => {
          // We tried to operate on a non-existing session:
          // let's loop without rescheduling (i.e., we ignore the channel)
          serverLoop()
        }
      }
    } catch {
      case _: InterruptedException => {
        logDebug("interrupted, leaving session handler loop")
      }
    }
    
    def reschedule(id: Int, c: In[session.Command]): Unit = {
      // Async. add next control message to FIFO, and then loop
      c.future.onComplete {
        case Success(cmd) => {
          chatServer.queueRequest(Success(SessionCommand(id, cmd)))
        }
        case Failure(e) => chatServer.queueRequest(Failure(e))
      }
      serverLoop()
    }
  }
  
  private def joinRoom(id: Int, rname: String): (In[room.Messages],
                                                 Out[roomctl.Control]) = {
    val (rin, rout) = rfactory()
    val (cin, cout) = cfactory()
    
    val subscrId = chatServer.addChatRoom(id, rname, rout) 
    
    // Async. add control messages to FIFO
    cin.future.onComplete {
      case Success(ctl) => {
        queueRoomCtlRequest(Success(RoomCtlRequest(id, rname, subscrId, ctl)))
      }
      case Failure(e) => queueRoomCtlRequest(Failure(e))
    }
    (rin, cout)
  }
  
  protected[server] def queueRoomCtlRequest(req: Try[RoomCtlRequest]): Unit = {
    req match {
      case Success(r) => {
        logDebug(f"queueing ${r}")
        roomRequests.write(r)
      }
      case Failure(e) => logDebug("got failure, not enqueuing")
    }
  }
  
  // Throw a NoSuchElementException if session id, chat and subscr. don't exist
  protected[server] def assertSessionIdAndChat(id: Int, roomName: String,
                                               subscrId: Int): Unit = {
    chatServer.assertSessionIdAndChat(id, roomName, subscrId)
  }
  
  // Dispatch a message to all users of the given chatroom
  protected[server] def dispatchMessage(room: String, username: String,
                                        text: String): Unit = {
    chatServer.dispatchMessage(room, username, text)
  }
  
  // Return the username owning the given (existing) session id
  protected[server] def getUsername(id: Int): String = {
    chatServer.getUsername(id)
  }
  
  // Remove the given chat room from the given session
  protected[server] def deleteChatRoom(id: Int, rname: String) {
    chatServer.deleteChatRoom(id, rname)
  }
}

private class RoomHandler(sessionHandler: SessionHandler,
                             requests: Channel[RoomCtlRequest])
                            (implicit ec: ExecutionContext,
                                      timeout: Duration)
    extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"${msg}")
  private def logDebug(msg: String) = logger.debug(f"${msg}")
  private def logInfo(msg: String) = logger.info(f"${msg}")
  private def logWarn(msg: String) = logger.warn(f"${msg}")
  private def logError(msg: String) = logger.error(f"${msg}")
  
  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def quit() = thread.interrupt()
  
  override def run() = {
    logDebug("started")
    serverLoop()
  }
  
  private def serverLoop(): Unit = {
    try {
      requests.read match { case RoomCtlRequest(sId, rname, subscrId, ctl) =>
        try {
          sessionHandler.assertSessionIdAndChat(sId, rname, subscrId)
          ctl match {
            case roomctl.Quit() => {
              sessionHandler.deleteChatRoom(sId, rname)
              sessionHandler.dispatchMessage(rname, sessionHandler.serverName,
                f"${sessionHandler.getUsername(sId)} left ${rname}")
              serverLoop()
            }
            case m @ roomctl.Ping(msg) => {
              val c = m.cont !! roomctl.Pong(msg)_
              reschedule(sId, rname, subscrId, c)
            }
            case m @ roomctl.SendMessage(text) => {
              val username = sessionHandler.getUsername(sId)
              sessionHandler.dispatchMessage(rname, username, text)
              reschedule(sId, rname, subscrId, m.cont)
            }
          }
        } catch {
          case e: NoSuchElementException => {
            // We tried to operate on a non-existing session or chat subscr.:
            // let's loop without rescheduling (i.e., we ignore the channel)
            serverLoop()
          }
        } 
      }
    } catch {
      case _: InterruptedException => {
        logDebug("interrupted, leaving room handling loop")
      }
    }
    
    def reschedule(sId: Int, roomName: String, subscrId: Int,
                   c: In[roomctl.Control]): Unit = {
      // Async. add next request to FIFO, and then loop
      c.future.onComplete {
        case Success(ctl) => {
          sessionHandler.queueRoomCtlRequest(
              Success(RoomCtlRequest(sId, roomName, subscrId, ctl)))
        }
        case Failure(e) => sessionHandler.queueRoomCtlRequest(Failure(e))
      }
      serverLoop()
    }
  }
}
