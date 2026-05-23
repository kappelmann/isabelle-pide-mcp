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
  private var _resources: Headless.Resources = _
  private var _session: Headless.Session = _
  private val scratch_dirs = mutable.Map[String, java.io.File]()  // theory_name -> tmpDir

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
    // Clean up all scratch directories
    scratch_dirs.values.foreach(Isabelle_System.rm_tree)
    scratch_dirs.clear()
  }

  /** Theories currently loaded in the session (excludes scratch/temporary theories). */
  def loaded_theories: List[Document.Node.Name] =
    _session.purge_theories(theories = Nil)._2.filterNot(_.theory.contains(PIDE_Session.scratch_theory_prefix))

  /* theory operations */

  def open_theory(path: Path, timeout_secs: Int = PIDE_Session.default_timeout_secs): Either[String, Unit] =
  {
    val abs_path = path.expand
    if (!abs_path.is_file) return Left(s"File not found: $path")
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

  def update_theory(
    theories: List[String],
    master_dir: String = "",
    timeout_secs: Int = PIDE_Session.default_timeout_secs
  ): Either[String, Headless.Use_Theories_Result] =
  {
    try {
      val future = Future {
        _session.use_theories(theories = theories, master_dir = master_dir, progress = new Console_Progress)
      }
      Right(Await.result(future, timeout_secs.seconds))
    } catch {
      case _: TimeoutException => Left(s"Timeout after ${timeout_secs}s updating theories: ${theories.mkString(", ")}")
      case ex: Exception => Left(s"Failed to update theory: ${ex.getMessage}")
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
}

object PIDE_Session
{
  val scratch_theory_prefix = "MCP_Scratch_"
  val scratch_tmpdir_prefix = "mcp_scratch"
  val default_timeout_secs = 15

  /* output collection */

  def collect_command_output(snap: Document.Snapshot): String =
  {
    val buf = new StringBuilder
    for ((cmd, _) <- snap.node.command_iterator()) {
      for ((_, elem) <- snap.command_results(cmd).iterator) {
        val text = elem match {
          case XML.Elem(Markup(Markup.WRITELN, _), body) => XML.content(body)
          case XML.Elem(Markup(Markup.WRITELN_MESSAGE, _), body) => XML.content(body)
          case XML.Elem(Markup(Markup.WARNING, _), body) => "Warning: " + XML.content(body)
          case XML.Elem(Markup(Markup.WARNING_MESSAGE, _), body) => "Warning: " + XML.content(body)
          case XML.Elem(Markup(Markup.ERROR, _), body) => "Error: " + XML.content(body)
          case XML.Elem(Markup(Markup.ERROR_MESSAGE, _), body) => "Error: " + XML.content(body)
          case _ => ""
        }
        if (text.nonEmpty) { buf ++= text; buf += '\n' }
      }
    }
    buf.toString.trim
  }

  /* scratch theory execution */

  def run_query(
    pid: PIDE_Session,
    content: String,
    imports: String,
    timeout_secs: Int = PIDE_Session.default_timeout_secs
  ): Either[String, (String, String, String)] =
  {
    val uid = java.util.UUID.randomUUID().toString.replace("-", "").take(12)
    val theory_name = scratch_theory_prefix + uid
    val tmp_dir = Isabelle_System.tmp_dir(scratch_tmpdir_prefix)
    try {
      val tmp_path = Path.explode(tmp_dir.toString)
      val thy_path = tmp_path + Path.basic(theory_name + ".thy")
      val thy_file = thy_path.implode

      val file_content =
        s"""theory $theory_name
           |imports $imports
           |begin
           |$content
           |end""".stripMargin

      Isabelle_System.make_directory(tmp_path)
      File.write(thy_path, file_content)
      val node_name = Document.Node.Name(thy_file, theory = theory_name)
      pid.update_theory(List(theory_name), tmp_dir.toString, timeout_secs) match {
        case Right(result) =>
          val snap = result.snapshot(node_name)
          val output = collect_command_output(snap)
          pid.scratch_dirs(theory_name) = tmp_dir
          if (output.nonEmpty) Right((output, theory_name, thy_file))
          else Left("No output produced")
        case Left(e) =>
          val snap = pid.snapshot(node_name)
          val output = collect_command_output(snap)
          if (output.nonEmpty) {
            pid.scratch_dirs(theory_name) = tmp_dir
            Right((output, theory_name, thy_file))
          } else {
            Isabelle_System.rm_tree(tmp_dir)
            Left(e)
          }
      }
    } catch {
      case ex: Exception =>
        Isabelle_System.rm_tree(tmp_dir)
        Left(s"Query error: ${ex.getMessage}")
    }
  }
}
