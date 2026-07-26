/*  Title:      PIDE_MCP/pide_mcp_session.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Session {
  def apply(
    session_name: String,
    tool_table: Map[String, PIDE_MCP_Tool],
    log: Logger,
    dirs: List[Path] = Nil,
    options: Options = Options.init(),
    session_ancestor: Option[String] = None,
    session_requirements: Boolean = false,
    fresh_build: Boolean = false,
    build_progress: Progress = new Progress,
  ): Exn.Result[PIDE_MCP_Session] = Exn.capture {
    val opts = options + "show_states=true" + "show_results=true"
    val session_background = Sessions.background(opts, session_name, dirs = dirs,
      session_ancestor = session_ancestor, session_requirements = session_requirements).check_errors
    Build.build(opts, selection = Sessions.Selection.session(session_background.session_name),
      build_heap = true, dirs = dirs, infos = session_background.infos,
      fresh_build = fresh_build, progress = build_progress).check
    val resources = Headless.Resources(opts, session_background, log)
    val session = resources.start_session()
    val mcp_session = new PIDE_MCP_Session(dirs = dirs, resources = resources, session = session,
      tool_table = tool_table)
    try {
      mcp_session.tool_table.values.foreach(_.init(mcp_session))
      mcp_session
    } catch {
      case ex: Exception =>
        log.error_message("Error initializing tool: " + Exn.message(ex))
        mcp_session.stop()
        throw ex
    }
  }
}

class PIDE_MCP_Session private(
  val dirs: List[Path] = Nil,
  val resources: Headless.Resources,
  val session: Headless.Session,
  val tool_table: Map[String, PIDE_MCP_Tool]
) {
  private val session_id: UUID.T = UUID.random()

  def stop(): Unit = {
    tool_table.values.foreach { tool =>
      try tool.stop()
      catch { case ex: Exception =>
        resources.log.error_message("Error stopping tool " + tool.name + ": " + Exn.message(ex)) 
      }
    }
    session.stop()
  }

  private def path_node_name(path: Path): Exn.Result[Document.Node.Name] = Exn.capture {
    val abs = PIDE_MCP_Util.canonical_path(path)
    resources.find_theory(abs.file).getOrElse { // session theories
      val base = abs.base.implode
      val candidate = Document.Node.Name(abs.implode, // full path file
        theory = if (base.endsWith(PIDE_MCP_Util.theory_suffix)) PIDE_MCP_Util.strip_theory_suffix(base) else "")
      if (candidate.path.is_file) candidate
      else session.store.source_file(path.implode) match { // source_file can return identity for unknown files
        case Some(file) if Path.explode(file).is_file => Exn.release(node_name(file))
        case _ => error("Path " + path.implode + " cannot be resolved: it is neither a file on disk nor resolvable by PIDE.")
      }
    }
  }

  def node_name(s: String): Exn.Result[Document.Node.Name] = Exn.capture {
    resources.find_theory_node(s).getOrElse(Exn.release(path_node_name(Path.explode(s))))
  }

  def origin(node_name: Document.Node.Name): String =
    if (node_name.is_theory) {
      resources.find_theory_node(node_name.theory) match {
        case Some(_) => node_name.theory // session-qualified
        case None => node_name.node // full path
      }
    } else node_name.node // full path

  def is_base_session_theory(node_name: Document.Node.Name): Boolean =
    resources.session_base.known_theories.contains(node_name.theory)

  def node_snapshot(node_name: Document.Node.Name): Exn.Result[Document.Snapshot] = Exn.capture {
    if (is_base_session_theory(node_name)) session.read_theory(node_name.theory, unicode_symbols = true) // base session
    else {
      val snapshot = session.get_state().snapshot(node_name)
      if (PIDE_MCP_Util.is_loaded_theory(snapshot, node_name)) snapshot // dynamic theory
      else error("No PIDE snapshot available for " + origin(node_name))
    }
  }

  private def do_load(
    theories: List[Document.Node.Name] = Nil,
    files: List[Document.Node.Name] = Nil
  ): Exn.Result[Unit] =
    Exn.capture {
      resources.load_theories(
        session = session, id = session_id,
        theories = theories, files = files,
        unicode_symbols = true, progress = new Progress)
    }

  def load_theory(node_name: Document.Node.Name): Exn.Result[Unit] = Exn.capture {
    node_snapshot(node_name) match {
      case Exn.Res(_) => () // only load if necessary
      case Exn.Exn(_) =>
        val deps = resources.dependencies(List(node_name -> Position.none)).check_errors
        Exn.release(do_load(theories = deps.theories, files = deps.loaded_files))
    }
  }

  def load_file(node_name: Document.Node.Name): Exn.Result[Unit] = do_load(files = List(node_name))

  def load(node_name: Document.Node.Name): Exn.Result[Unit] =
    if (node_name.is_theory) load_theory(node_name) else load_file(node_name)

  def unload(node_names: List[Document.Node.Name]): Exn.Result[List[Document.Node.Name]] =
    Exn.capture {
      val snapshot = session.snapshot()
      val dependents = snapshot.version.nodes.descendants(node_names)
        .filter(name => PIDE_MCP_Util.is_loaded_theory(snapshot, name))
      resources.clean_theories(session = session, id = session_id, theories = dependents)
      dependents
    }

  def text_edits(
    node_name: Document.Node.Name,
    edits: List[Text.Edit],
    full_text: String
  ): Unit = {
    val node_header = resources.check_thy(node_name, Scan.char_reader(full_text))
    for (imp <- node_header.imports) Exn.release(load_theory(imp))
    session.update(Document.Blobs.empty, List(
      node_name -> Document.Node.Deps(node_header),
      node_name -> Document.Node.Edits(edits),
      node_name -> Document.Node.Perspective(true, Text.Perspective.full, Document.Node.Overlays.empty)))
  }

  def read_load_file(node_name: Document.Node.Name): Exn.Result[String] =
    Exn.capture {
      val text = Symbol.decode(File.read(node_name.path))
      Exn.release(load_file(node_name))
      text
    }

  def read_load_theory(node_name: Document.Node.Name): Exn.Result[String] =
    Exn.capture {
      node_snapshot(node_name) match {
        case Exn.Res(snapshot) =>
          val snapshot_text = snapshot.node.source
          if (is_base_session_theory(node_name)) snapshot_text
          else {
            val text = Symbol.decode(File.read(node_name.path))
            if (text != snapshot_text) {
              val prefix_len = snapshot_text.iterator.zip(text.iterator).takeWhile(_ == _).length
              val snapshot_after = snapshot_text.drop(prefix_len)
              val text_after = text.drop(prefix_len)
              val suffix_len =
                snapshot_after.reverseIterator.zip(text_after.reverseIterator).takeWhile(_ == _).length
              val edits = Text.Edit.replace(prefix_len, snapshot_after.dropRight(suffix_len),
                text_after.dropRight(suffix_len))
              text_edits(node_name, edits, text)
            }
            text
          }
        case Exn.Exn(_) =>
          Exn.release(load_theory(node_name))
          Symbol.decode(File.read(node_name.path))
      }
    }

  def read_load(node_name: Document.Node.Name): Exn.Result[String] =
    if (node_name.is_theory) read_load_theory(node_name) else read_load_file(node_name)

}
