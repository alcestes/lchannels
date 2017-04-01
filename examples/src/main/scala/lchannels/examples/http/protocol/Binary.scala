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

/** Binary protocol classes for the HTTP server.
 *  The classes in this package have been automatically generated from the
 *  Scribble HTTP protocol definition:
 *  https://github.com/alcestes/scribble-java/blob/linear-channels/modules/linmp-scala/src/test/scrib/Http.scr
 *  
 * @author Alceste Scalas <alceste.scalas@imperial.ac.uk> */
package lchannels.examples.http.protocol.binary

import lchannels._
import lchannels.examples.http.protocol.types._

import java.time.ZonedDateTime;

case class Request(p: RequestLine)(val cont: In[RequestChoice])

sealed abstract class RequestChoice
case class Accept(p: String)(val cont: In[RequestChoice]) extends RequestChoice
case class AcceptEncodings(p: String)(val cont: In[RequestChoice]) extends RequestChoice
case class AcceptLanguage(p: String)(val cont: In[RequestChoice]) extends RequestChoice
case class Connection(p: String)(val cont: In[RequestChoice]) extends RequestChoice
case class DoNotTrack(p: Boolean)(val cont: In[RequestChoice]) extends RequestChoice
case class Host(p: String)(val cont: In[RequestChoice]) extends RequestChoice
case class RequestBody(p: Body)(val cont: Out[HttpVersion]) extends RequestChoice
case class UpgradeIR(p: Boolean)(val cont: In[RequestChoice]) extends RequestChoice
case class UserAgent(p: String)(val cont: In[RequestChoice]) extends RequestChoice

case class HttpVersion(p: Version)(val cont: In[Code200OrCode404])

sealed abstract class Code200OrCode404
case class Code200(p: String)(val cont: In[ResponseChoice]) extends Code200OrCode404
case class Code404(p: String)(val cont: In[ResponseChoice]) extends Code200OrCode404

sealed abstract class ResponseChoice
case class AcceptRanges(p: String)(val cont: In[ResponseChoice]) extends ResponseChoice
case class ContentLength(p: Int)(val cont: In[ResponseChoice]) extends ResponseChoice
case class ContentType(p: String)(val cont: In[ResponseChoice]) extends ResponseChoice
case class Date(p: ZonedDateTime)(val cont: In[ResponseChoice]) extends ResponseChoice
case class ETag(p: String)(val cont: In[ResponseChoice]) extends ResponseChoice
case class LastModified(p: ZonedDateTime)(val cont: In[ResponseChoice]) extends ResponseChoice
case class ResponseBody(p: Body) extends ResponseChoice
case class Server(p: String)(val cont: In[ResponseChoice]) extends ResponseChoice
case class StrictTS(p: String)(val cont: In[ResponseChoice]) extends ResponseChoice
case class Vary(p: String)(val cont: In[ResponseChoice]) extends ResponseChoice
case class Via(p: String)(val cont: In[ResponseChoice]) extends ResponseChoice

import java.net.Socket
import java.io.{
  BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter
}

/** Socket manager for the HTTP protocol.
 *  
 *  @param socket the socket managed by the instance
 *  @param relaxHeaders if true, skip unmanaged HTTP headers (otherwise, error)
 *  @param logger logging function, used to report e.g. skipped headers and other info 
 */
class HttpServerSocketManager(socket: Socket,
                              relaxHeaders: Boolean,
                              logger: (String) => Unit) extends SocketManager(socket) {
  private val outb = new BufferedWriter(new OutputStreamWriter(out))
  private var requestStarted = false // Remembers whether GET/POST/... was seen
  
  private val crlf = "\r\n"
  
  override def streamer(x: Any) = x match {
    case HttpVersion(v) => outb.write(f"${v} ")
    case Code404(msg) => {
      outb.write(f"404 ${msg}${crlf}"); outb.flush()
      close()
    }
  }
  
  private val inb = new BufferedReader(new InputStreamReader(in))
  private val requestR = """(\S+) (\S+) (\S+)""".r // Start of HTTP request
  private val acceptR = """Accept: (.+)""".r // Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8
  private val acceptEncR = """Accept-Encoding: (.+)""".r // Accept-Encoding: gzip, deflate
  private val acceptLangR = """Accept-Language: (.+)""".r // Accept-Language: en-GB,en;q=0.5
  private val connectionR = """Connection: (\S+)""".r // Connection: keep-alive
  private val dntR = """DNT: (\d)""".r // DNT: 1
  private val hostR = """Host: (\S+)""".r // Host: www.doc.ic.ac.uk
  private val upgradeirR = """Upgrade-Insecure-Requests: (\d)""".r // Upgrade-Insecure-Requests: 1
  private val useragentR = """User-Agent: (.+)""".r // User-Agent: Mozilla/5.0 (Windows NT 6.3; WOW64; rv:38.0) Gecko/20100101 Firefox/38.0
  
  private val genericHeaderR = """(\S+): (.+)""".r // Generic regex for unsupported headers
  
  override def destreamer(): Any = {
    val line = inb.readLine()
    
    if (!requestStarted) {
      line match {
        case requestR(method, path, version) => {
          requestStarted = true;
          return Request(RequestLine(method, path, Version(version)))(SocketIn[RequestChoice](this))
        }
        
        case e => { close(); throw new RuntimeException(f"Unexpected initial message: '${e}'") }
      }
    }
    
    // If we are here, then requestStarted was false
    line match {
      case acceptR(fmts) => Accept(fmts)(SocketIn[RequestChoice](this))
      case acceptEncR(encs) => AcceptEncodings(encs)(SocketIn[RequestChoice](this))
      case acceptLangR(langs) => AcceptLanguage(langs)(SocketIn[RequestChoice](this))
      case connectionR(conn) => Connection(conn)(SocketIn[RequestChoice](this))
      case dntR(dnt) => DoNotTrack(dnt == 1)(SocketIn[RequestChoice](this))
      case hostR(host) => Host(host)(SocketIn[RequestChoice](this))
      case upgradeirR(up) => UpgradeIR(up == 1)(SocketIn[RequestChoice](this))
      case useragentR(ua) => UserAgent(ua)(SocketIn[RequestChoice](this))
      
      case genericHeaderR(h, _) if relaxHeaders => {
        // Ignore this header, and keep looking for something supported
        logger(f"Skipping unsupported HTTP header '${h}'")
        destreamer()
      }
      
      case "" => {
        // The request body should now follow.
        // TODO: in this HTTP fragment, we assume GET with Content-Length=0
        RequestBody(Body("text/html", Array[Byte]()))(SocketOut[HttpVersion](this))
      }
      
      case e => { close(); throw new RuntimeException(f"Unexpected message: '${e}'") }
    }
  }
}
