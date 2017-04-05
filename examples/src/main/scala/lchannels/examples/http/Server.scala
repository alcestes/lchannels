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
import lchannels.examples.http.protocol.server.{Server => ServerName} // Clash
import lchannels.examples.http.protocol.types._

import scala.concurrent.duration._
import java.net.Socket
import java.nio.file.{Path, Paths}
import java.time.ZonedDateTime;
import com.typesafe.scalalogging.StrictLogging

object Server extends App {
  // Helper method to ease external invocation
  def run() = main(Array())
  
  import java.net.{InetAddress, ServerSocket}
  
  val root = java.nio.file.FileSystems.getDefault().getPath("").toAbsolutePath
  
  val address = InetAddress.getByName(null)
  val port = 8080
  val ssocket = new ServerSocket(port, 0, address)
  
  println(f"[*] HTTP server listening on: http://${address.getHostAddress}:${port}/")
  println(f"[*] Root directory: ${root}")
  println(f"[*] Press Ctrl+C to terminate")
  
  implicit val timeout = 30.seconds
  accept(1)
  
  @scala.annotation.tailrec
  def accept(nextWorkerId: Int) {
    val client = ssocket.accept()
    println(f"[*] Connection from ${client.getInetAddress}, spawning worker")
    new Worker(nextWorkerId, client, root)
    accept(nextWorkerId + 1)
  }
}

class Worker(id: Int, socket: Socket, root: Path)
            (implicit timeout: Duration)
    extends Runnable with StrictLogging {
  private def logTrace(msg: String) = logger.trace(msg)
  private def logDebug(msg: String) = logger.debug(msg)
  private def logInfo(msg: String) = logger.info(msg)
  private def logWarn(msg: String) = logger.warn(msg)
  private def logError(msg: String) = logger.error(msg)
  
  private val serverName = "lchannels HTTP server"
  private val pslash = Paths.get("/") // Used to relativize request paths

  // Own thread
  private val thread = { val t = new Thread(this); t.start(); t }
  def join() = thread.join()
  
  override def run(): Unit = {
    logInfo("Started.")
    
    // Socket manager for the HTTP connection
    val sktmgr = new binary.HttpServerSocketManager(socket, true, logInfo)
    // Create a SocketChannel (with the correct type) from the client socket...
    val c = SocketIn[binary.Request](sktmgr)
    // ...and wrap it with a multiparty (in this case, binary) session object,
    // to hide continuation-passing
    val r = MPRequest(c)
    
    val (rpath, cont) = {
      try getRequest(r)
      catch {
        case sktmgr.ConnectionClosed(msg) => { logInfo(msg); return }
        case e: java.util.concurrent.TimeoutException => {
          logInfo(f"Timeout error: ${e.getMessage}")
          sktmgr.close()
          logInfo("Terminating.")
          return
        }
      }
    }
    
    val path = root.resolve(pslash.relativize(Paths.get(rpath)))
    logInfo(f"Resolved request path: ${path}")
    // TODO: we should reject paths like e.g. ../../../../etc/passwd
    
    val cont2 = cont.send(HttpVersion(Http11))
    
    val file = path.toFile
    
    if (!file.exists || !file.canRead) {
      notFound(cont2, rpath)
    } else {
      logInfo("Resource found.")
      val cont3 = cont2.send(Code200("OK"))
        .send(ServerName(serverName))
        .send(Date(ZonedDateTime.now))
      if (file.isFile) {
        serveFile(cont3, path)
      } else if (file.isDirectory) {
        serveDirectory(cont3, rpath, file)
      } else {
        throw new RuntimeException(f"BUG: unsupported resource type: ${path}")
      }
    }
    
    logInfo("Terminating.")
  }
  
  private def getRequest(c: MPRequest)(implicit timeout: Duration) = {
    val req = c.receive
    logInfo(f"Method: ${req.p.method}; path: ${req.p.path}; version: ${req.p.version}")
    val cont = choices(req.cont)
    (req.p.path, cont)
  }
  
  @scala.annotation.tailrec
  private def choices(c: MPRequestChoice)
                     (implicit timeout: Duration): MPHttpVersion = c.receive match {
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
  
  private def notFound(c: MPCode200OrCode404, res: String) = {
    logInfo(f"Resource not found: ${res}")
    c.send(Code404("Not Found"))
      .send(ServerName(serverName))
      .send(Date(ZonedDateTime.now))
      .send(ResponseBody(
          Body("text/plain", f"Resource ${res} not found".getBytes("UTF-8"))))
  }
  
  private def serveFile(c: MPResponseChoice, file: Path) = {
    val filename = file.getFileName().toString()
    val contentType = {
      if (filename.endsWith(".html")) "text/html"
      else if (filename.endsWith(".css")) "text/css"
      else "text/plain" // TODO: we assume content is human-readable
    }
    logInfo(f"Serving file: ${file} (content type: ${contentType}")
    
    // TODO: for simplicity, we assume all files are UTF-8
    c.send(ResponseBody(
        Body(f"${contentType}; charset=utf-8", java.nio.file.Files.readAllBytes(file))))
  }
  
  private def serveDirectory(c: MPResponseChoice, rpath: String, dir: java.io.File) = {
    logInfo(f"Serving directory: ${dir}")
    
    val list = dir.listFiles.foldLeft(""){(a,i) =>
      a + f"""|      <li>
              |        <a href="${i.getName}${if (i.isFile) "" else "/"}">
              |          ${i.getName}${if (i.isFile) "" else "/"}
              |        </a>
              |      </li>\n""".stripMargin
    }
    val html = f"""|<!DOCTYPE html>
                   |<html>
                   |  <head>
                   |    <meta charset="UTF-8">
                   |    <title>Contents of ${rpath}</title>
                   |  </head>
                   |  <body>
                   |    <h1>Contents of ${rpath}</h1>
                   |    <ul>
                   |${list}
                   |    </ul>
                   |    <p><em>Page generated by ${serverName}</em></p>
                   |  </body>
                   |</html>\n""".stripMargin
    
    c.send(ResponseBody(
        Body("text/html", html.getBytes("UTF-8"))))
  }
}
