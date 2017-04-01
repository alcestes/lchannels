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
package lchannels.examples.http.server

import lchannels._
import lchannels.examples.http.protocol.binary
import lchannels.examples.http.protocol.server._
import lchannels.examples.http.protocol.types._

import scala.concurrent.duration._
import java.net.Socket
import com.typesafe.scalalogging.StrictLogging
  
class Worker(id: Int, socket: Socket)
            (implicit timeout: Duration)
    extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(f"Worker [${id}]: ${msg}")
  private def logDebug(msg: String) = logger.debug(f"Worker [${id}]: ${msg}")
  private def logInfo(msg: String) = logger.info(f"Worker [${id}]: ${msg}")
  private def logWarn(msg: String) = logger.warn(f"Worker [${id}]: ${msg}")
  private def logError(msg: String) = logger.error(f"Worker [${id}]: ${msg}")

  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def join() = thread.join()
  
  override def run() = {
    logInfo("Started.")
    
    // Create a SocketChannel (with the correct type) from the client socket...
    val c = SocketIn[binary.Request](
        new binary.HttpServerSocketManager(socket, true, (m) => logInfo(m))
    )
    
    // ...and wrap it with a multiparty (in this case, binary) session object,
    // to hide continuation-passing
    val r = MPRequest(c)
    
    val (path, cont) = getRequest(r)
    
    cont.send(HttpVersion(Http11)).send(Code404("Sorry, this is just a test!"))
    
    logInfo("Quitting.")
  }
  
  private def getRequest(c: MPRequest) = {
    val req = c.receive()
    logInfo(f"Method: ${req.p.method}; path: ${req.p.path}; version: ${req.p.version}")
    val cont = choices(req.cont)
    (req.p.path, cont)
  }
  
  @scala.annotation.tailrec
  private def choices(c: MPRequestChoice): MPHttpVersion = c.receive() match {
    case Accept(p, cont)  => {
      logInfo(f"Client accepts: ${p}")
      choices(cont)
    }
    case AcceptEncodings(p, cont)  => {
      logInfo(f"Client encodings: ${p}")
      choices(cont)
    }
    case AcceptLanguage(p, cont)  => {
      logInfo(f"Client languages: ${p}")
      choices(cont)
    }
    case Connection(p, cont)  => {
      logInfo(f"Client connection: ${p}")
      choices(cont)
    }
    case DoNotTrack(p, cont)  => {
      logInfo(f"Client Do Not Track flag: ${p}")
      choices(cont)
    }
    case Host(p, cont)  => {
      logInfo(f"Client host: ${p}")
      choices(cont)
    }
    case RequestBody(p, cont)  => {
      logInfo(f"Client request body: ${p}")
      cont
    }
    case UpgradeIR(p, cont)  => {
      logInfo(f"Client upgrade insecure requests: ${p}")
      choices(cont)
    }
    case UserAgent(p, cont)  => {
      logInfo(f"Client user agent: ${p}")
      choices(cont)
    }
  }
}

object Server extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import java.net.{InetAddress, ServerSocket}
  
  val address = InetAddress.getByName(null)
  val port = 8080
  val ssocket = new ServerSocket(port, 0, address)
  
  println(f"[*] HTTP server listening on: http://${address.getHostAddress}:${port}/")
  println(f"[*] Press Ctrl+C to terminate")
  
  implicit val timeout = 120.seconds
  accept(1)
  
  @scala.annotation.tailrec
  def accept(nextWorkerId: Int) {
    val client = ssocket.accept()
    println(f"[*] Connection from ${client.getInetAddress}, spawning worker")
    new Worker(nextWorkerId, client)
    accept(nextWorkerId + 1)
  }
}
