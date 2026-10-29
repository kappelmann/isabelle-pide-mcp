/*  Title:      MCP/src/Services/service_file_watcher.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Service_File_Watcher extends PIDE_MCP_Service("file_watcher") {
  private val watchers =
    Synchronized(Map.empty[String, (PIDE_MCP_File_Watcher[Document.Node.Name], Progress)])

  private def delay(options: Options): Time = options.seconds("pide_mcp_file_watcher_delay")

  private def loaded_files(session: PIDE_MCP_Session): List[Document.Node.Name] =
    session.snapshot().version.nodes.iterator.collect {
      case (name, node) if PIDE_MCP_Util.is_loaded_dynamic(node) => name
    }.toList

  override def start(
    sessions: PIDE_MCP_Sessions,
    session: PIDE_MCP_Session,
    progress: Progress
  ): Unit = {
    val log = sessions.log
    val sync_progress = new Sub_Progress(sessions.base_progress)
    val watcher = new PIDE_MCP_File_Watcher[Document.Node.Name](
      session.id, () => loaded_files(session), _.path,
      changed => Exn.result {
          session.with_lock(sync_progress) {
            val nodes = session.tip_version(sync_progress).nodes
            session.read_update(
              changed.filter(name => PIDE_MCP_Util.is_loaded_dynamic(nodes(name))).map(_ -> None),
              hide_others = false, progress = sync_progress)
          }
        } match {
          case Exn.Exn(exn) =>
            log.warning(s"File watcher ${quote(session.id)} failed to synchronize: " +
              Exn.message(exn))
          case _ =>
        },
      delay(session.options), log)
    watchers.change(_ + (session.id -> (watcher, sync_progress)))
  }

  override def stop(
    sessions: PIDE_MCP_Sessions,
    session: PIDE_MCP_Session,
    progress: Progress
  ): Unit =
    watchers.change_result(watchers => (watchers.get(session.id), watchers - session.id))
      .foreach { case (watcher, sync_progress) => sync_progress.stop(); watcher.shutdown() }
}

class Services_File_Watcher extends PIDE_MCP_Services(new Service_File_Watcher)
