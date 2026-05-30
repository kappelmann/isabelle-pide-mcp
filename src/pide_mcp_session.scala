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
  val log: Logger,
  dirs: List[Path] = Nil,
  val options: Options = Options.init()
) {
  private val scratch_theory_prefix: String = "PIDE_MCP_Scratch_"
  private val scratch_tmpdir_prefix: String = "pide_mcp_scratch"
  private val session_id: UUID.T = UUID.random()

  private var resources: Headless.Resources = _
  private var session: Headless.Session = _
  private val scratch_dirs = mutable.Map[String, JFile]()

  def start(build_progress: Progress = new Progress): Unit = {
    val opts = options + "show_states=true" + "show_results=true"

    Build.build_logic(opts, session_name, build_heap = true, progress = build_progress, dirs = dirs).check

    resources = Headless.Resources.make(opts, session_name, log = log, session_dirs = dirs)
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
    if (is_base_session_theory(node_name)) session.read_theory(node_name.theory) // base session
    else {
      val snapshot = session.get_state().snapshot(node_name)
      if (PIDE_MCP_Util.node_defined(snapshot)) snapshot // dynamic theory
      else error("No PIDE snapshot available for " + origin(node_name))
    }
  }

  def loaded_theories(snapshot: Option[Document.Snapshot] = None)
    : (List[Document.Node.Name], List[Document.Node.Name], List[Document.Node.Name]) = {
    val all_nodes = snapshot.getOrElse(session.snapshot()).version.nodes.topological_order
    val (scratch, non_scratch) = all_nodes.partition(_.theory.contains(scratch_theory_prefix))
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

  private def load_file(node_name: Document.Node.Name): Exn.Result[Unit] = do_load(files = List(node_name))

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
    start_line: Option[Int],
    end_line: Option[Int],
    old_text: String
  ): Exn.Result[String] = Exn.capture {
    val (s, e) = Exn.release(PIDE_MCP_Util.resolve_lines(start_line, end_line, lines.length))
    val start_idx = s - 1
    val actual_old = lines.slice(start_idx, e).mkString("\n") + (if (e < lines.length) "\n" else "")
    val sep = "\""
    if (actual_old.stripTrailing != old_text.stripTrailing)
      error(s"old_text mismatch at lines $s-$e.\nExpected:\n$sep$old_text$sep\nActual:\n$sep$actual_old$sep")
    val prefix = lines.take(start_idx)
    val suffix = lines.drop(e)
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
    val computed_text = Exn.release(compute_edit(doc.lines.map(_.text), new_text, start_line, end_line, old_text))
    val write = computed_text != current_text
    if (write) {
      File.write(node_name.path, Symbol.encode(computed_text))
      if (node_name.is_theory) {
        val (s, e) = Exn.release(PIDE_MCP_Util.resolve_lines(start_line, end_line, doc.lines.length))
        val offset = doc.offset(Line.Position(line = s - 1)).get
        text_edit(node_name, offset, old_text, new_text, computed_text)
      } else Exn.release(load_file(node_name))
    }
    (computed_text, write)
  }

  def create_scratch_theory(
    name_suffix: Option[String] = None,
    imports: List[String]
  ): Exn.Result[Document.Node.Name] =
  {
    val suffix = name_suffix.getOrElse(UUID.random_string().filterNot(_ == '-').take(12))
    val theory_name = scratch_theory_prefix + suffix
    val tmp_dir = Isabelle_System.tmp_dir(scratch_tmpdir_prefix)
    Exn.capture {
      val theory_path = File.path(tmp_dir) + Path.basic(theory_name).thy
      val abs = PIDE_MCP_Util.canonical_path(theory_path)
      val node_name = Document.Node.Name(abs.implode, theory = theory_name)
      val imports_line = imports.mkString("imports ", " ", "")
      val file_content =
        s"""theory $theory_name
           |  $imports_line
           |begin
           |
           |
           |
           |end""".stripMargin
      File.write(node_name.path, Symbol.encode(file_content))
      scratch_dirs(theory_name) = tmp_dir
      Exn.release(load_theory(node_name))
      node_name
    } match {
      case Exn.Res(node_name) => Exn.Res(node_name)
      case Exn.Exn(ex) =>
        Isabelle_System.rm_tree(tmp_dir)
        scratch_dirs.remove(theory_name)
        Exn.Exn(ex)
    }
  }

}
