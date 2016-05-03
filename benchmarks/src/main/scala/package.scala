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
package lchannels

package object benchmarks {
  private val defaultMsgCount = 25600
  
  case class BenchmarkResult(title: String, data: Iterator[Long])
  type BenchmarkResults = List[BenchmarkResult]
  
  def main(args: Array[String]): Unit = {
    val savePath = if (args.length < 1) {
      throw new IllegalArgumentException("You must provide the path for saving benchmark results")
    } else {
      args(0)
    }
    
    val msgCount = if (args.length >= 2) args(1).toInt else defaultMsgCount
    
    run(savePath, msgCount)
  }
  
  def run(savePath: String, msgCount: Int) {
    import java.nio.file.Paths
    
    case class Benchmark(title: String, benchf: () => BenchmarkResults,
                         filename: String)
    
    val repetitions = 30 // 30 should be the minimum for one JVM invocation
    
    println(f"Starting benchmarks: ${msgCount} minimum message transmissions, ${repetitions} repetitions")
    
    val benchmarks = List(
      Benchmark(title    = f"Ping-pong (${msgCount} message exchanges)",
                benchf   = () => pingpong.Benchmark(msgCount*2, repetitions, "Token"),
                filename = "pingpong.csv"),
      Benchmark(title    = f"Ring (${1000} processes, ${msgCount/1000} loops)",
                benchf   = () => ring.Benchmark(ring.Benchmark.Standard(),
                                                msgCount, 1000, repetitions, "Token"),
                filename = "ring.csv"),
      Benchmark(title    = f"Streaming (${16} processes, ${msgCount*3/2} msgs sent/recvd)",
                benchf   = () => ring.Benchmark(ring.Benchmark.Streaming(),
                                                msgCount*3/2, 16, repetitions, "Token"),
                filename = "ring-stream.csv"),
      Benchmark(title    = f"Chameneos (${256} chameneos, ${(msgCount*6) / (3 * 2)} meetings)",
                benchf   = () => chameneos.Benchmark(msgCount*6, 256, repetitions),
                filename = "chameneos.csv")
    )
    
    for (b <- benchmarks) {
      val fname = Paths.get(savePath, b.filename).toString()
      runAndSave(b.title, b.benchf, fname)
      println(f"    Results saved in: ${fname}")
    }
  }
    
  private def runAndSave(title: String, benchf: () => BenchmarkResults,
                         filename: String) : Unit = {
    import java.io.{File, PrintWriter}
    
    val delim = ","
    
    val out = new PrintWriter(new File(filename))
    
    val res = benchf()
    
    val nbench = res.length
    val titles = res.map(x => x.title)
    
    out.write(title ++ "\n")
    
    // Save benchmarks as values in column under their respective titles
    out.write(titles.mkString(delim) ++ "\n")
    while (res(0).data.hasNext) {
      out.write(
        (for (i <- 0 until nbench) yield res(i).data.next()).mkString(delim)
      )
      out.write("\n")
    }
    
    out.close()
  }
}
