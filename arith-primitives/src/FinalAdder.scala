package playground.arith

import chisel3._
import chisel3.util._
import prefix.topology.library.Add

object FinalAdder {
  def apply(columns: Array[Seq[Bool]], implementation: String): UInt = {
    val row0 = Cat(columns.toSeq.reverse.map(_.lift(0).getOrElse(false.B)))
    val row1 = Cat(columns.toSeq.reverse.map(_.lift(1).getOrElse(false.B)))
    val (sum, _) = Add(row0, row1, false.B, implementation)
    sum
  }
}
