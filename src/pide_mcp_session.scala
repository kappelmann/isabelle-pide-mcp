/*  Title:      PIDE_MCP/pide_session.scala
    Author:     Kevin Kappelmann

PIDE MCP session and resource operations.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls
import scala.collection.mutable


class PIDE_MCP_Session(session_name: String, dirs: List[Path] = Nil, val options: Options = Options.init()) {
  private val scratch_theory_prefix: String = "PIDE_MCP_Scratch_"
  private val scratch_tmpdir_prefix: String = "pide_mcp_scratch"
  private val session_id: java.util.UUID = java.util.UUID.randomUUID

  private var _resources: Headless.Resources = _
  private var _session: Headless.Session = _
  private val scratch_dirs = mutable.Map[String, java.io.File]()

  def resources: Headless.Resources = _resources
  def session: Headless.Session = _session

  def start(): Unit = {
    val opts = options + "show_states=true" + "show_results=true"

    Build.build_logic(opts, session_name, build_heap = true, progress = new Progress, dirs = dirs).check

    _resources = Headless.Resources.make(opts, session_name, log = Logger.none, session_dirs = dirs)
    _session = _resources.start_session()
  }

  def stop(): Unit = {
    if (_session != null) _session.stop()
    scratch_dirs.synchronized { scratch_dirs.values.toList }.foreach(Isabelle_System.rm_tree)
    scratch_dirs.clear()
  }

  def node_name(path: Path): Document.Node.Name = {
    val abs = path.expand
    val base = abs.base.implode
    Document.Node.Name(abs.implode,
      theory = if (base.endsWith(PIDE_MCP_Util.theory_suffix)) PIDE_MCP_Util.strip_theory_suffix(base) else "")
  }

  def snapshot(name: Document.Node.Name): Document.Snapshot =
    _session.get_state().snapshot(name)

  def command_status(
    snap: Document.Snapshot,
    command: Command
  ): Document_Status.Command_Status =
    snap.state.command_status(snap.version, command)

  def loaded_theories
    : (List[Document.Node.Name], List[Document.Node.Name], List[Document.Node.Name]) = {
    val static_names = _session.resources.session_base.loaded_theories.keys.toSet
    val all_nodes = _session.get_state().history.tip.version.join.nodes.topological_order
    val (scratch, non_scratch) = all_nodes.partition(_.theory.contains(scratch_theory_prefix))
    val (static, dynamic) = non_scratch.partition(n => static_names.contains(n.theory))
    (static, dynamic, scratch)
  }

  private def load(
    theories: List[Document.Node.Name] = Nil,
    files: List[Document.Node.Name] = Nil
  ): Exn.Result[Unit] =
    Exn.capture {
      resources.load_theories(
        session = _session,
        id = session_id,
        theories = theories,
        files = files,
        unicode_symbols = false,
        progress = new Progress)
    }

  def load_theory(name: Document.Node.Name, reload: Boolean = true): Exn.Result[Unit] =
    if (reload) load(theories = List(name))
    else
      Exn.capture {
        val (static, dynamic, scratch) = loaded_theories
        val loaded = (static ::: dynamic ::: scratch).exists(_.theory == name.theory)
        if (!loaded) Exn.release(load(theories = List(name)))
      }

  def load_file(name: Document.Node.Name): Exn.Result[Unit] = load(files = List(name))

  def read(name: Document.Node.Name): Exn.Result[String] =
    Exn.capture {
      if (name.is_theory) Exn.release(load_theory(name))
      else Exn.release(load_file(name))
      File.read(name.path)
    }

  def create_scratch_theory(
    name_suffix: Option[String] = None,
    imports: List[String]
  ): Exn.Result[Path] =
  {
    val suffix = name_suffix.getOrElse(java.util.UUID.randomUUID().toString.replace("-", "").take(12))
    val theory_name = scratch_theory_prefix + suffix
    val tmp_dir = Isabelle_System.tmp_dir(scratch_tmpdir_prefix)
    Exn.capture {
      val theory_path = File.path(tmp_dir) + Path.basic(theory_name).thy
      val imports_line = imports.mkString("imports ", " ", "")
      val file_content =
        s"""theory $theory_name
           |$imports_line
           |begin
           |
           |
           |
           |end""".stripMargin
      File.write(theory_path, file_content)
      scratch_dirs(theory_name) = tmp_dir
      Exn.release(load_theory(node_name(theory_path)))
      theory_path
    } match {
      case Exn.Res(path) => Exn.Res(path)
      case Exn.Exn(ex) =>
        Isabelle_System.rm_tree(tmp_dir)
        scratch_dirs.remove(theory_name)
        Exn.Exn(ex)
    }
  }

  private def compute_file_edit(
    current_text: String,
    content: String,
    start_line: Option[Int],
    end_line: Option[Int],
    old_content: String
  ): Exn.Result[String] =
    Exn.capture {
      val lines = Library.split_lines(current_text)
      val (s, e) = Exn.release(PIDE_MCP_Util.resolve_lines(start_line, end_line, lines.length))
      val start_idx = s - 1
      val actual_old = lines.slice(start_idx, e).mkString("\n")
      if (actual_old != old_content)
        error(s"old_content mismatch at lines $s-$e.\nExpected:\n$old_content\nActual:\n$actual_old")
      val prefix = lines.take(start_idx)
      val suffix = lines.drop(e)
      (prefix ++ Library.split_lines(content) ++ suffix).mkString("\n")
    }

  private def edit_file(
    path: Path,
    content: String,
    start_line: Option[Int],
    end_line: Option[Int],
    old_content: String
  ): Exn.Result[(String, Boolean)] =
    Exn.capture {
      val disk_text = File.read(path)
      val new_text = Exn.release(compute_file_edit(disk_text, content, start_line, end_line, old_content))
      val changed = new_text != disk_text
      if (changed) File.write(path, new_text)
      (new_text, changed)
    }

  def edit_load_file(
    path: Path,
    content: String,
    start_line: Option[Int],
    end_line: Option[Int],
    old_content: String
  ): Exn.Result[(String, Boolean)] = {
    val name = node_name(path)
    Exn.capture {
      val (new_text, changed) = Exn.release(edit_file(path, content, start_line, end_line, old_content))
      if (changed) {
        if (name.is_theory) Exn.release(load_theory(name))
        else Exn.release(load_file(name))
      }
      (new_text, changed)
    }
  }

}
