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
package lchannels.medium

abstract class In[M, +T] extends lchannels.In[T]

abstract class Out[M, -T] extends lchannels.Out[T] {
  override def create[U](): (In[M, U], Out[M, U])
  
  /** Medium-constrained version of [[lchannels.Out.createContIn]]. */
  override def createContIn[U](): (In[M, U], Out[M, U])  = create[U]()
  
  /** Medium-constrained version of [[lchannels.Out.createContOut]]. */
  override def createContOut[U](): (In[M, U], Out[M, U])  = create[U]()

  // FIXME: we would ideally merge ! and !!, but Scala gets confused
  // NOTE: !! works better with messages defined as curried case classes
  // See: http://pchiusano.blogspot.co.uk/2011/05/making-most-of-scalas-extremely-limited.html
  /** Medium-constrained version of [[lchannels.Out]].`!!`. */
  def !![U](f: In[M, U] => T): Out[M, U] = {
    val (cin, cout) = createContOut[U]()
    this ! f(cin)
    cout
  }
  /** Medium-constrained version of [[lchannels.Out]].`!!`. */
  def !![U](f: Out[M, U] => T): In[M, U] = {
    val (cin, cout) = createContIn[U]()
    this ! f(cout)
    cin
  }
}

case class End[M]() extends lchannels.Channel[lchannels.None]
