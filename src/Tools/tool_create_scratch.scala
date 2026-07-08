/*  Title:      PIDE_MCP/tool_create_scratch.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._
import java.io.{File => JFile}

class Tool_Create_Scratch extends PIDE_MCP_Tool("create_scratch") {
  def description: String =
    "Create a temporary file for experimentation that does not interfere with user accessible files. "
      + "Use this whenever you think you need to do iterative developments or when you want to find and explore theorems, syntax, concepts, commands, ML code, etc. Write back final results to files accessible to the user. "
      + "Temporary files are cleaned up when the session stops."

  def input_schema: JSON.Object.T =
    JSON.Object("type" -> "object", "properties" -> JSON.Object(
      "name_suffix" -> JSON.Object("type" -> "string",
        "description" -> "Label to identify the scratch file (auto-generated if omitted)"),
      "extension" -> JSON.Object("type" -> "string",
        "description" -> "File extension (typically \".thy\" or \".ML\")")
    ))

  private val scratch_prefix: String = "tmp_pide_mcp_scratch_"
  private val scratch_tmpdir_prefix: String = "tmp_pide_mcp_scratch"
  private val scratch_dir = Synchronized[Option[JFile]](None)

  private def get_scratch_dir(): JFile =
    scratch_dir.change_result {
      case some @ Some(dir) if dir.isDirectory => (dir, some)
      case _ =>
        val dir = Isabelle_System.tmp_dir(scratch_tmpdir_prefix)
        (dir, Some(dir))
    }

  override def stop(): Unit =
    scratch_dir.change {
      case Some(dir) => Isabelle_System.rm_tree(dir); None
      case None => None
    }

  def create_scratch(
    name_suffix: Option[String] = None,
    extension: Option[String] = None
  ): Exn.Result[Path] = {
    val suffix = name_suffix.getOrElse(Date.now().format(Date.Format("yyyy_MM_dd_HH_mm_ss_SSS")))
    val base_name = scratch_prefix + suffix
    val file_name = extension match { case Some(ext) => base_name + ext case None => base_name }
    Exn.capture {
      val file_path = PIDE_MCP_Util.canonical_path(File.path(get_scratch_dir()) + Path.basic(file_name))
      File.write(file_path, "")
      file_path
    }
  }

  def handle(params: JSON.Object.T): Exn.Result[JSON.T] = Exn.capture {
    val name_suffix = JSON.string(params, "name_suffix")
    val extension = JSON.string(params, "extension")
    val path = Exn.release(create_scratch(name_suffix, extension))
    JSON.Object("path" -> path.implode)
  }
}

class Tools_Create_Scratch extends PIDE_MCP_Tools(new Tool_Create_Scratch)
