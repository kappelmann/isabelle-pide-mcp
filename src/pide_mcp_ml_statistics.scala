/*  Title:      PIDE_MCP/pide_mcp_ml_statistics.scala
    Author:     Kevin Kappelmann

Backport of the isabelle.ML_Statistics field interface for Isabelle2025-2.
*/

package isabelle.pide.mcp

import isabelle._

import scala.annotation.tailrec

object ML_Statistics {
  def apply(statistics: List[Properties.T]): isabelle.ML_Statistics =
    isabelle.ML_Statistics(statistics)

  def jvm_statistics(): Properties.T = Java_Statistics.jvm_statistics()


  /* fields */

  class Field(val name: String, description: String = "") {
    val domain: List[String] = List(name)
    def title: String = proper_string(description).getOrElse(Word.informal(name))
    def unapply(props: Properties.T): Option[Double] =
      Properties.get(props, name).flatMap(Value.Double.unapply)
    def get(props: Properties.T): Double = unapply(props).getOrElse(0.0)
    def scale(y: Double): Double = y
  }

  class Field_MiB(name: String, description: String = "")
  extends Field(name, description = description) {
    override def scale(y: Double): Double = Space.B(y).MiB
  }

  val Heap_Size =
    new Field_MiB("size_heap", description = "heap size")
  val Heap_Free_Minor =
    new Field_MiB("size_heap_free_last_GC", description = "heap free (minor GC)")

  val Tasks_Running = new Field("tasks_running")
  val Tasks_Total = new Field("tasks_total")

  val Workers_Total = new Field("workers_total")
  val Workers_Active = new Field("workers_active")

  val GCs_Minor = new Field("partial_GCs", description = "GCs (minor)")
  val GCs_Major = new Field("full_GCs", description = "GCs (major)")

  val Program_Code = new Field_MiB("size_code", description = "program code")
  val Program_Stack = new Field_MiB("size_stacks", description = "program stack")

  val Threads_ML = new Field("threads_in_ML", description = "threads (ML)")

  val Java_Heap_Size = new Field_MiB("java_heap_size", description = "Java heap size")
  val Java_Heap_Used = new Field_MiB("java_heap_used", description = "Java heap used")
  val Java_Threads_Total = new Field("java_threads_total", description = "Java threads total")
  val Java_Workers_Total = new Field("java_workers_total", description = "Java workers total")
  val Java_Workers_Active = new Field("java_workers_active", description = "Java workers active")


  /* content interpretation */

  private def entry_value(entry: isabelle.ML_Statistics.Entry, field: Field): Double = {
    val props = field.domain.map(x => x -> Value.Double(entry.data.getOrElse(x, 0.0)))
    field.scale(field.get(props))
  }

  def maximum(statistics: isabelle.ML_Statistics, field: Field): Double =
    statistics.content.foldLeft(0.0) { case (m, e) => m max entry_value(e, field) }

  def average(statistics: isabelle.ML_Statistics, field: Field): Double = {
    @tailrec def sum(
      t0: Double,
      list: List[isabelle.ML_Statistics.Entry],
      acc: Double
    ): Double =
      list match {
        case Nil => acc
        case e :: es =>
          val t = e.time
          sum(t, es, (t - t0) * entry_value(e, field) + acc)
      }
    statistics.content match {
      case Nil => 0.0
      case List(e) => entry_value(e, field)
      case e :: es => sum(e.time, es, 0.0) / statistics.duration
    }
  }
}
