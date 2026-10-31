/*  Title:      MCP/src/Tools/tool_create_scratch.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import java.io.{File => JFile}

class Tool_Create_Scratch extends PIDE_MCP_Tool("create_scratch") {
  def description: String =
    "Create a temporary file for experimentation. " +
    "Files are cleaned up on session stop."

  private val name_suffix_arg = JSON_Schema.Arg.optional(
    "name_suffix", "Label to identify the file. Default: auto-generated.",
    JSON_Schema.Format.string)
  private val extension_arg = JSON_Schema.Arg.default(
    "ext", "File extension.", JSON_Schema.Format.string, ".thy")

  def input_schema: JSON.Object.T = JSON_Schema.object_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, name_suffix_arg, extension_arg))

  private val scratch_prefix: String = "Scratch_"
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
    extension: String = ""
  ): (Path, Boolean) = {
    val suffix = name_suffix.getOrElse(Date.now().format(Date.Format("yyyy_MM_dd_HH_mm_ss_SSS")))
    val base_name = scratch_prefix + suffix
    val file_name = base_name + extension
    val file_path = (File.path(get_scratch_dir(session_id)) + Path.basic(file_name)).canonical
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
      JSON_Object("path" -> path.implode) ++
      JSON_Object.if_proper(!created, JSON_Object("note" -> "File already existed."))
    }
}

class Tools_Create_Scratch extends PIDE_MCP_Tools(new Tool_Create_Scratch)
