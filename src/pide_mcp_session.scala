/*  Title:      PIDE_MCP/pide_mcp_session.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.annotation.tailrec

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
    val mcp_session = new PIDE_MCP_Session(dirs = dirs, session = session, tool_table = tool_table)
    try {
      mcp_session.tool_table.values.foreach(_.init(mcp_session))
      mcp_session
    } catch {
      case ex: Exception =>
        log("Error initializing tool: " + Exn.message(ex))
        mcp_session.stop()
        throw ex
    }
  }
}

class PIDE_MCP_Session private(
  val dirs: List[Path] = Nil,
  val session: Headless.Session,
  val tool_table: Map[String, PIDE_MCP_Tool]
) {

  def resources: Headless.Resources = session.resources

  def stop(): Unit = {
    tool_table.values.foreach { tool =>
      try tool.stop()
      catch { case ex: Exception =>
        resources.log("Error stopping tool " + tool.name + ": " + Exn.message(ex))
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
    resources.loaded_theory(node_name)

  def snapshot(): Document.Snapshot = session.snapshot()

  def await_stable_snapshot(): Document.Snapshot = session.await_stable_snapshot()

  def node_snapshot(node_name: Document.Node.Name): Exn.Result[Document.Snapshot] =
    switch(session.snapshot(), node_name)

  def switch(
    snapshot: => Document.Snapshot,
    node_name: Document.Node.Name
  ): Exn.Result[Document.Snapshot] = Exn.capture {
    if (is_base_session_theory(node_name)) session.read_theory(node_name.theory, unicode_symbols = true) // base session
    else {
      val snapshot1 = snapshot
      val new_snapshot =
        (if (is_base_session_theory(snapshot1.node_name)) session.snapshot() else snapshot1).switch(node_name)
      if (PIDE_MCP_Util.is_loaded_dynamic(new_snapshot.version.nodes, node_name)) new_snapshot // dynamic theory
      else error("No PIDE snapshot available for " + origin(node_name))
    }
  }

  def tip_version(): Exn.Result[Document.Version] =
    Exn.capture { session.get_state().history.tip.version.join }

  def read_file_content(node_name: Document.Node.Name): Exn.Result[String] =
    Exn.capture {
      resources.make_theory_content(node_name).getOrElse(
        Symbol.decode(Line.normalize(File.read(node_name.path))))
    }

  def write_file_content(path: Path, text: String): Exn.Result[Unit] =
    Exn.capture { File.write(path, Symbol.encode(Line.normalize(text))) }

  def node_source(
    snapshot: Document.Snapshot,
    node_name: Document.Node.Name
  ): Exn.Result[String] =
    switch(snapshot, node_name) match {
      case Exn.Res(new_snapshot) => Exn.Res(new_snapshot.node.source)
      case Exn.Exn(_) => read_file_content(node_name)
    }

  def update(edits: List[Document.Edit_Text]): Unit =
    if (edits.nonEmpty) {
      val blobs = for (case (name, Document.Node.Blob(blob)) <- edits) yield name -> blob
      session.update(Document.Blobs(blobs.toMap), edits)
    }

  private def replace_edits(old_text: String, new_text: String): List[Text.Edit] = {
    val prefix = old_text.iterator.zip(new_text.iterator).takeWhile(_ == _).length
    val (old_rest, new_rest) = (old_text.drop(prefix), new_text.drop(prefix))
    val suffix = old_rest.reverseIterator.zip(new_rest.reverseIterator).takeWhile(_ == _).length
    Text.Edit.replace(prefix, old_rest.dropRight(suffix), new_rest.dropRight(suffix))
  }

  private case class Node_Model(
    node_name: Document.Node.Name,
    node: Document.Node,
    text: String,
    text_perspective: Text.Perspective
  ) extends Document.Model {
    def session: Document.Session = PIDE_MCP_Session.this.session
    def node_required: Boolean = text.nonEmpty
    def untyped_data: AnyRef = File_Format.registry.parse_data(node_name, text)

    lazy val pending_edits: List[Text.Edit] = replace_edits(node.source, text)
    def is_stable: Boolean = pending_edits.isEmpty

    def get_text(range: Text.Range): Option[String] = range.try_substring(text)

    def get_blob: Option[Document.Blobs.Item] =
      if (is_theory) None
      else Some(Document.Blobs.Item(
        Bytes(Symbol.encode(text)), text, Symbol.Text_Chunk(text), changed = !is_stable))

    def node_header: Document.Node.Header =
      resources.special_header(node_name).getOrElse(
        resources.check_thy(node_name, Scan.char_reader(text)))

    def node_perspective: Document.Node.Perspective_Text.T =
      if (is_theory)
        Document.Node.Perspective(node_required, text_perspective, node.perspective.overlays)
      else Document.Node.Perspective_Text.empty

    def edits: List[Document.Edit_Text] =
      if (is_stable && node.edit_perspective == node_perspective) Nil
      else node_edits(node_header, pending_edits, node_perspective)
  }

  private def hide_edits(
    version: Document.Version,
    keep: Set[Document.Node.Name]
  ): Iterator[Document.Edit_Text] =
    for {
      (name, node) <- version.nodes.iterator
      if !keep(name) && !node.text_perspective.is_empty
    } yield name -> Document.Node.Perspective(
      node.perspective.required, Text.Perspective.empty, node.perspective.overlays)

  def read_update(
    nodes: List[(Document.Node.Name, Text.Perspective)],
    hide_others: Boolean
  ): Exn.Result[Map[Document.Node.Name, String]] = Exn.capture {
    val models = synchronized {
      val version = Exn.release(tip_version())
      val models1 = for ((name, text_perspective) <- nodes)
        yield Node_Model(
          name, version.nodes(name), Exn.release(read_file_content(name)), text_perspective)
      val other_edits =
        if (hide_others) hide_edits(version, nodes.map { case (name, _) => name }.toSet)
        else Iterator.empty
      update((other_edits ++ models1.iterator.flatMap(_.edits)).toList)
      models1
    }
    (for (model <- models) yield model.node_name -> model.text).toMap
  }

  private def required_nodes(
    version: Document.Version,
    seen: Set[Document.Node.Name]
  ): Set[Document.Node.Name] = {
    def is_required(name: Document.Node.Name): Boolean =
      !seen(name) && !is_base_session_theory(name) &&
      !PIDE_MCP_Util.is_loaded_dynamic(version.nodes, name) &&
      (name.path.is_file || resources.make_theory_content(name).isDefined)
    val thy_files = version.nodes.iterator.flatMap { case (name, node) =>
        node.header.imports.iterator ++ resources.make_theory_name(name).iterator
      }.distinct.filter(is_required)
    val deps = resources.dependencies(thy_files.map((_, Position.none)).toList)
    val dep_files = try deps.loaded_files catch { case ERROR(_) => Nil }
    val aux_files = resources.undefined_blobs(version)
    (deps.theories ++ dep_files ++ aux_files).toSet.filter(is_required)
  }

  def resolve_dependencies(): Unit = {
    @tailrec def loop(seen: Set[Document.Node.Name]): Unit = {
      val names = required_nodes(Exn.release(tip_version()), seen)
      if (names.nonEmpty) {
        Exn.release(read_update(
          (for (name <- names) yield name -> Text.Perspective.empty).toList, hide_others = false))
        loop(seen ++ names)
      }
    }
    loop(Set.empty)
  }

  def read_update_resolve(
    node_name: Document.Node.Name,
    text_perspective: Text.Perspective,
    await_stable_before_resolve: Boolean,
    hide_others: Boolean
  ): Exn.Result[String] = Exn.capture {
    if (is_base_session_theory(node_name)) Exn.release(node_snapshot(node_name)).node.source
    else {
      val text =
        Exn.release(read_update(List(node_name -> text_perspective), hide_others))(node_name)
      if (await_stable_before_resolve) await_stable_snapshot()
      resolve_dependencies()
      text
    }
  }

  private def unload_edits(
    node_name: Document.Node.Name,
    node: Document.Node
  ): List[Document.Edit_Text] = {
    val model = Node_Model(node_name, node, "", Text.Perspective.empty)
    model.node_edits(
      Document.Node.no_header, model.pending_edits, Document.Node.Perspective_Text.empty)
  }

  def unload(node_names: List[Document.Node.Name]): Exn.Result[List[Document.Node.Name]] =
    Exn.capture {
      for (name <- node_names if is_base_session_theory(name))
        error("Cannot unload base session theory " + origin(name))
      synchronized {
        val nodes = Exn.release(tip_version()).nodes
        val descendants = nodes.descendants(node_names).filter(PIDE_MCP_Util.is_loaded_dynamic(nodes, _))
        update(descendants.flatMap(name => unload_edits(name, nodes(name))))
        descendants
      }
    }
}
