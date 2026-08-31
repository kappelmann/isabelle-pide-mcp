/*  Title:      PIDE_MCP/progress_task.scala
    Author:     Kevin Kappelmann

Asynchronous operation respecting progress cancellation.
*/

package isabelle.pide.mcp

import isabelle._

abstract class Progress_Task(
  name: String,
  val progress: Progress,
  daemon: Boolean = false
) {
  protected def run(): Unit

  private val thread = Isabelle_Thread.create(
    () => Isabelle_Thread.interrupt_handler(_ => progress.stop()) { run() },
    name = name, daemon = daemon)

  def start(): Unit = thread.start()
  def stopped: Boolean = progress.stopped
  def stop(): Unit = progress.stop()
  def join(): Unit = thread.join()
}
