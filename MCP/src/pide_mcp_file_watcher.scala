/*  Title:      MCP/src/pide_mcp_file_watcher.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import java.io.{File => JFile}
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

object PIDE_MCP_File_Watcher {
  type Stamp = Option[(Long, Long)]

  def stamp(file: JFile): Stamp =
    try {
      val attrs = Files.readAttributes(file.toPath, classOf[BasicFileAttributes]).nn
      if (attrs.isRegularFile) Some((attrs.lastModifiedTime.nn.toMillis, attrs.size)) else None
    }
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
  private var stamps = Map.empty[A, (JFile, PIDE_MCP_File_Watcher.Stamp)]

  private def check(): Unit = {
    val current = files().iterator.map { a =>
      val file = stamps.get(a).fold(path(a).file)(_._1)
      a -> (file, PIDE_MCP_File_Watcher.stamp(file))
    }.toMap
    val changes = current.iterator.collect {
      case (a, (_, stamp @ Some(_))) if stamps.get(a).forall(_._2 != stamp) => a
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
