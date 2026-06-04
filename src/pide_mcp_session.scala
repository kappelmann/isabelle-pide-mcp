/*  Title:      PIDE_MCP/pide_mcp_session.scala
    Author:     Kevin Kappelmann

PIDE MCP session and resource operations.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls
import scala.collection.mutable
import java.io.{File => JFile}


class PIDE_MCP_Session(
  session_name: String,
  private val log: Logger,
  dirs: List[Path] = Nil,
  private val options: Options = Options.init(),
  session_ancestor: Option[String] = None,
  session_requirements: Boolean = false,
  fresh_build: Boolean = false,
) {
  private val scratch_prefix: String = "tmp_pide_mcp_scratch_"
  private val scratch_tmpdir_prefix: String = "tmp_pide_mcp_scratch"
  private val session_id: UUID.T = UUID.random()

  private var resources: Headless.Resources = _
  private var session: Headless.Session = _
  private val scratch_dirs = mutable.Map[String, JFile]()

  def start(build_progress: Progress = new Progress): Exn.Result[Unit] = Exn.capture {
    val opts = options + "show_states=true" + "show_results=true"
    val session_background = Sessions.background(opts, session_name, dirs = dirs,
      session_ancestor = session_ancestor, session_requirements = session_requirements).check_errors
    Build.build(opts, selection = Sessions.Selection.session(session_background.session_name),
      build_heap = true, dirs = dirs, infos = session_background.infos,
      fresh_build = fresh_build, progress = build_progress).check
    resources = Headless.Resources(opts, session_background, log)
    session = resources.start_session()
  }

  def stop(): Unit = {
    if (session != null) session.stop()
    scratch_dirs.values.foreach(Isabelle_System.rm_tree)
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
      if (PIDE_MCP_Util.node_defined(snapshot)) snapshot // dynamic theory
      else error("No PIDE snapshot available for " + origin(node_name))
    }
  }

  def loaded_theories(snapshot: Option[Document.Snapshot] = None)
    : (List[Document.Node.Name], List[Document.Node.Name], List[Document.Node.Name]) = {
    val all_nodes = snapshot.getOrElse(session.snapshot()).version.nodes.topological_order
    val (scratch, non_scratch) = all_nodes.partition(_.theory.contains(scratch_prefix)) // FIXME: not 100% reliable
    val base_session_names = resources.session_base.loaded_theories.keys.toSet
    val (base_session, dynamic) = non_scratch.partition(n => base_session_names.contains(n.theory))
    (base_session, dynamic, scratch)
  }

  def session_directories: List[Path] =
    Sessions.directories(dirs, Nil).map(p => PIDE_MCP_Util.canonical_path(p._2))

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

  private def text_edit(
    node_name: Document.Node.Name,
    offset: Int,
    old_content: String,
    new_content: String,
    full_text: String
  ): Unit = {
    val node_header = resources.check_thy(node_name, Scan.char_reader(full_text))
    for (imp <- node_header.imports) Exn.release(load_theory(imp))
    val text_edits = Text.Edit.replace(offset, old_content, new_content)
    session.update(Document.Blobs.empty, List(
      node_name -> Document.Node.Deps(node_header),
      node_name -> Document.Node.Edits(text_edits),
      node_name -> Document.Node.Perspective(true, Text.Perspective.full, Document.Node.Overlays.empty)))
  }

  def read_file(node_name: Document.Node.Name): Exn.Result[String] = Exn.capture {
    val text = Symbol.decode(File.read(node_name.path))
    Exn.release(load_file(node_name))
    text
  }

  def read_theory(node_name: Document.Node.Name): Exn.Result[String] = Exn.capture {
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
            text_edit(node_name, prefix_len,
              snapshot_after.dropRight(suffix_len),
              text_after.dropRight(suffix_len),
              text)
          }
          text
        }
      case Exn.Exn(_) =>
        Exn.release(load_theory(node_name))
        Symbol.decode(File.read(node_name.path))
    }
  }

  def read(node_name: Document.Node.Name): Exn.Result[String] =
    if (node_name.is_theory) read_theory(node_name) else read_file(node_name)

  private def compute_edit(
    lines: List[String],
    new_text: String,
    start_line: Int,
    end_line: Int,
    old_text: String
  ): String = {
    val start_idx = start_line - 1
    val actual_old = lines.slice(start_idx, end_line).mkString("\n") + (if (end_line < lines.length) "\n" else "")
    if (actual_old.stripTrailing != old_text.stripTrailing)
      error(s"old_text mismatch at lines $start_line-$end_line.\nExpected:\n\"$old_text\"\nActual:\n\"$actual_old\"")
    val prefix = lines.take(start_idx)
    val suffix = lines.drop(end_line)
    (prefix ++ Library.split_lines(new_text) ++ suffix).mkString("\n")
  }

  def read_edit(
    node_name: Document.Node.Name,
    new_text: String,
    start_line: Option[Int],
    end_line: Option[Int],
    old_text: String
  ): Exn.Result[(String, Boolean)] = Exn.capture {
    if (is_base_session_theory(node_name))
      error("Cannot edit base session theory " + origin(node_name))
    val current_text = Exn.release(read(node_name))
    val doc = Line.Document(current_text)
    val (s, e) = Exn.release(PIDE_MCP_Util.resolve_lines(start_line, end_line, doc.lines.length))
    val computed_text = compute_edit(doc.lines.map(_.text), new_text, s, e, old_text)
    val write = computed_text != current_text
    if (write) {
      File.write(node_name.path, Symbol.encode(computed_text))
      if (node_name.is_theory) {
        val offset = doc.offset(Line.Position(line = s - 1)).get
        text_edit(node_name, offset, old_text, new_text, computed_text)
      } else Exn.release(load_file(node_name))
    }
    (computed_text, write)
  }

  def create_file(path: Path): Exn.Result[Boolean] = Exn.capture {
    val abs_path = PIDE_MCP_Util.canonical_path(path)
    if (abs_path.file.isDirectory) error("Path " + abs_path.implode + " is an existing directory.")
    abs_path.file.getParentFile.mkdirs()
    if (!abs_path.file.exists) { File.write(abs_path, ""); true }
    else false
  }

  def create_scratch_theory(
    name_suffix: Option[String] = None,
    extension: Option[String] = None
  ): Exn.Result[Path] =
  {
    val suffix = name_suffix.getOrElse(UUID.random_string().filterNot(_ == '-').take(12))
    val base_name = scratch_prefix + suffix
    val file_name = extension match { case Some(ext) => base_name + ext case None => base_name }
    val tmp_dir = Isabelle_System.tmp_dir(scratch_tmpdir_prefix)
    Exn.capture {
      val file_path = PIDE_MCP_Util.canonical_path(File.path(tmp_dir) + Path.basic(file_name))
      File.write(file_path, "")
      scratch_dirs(file_name) = tmp_dir
      file_path
    } match {
      case Exn.Res(path) => Exn.Res(path)
      case Exn.Exn(ex) =>
        Isabelle_System.rm_tree(tmp_dir)
        scratch_dirs.remove(file_name)
        Exn.Exn(ex)
    }
  }

}
