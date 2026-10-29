/*  Title:      MCP/src/pide_mcp_file_watcher.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_File_Watcher {
  type Stamp = Option[(Long, Long)]

  def stamp(path: Path): Stamp =
    try { if (path.is_file) Some((path.file.lastModified, File.size(path))) else None }
    catch { case ERROR(_) => None }
}

class PIDE_MCP_File_Watcher[A](
  id: String,
  files: () => Iterable[A],
  path: A => Path,
  changed: List[A] => Unit,
  delay: Time,
  log: Logger
) {
  private var stamps = Map.empty[A, PIDE_MCP_File_Watcher.Stamp]

  private def check(): Unit = {
    val current = files().iterator.map(a => a -> PIDE_MCP_File_Watcher.stamp(path(a))).toMap
    val changes = current.iterator.collect {
      case (a, stamp @ Some(_)) if stamps.get(a).forall(_ != stamp) => a
    }.toList
    if (changes.nonEmpty) changed(changes)
    stamps = current
  }

  private val consumer = Consumer_Thread.fork_bulk[Unit]("pide_mcp_file_watcher_" + id,
    daemon = true, log = log, timeout = Some(delay),
    consume = { _ =>
      val result = Exn.capture_trace(
        log.error_message, prefix = s"File watcher ${quote(id)} stopped")(check())
      (Nil, Exn.is_res(result))
    })

  def shutdown(): Unit = consumer.shutdown()
}
