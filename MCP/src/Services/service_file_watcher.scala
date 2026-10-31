/*  Title:      MCP/src/Services/service_file_watcher.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Service_File_Watcher extends PIDE_MCP_Service("file_watcher") {
  def description: String = "Synchronise disk changes of loaded files."

  private val watchers = Synchronized(
    Map.empty[String, (PIDE_MCP_File_Watcher[Document.Node.Name], Progress)])

  private def delay(options: Options): Time = options.seconds("pide_mcp_file_watcher_delay")

  private def loaded_files(session: PIDE_MCP_Session): () => List[Document.Node.Name] = {
    var cache = (Document.Version.init, List.empty[Document.Node.Name])
    () => {
      val version = session.snapshot().version
      if (cache._1 ne version) cache = (version, version.nodes.iterator.collect {
        case (name, node) if PIDE_MCP_Util.is_loaded_dynamic(node) => name
      }.toList)
      cache._2
    }
  }

  override def start(
    sessions: PIDE_MCP_Sessions,
    session: PIDE_MCP_Session,
    progress: Progress
  ): Unit = {
    val log = sessions.log
    val sync_progress = new Sub_Progress(sessions.base_progress)
    val watcher = new PIDE_MCP_File_Watcher[Document.Node.Name](
      session.id, loaded_files(session), _.path,
      changed => Exn.result { session.sync_resolve(changed, sync_progress) } match {
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
