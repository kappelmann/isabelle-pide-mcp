/*  Title:      PIDE_MCP/PIDESession.scala
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


class PIDESession(session_name: String, dirs: List[Path] = Nil, val options: Options = Options.init())
{
  private var _resources: Headless.Resources = _
  private var _session: Headless.Session = _
  private val scratchDirs = mutable.Map[String, java.io.File]()  // theory_name -> tmpDir

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
    scratchDirs.values.foreach(Isabelle_System.rm_tree)
    scratchDirs.clear()
  }

  /** Theories currently loaded in the session (excludes scratch/temporary theories). */
  def loaded_theories: List[Document.Node.Name] =
    _session.purge_theories(theories = Nil)._2.filterNot(_.theory.contains(PIDESession.scratch_theory_prefix))

  /* theory operations */

  def open_theory(path: Path, timeoutSecs: Int = PIDESession.default_timeout_secs): Either[String, Unit] =
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
      Await.result(future, timeoutSecs.seconds)
      Right(())
    } catch {
      case _: TimeoutException => Left(s"Timeout after ${timeoutSecs}s loading theory: $path")
      case ex: Exception => Left(s"Failed to load theory: ${ex.getMessage}")
    }
  }

  def update_theory(
    theories: List[String],
    master_dir: String = "",
    timeoutSecs: Int = PIDESession.default_timeout_secs
  ): Either[String, Headless.Use_Theories_Result] =
  {
    try {
      val future = Future {
        _session.use_theories(theories = theories, master_dir = master_dir, progress = new Console_Progress)
      }
      Right(Await.result(future, timeoutSecs.seconds))
    } catch {
      case _: TimeoutException => Left(s"Timeout after ${timeoutSecs}s updating theories: ${theories.mkString(", ")}")
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

object PIDESession
{
  val scratch_theory_prefix = "MCP_Scratch_"
  val scratch_tmpdir_prefix = "mcp_scratch"
  val default_timeout_secs = 15

  /* output collection */

  def collectCommandOutput(snap: Document.Snapshot): String =
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

  def runQuery(
    pid: PIDESession,
    content: String,
    imports: String,
    timeoutSecs: Int = PIDESession.default_timeout_secs
  ): Either[String, (String, String, String)] =
  {
    val uid = java.util.UUID.randomUUID().toString.replace("-", "").take(12)
    val theoryName = scratch_theory_prefix + uid
    val tmpDir = Isabelle_System.tmp_dir(scratch_tmpdir_prefix)
    try {
      val tmpPath = Path.explode(tmpDir.toString)
      val thyPath = tmpPath + Path.basic(theoryName + ".thy")
      val thyFile = thyPath.implode

      val fileContent =
        s"""theory $theoryName
           |imports $imports
           |begin
           |$content
           |end""".stripMargin

      Isabelle_System.make_directory(tmpPath)
      File.write(thyPath, fileContent)
      val nodeName = Document.Node.Name(thyFile, theory = theoryName)
      pid.update_theory(List(theoryName), tmpDir.toString, timeoutSecs) match {
        case Right(result) =>
          val snap = result.snapshot(nodeName)
          val output = collectCommandOutput(snap)
          pid.scratchDirs(theoryName) = tmpDir
          if (output.nonEmpty) Right((output, theoryName, thyFile))
          else Left("No output produced")
        case Left(e) =>
          val snap = pid.snapshot(nodeName)
          val output = collectCommandOutput(snap)
          if (output.nonEmpty) {
            pid.scratchDirs(theoryName) = tmpDir
            Right((output, theoryName, thyFile))
          } else {
            Isabelle_System.rm_tree(tmpDir)
            Left(e)
          }
      }
    } catch {
      case ex: Exception =>
        Isabelle_System.rm_tree(tmpDir)
        Left(s"Query error: ${ex.getMessage}")
    }
  }
}
