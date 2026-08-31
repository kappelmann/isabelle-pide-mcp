/*  Title:      PIDE_MCP/queue_lock.scala
    Author:     Kevin Kappelmann

FIFO cancellable re-entrant queue
*/

package isabelle.pide.mcp

import isabelle._

class Queue_Lock {
  private val queue = Synchronized[List[Thread]](Nil)

  def waiting: Int = queue.value.length

  def with_lock[A](
    progress: Progress,
    message: => String,
    delay: Time,
    progress_delay: Time
  )(body: => A): A = {
    val thread = Thread.currentThread().nn
    def ahead: Int = queue.value.indexOf(thread)
    def holding: Boolean = ahead == 0
    if (holding) body
    else {
      queue.change(_ ::: List(thread))
      try {
        PIDE_MCP_Progress.await(progress,
          message + if_proper(ahead > 0, s" ($ahead ahead in queue)"),
          delay, progress_delay)(Option.when(holding)(()))
        body
      } finally queue.change(_.filterNot(_ == thread))
    }
  }
}
