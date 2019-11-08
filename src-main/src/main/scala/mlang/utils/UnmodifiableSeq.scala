package mlang.utils

import scala.collection.{immutable, mutable}

class UnmodifiableSeq[A](buffer: mutable.Seq[A]) extends immutable.Seq[A]{
  def update(idx: Int, elem: A) = throw new UnsupportedOperationException()

  def length = buffer.length

  def apply(idx: Int) = buffer(idx)

  def iterator = buffer.iterator
}
