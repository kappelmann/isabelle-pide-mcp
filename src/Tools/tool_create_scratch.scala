/*  Title:      PIDE_MCP/tool_create_scratch.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import java.io.{File => JFile}

class Tool_Create_Scratch extends PIDE_MCP_Tool("create_scratch") {
  def description: String =
    "Create a temporary file for experimentation that does not interfere with user accessible files. " +
      "Use this whenever you think you need to do iterative developments or when you want to find and explore theorems, syntax, concepts, commands, ML code, etc. Write back final results to files accessible to the user. " +
      "Temporary files are cleaned up when the session stops."

  private val name_suffix_arg = PIDE_MCP_Tool_Arg.opt_string(
    "name_suffix",
    "Label to identify the scratch file (auto-generated if omitted)")
  private val extension_arg = PIDE_MCP_Tool_Arg.opt_string(
    "extension",
    "File extension (typically \".thy\" or \".ML\")")

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, name_suffix_arg, extension_arg))

  private val scratch_prefix: String = "tmp_pide_mcp_scratch_"
  private val scratch_tmpdir_prefix: String = "tmp_pide_mcp_scratch"
  private val scratch_dirs = Synchronized[Map[String, JFile]](Map.empty)

  private def get_scratch_dir(session_id: String): JFile =
    scratch_dirs.change_result { dirs =>
      dirs.get(session_id) match {
        case Some(dir) if dir.isDirectory => (dir, dirs)
        case _ =>
          val dir = Isabelle_System.tmp_dir(scratch_tmpdir_prefix)
          (dir, dirs + (session_id -> dir))
      }
    }

  override def stop(
    sessions: PIDE_MCP_Sessions,
    session: PIDE_MCP_Session,
    progress: Progress
  ): Unit = {
    val dir = scratch_dirs.change_result(dirs => (dirs.get(session.id), dirs - session.id))
    dir.foreach(Isabelle_System.rm_tree)
  }

  def create_scratch(
    session_id: String,
    name_suffix: Option[String] = None,
    extension: Option[String] = None
  ): (Path, Boolean) = {
    val suffix = name_suffix.getOrElse(Date.now().format(Date.Format("yyyy_MM_dd_HH_mm_ss_SSS")))
    val base_name = scratch_prefix + suffix
    val file_name = extension match { case Some(ext) => base_name + ext case None => base_name }
    val file_path =
      (File.path(get_scratch_dir(session_id)) + Path.basic(file_name)).canonical
    (file_path, file_path.file.createNewFile())
  }

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      val name_suffix = name_suffix_arg.get(args)
      val extension = extension_arg.get(args)
      val (path, created) = create_scratch(session.id, name_suffix, extension)
      JSON_Object("path" -> path.implode,
        "message" -> (if (created) "File created" else "Path already existed"))
    }
}

class Tools_Create_Scratch extends PIDE_MCP_Tools(new Tool_Create_Scratch)
