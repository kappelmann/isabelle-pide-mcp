/*  Title:      PIDE_MCP/pide_session.scala
    Author:     Kevin Kappelmann

Embedded Isabelle/PIDE session lifecycle and theory operations.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls

import scala.collection.mutable
import scala.concurrent.{Await, Future, TimeoutException}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class PIDE_Session(session_name: String, dirs: List[Path] = Nil, val options: Options = Options.init())
{
  private val scratch_theory_prefix: String = "PIDE_MCP_Scratch_"
  private val scratch_tmpdir_prefix: String = "pide_mcp_scratch"

  private var _resources: Headless.Resources = _
  private var _session: Headless.Session = _
  private val scratch_dirs = mutable.Map[String, java.io.File]()

  def resources: Headless.Resources = _resources
  def session: Headless.Session = _session

  /* session lifecycle */

  def start(): Unit =
  {
    val opts = options + "show_states=true"
    _resources = Headless.Resources.make(opts, session_name, dirs)
    _session = _resources.start_session()
  }

  def stop(): Unit =
  {
    if (_session != null) _session.stop()
    scratch_dirs.values.foreach(Isabelle_System.rm_tree)
    scratch_dirs.clear()
  }

  /** Theories loaded in the session: static (from session build), dynamic (loaded at
    runtime via use_theories, excluding scratch), and scratch (temporary theories). */
  def loaded_theories: (List[Document.Node.Name], List[Document.Node.Name], List[Document.Node.Name]) =
  {
    val static_names = _session.resources.session_base.loaded_theories.keys.toSet
    val all_nodes = _session.get_state().stable_tip_version
      .map(_.nodes.topological_order)
      .getOrElse(Nil)
    val (scratch, non_scratch) = all_nodes.partition(_.theory.contains(scratch_theory_prefix))
    val (static, dynamic) = non_scratch.partition(n => static_names.contains(n.theory))
    (static, dynamic, scratch)
  }

  /* theory operations */

  def load_theory(path: Path, timeout_secs: Int = PIDE_Session.default_timeout_secs): Either[String, Unit] =
  {
    val abs_path = path.expand
    try {
      val future = Future {
        _session.use_theories(
          theories = List(abs_path.base.implode.stripSuffix(".thy")),
          master_dir = abs_path.dir.implode,
          progress = new Console_Progress)
      }
      Await.result(future, timeout_secs.seconds)
      Right(())
    } catch {
      case _: TimeoutException => Left(s"Timeout after ${timeout_secs}s loading theory: $path")
      case ex: Exception => Left(s"Failed to load theory: ${ex.getMessage}")
    }
  }

  def check_theory(
    theory: String,
    master_dir: String = "",
    timeout_secs: Int = PIDE_Session.default_timeout_secs
  ): Either[String, Headless.Use_Theories_Result] =
  {
    try {
      val future = Future {
        _session.use_theories(theories = List(theory), master_dir = master_dir, progress = new Console_Progress)
      }
      Right(Await.result(future, timeout_secs.seconds))
    } catch {
      case _: TimeoutException => Left(s"Timeout after ${timeout_secs}s checking theory: $theory")
      case ex: Exception => Left(s"Failed to check theory: ${ex.getMessage}")
    }
  }

  def snapshot(name: Document.Node.Name): Document.Snapshot =
    _session.get_state().snapshot(name)

  def node_name(path: Path): Document.Node.Name =
  {
    val abs = path.expand
    Document.Node.Name(abs.implode, theory = abs.base.implode.stripSuffix(".thy"))
  }

  def command_iterator(
    snap: Document.Snapshot,
    range: Option[Text.Range] = None
  ): Iterator[(Command, Text.Offset)] =
    range match {
      case Some(r) => snap.node.command_iterator(r)
      case None => snap.node.command_iterator()
    }

  def command_status(
    snap: Document.Snapshot,
    command: Command
  ): Document_Status.Command_Status =
    snap.state.command_status(snap.version, command)

  def create_scratch_theory(name_suffix: Option[String] = None): Either[String, (String, String)] =
  {
    val suffix = name_suffix.getOrElse(java.util.UUID.randomUUID().toString.replace("-", "").take(12))
    val theory_name = scratch_theory_prefix + suffix
    val tmp_dir = Isabelle_System.tmp_dir(scratch_tmpdir_prefix)
    try {
      val tmp_path = Path.explode(tmp_dir.toString)
      val thy_path = tmp_path + Path.basic(theory_name + ".thy")
      val thy_file = thy_path.implode

      val file_content =
        s"""theory $theory_name
           |imports Pure
           |begin
           |
           |
           |end""".stripMargin

      Isabelle_System.make_directory(tmp_path)
      scratch_dirs(theory_name) = tmp_dir
      File.write(thy_path, file_content)
      Right((theory_name, thy_file))
    } catch {
      case ex: Exception =>
        Isabelle_System.rm_tree(tmp_dir)
        scratch_dirs.remove(theory_name)
        Left(s"Failed to create scratch theory: ${ex.getMessage}")
    }
  }
}

object PIDE_Session
{
  val default_timeout_secs: Int = 15
}
