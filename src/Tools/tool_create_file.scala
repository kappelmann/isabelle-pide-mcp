/*  Title:      PIDE_MCP/tool_create_file.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object Tool_Create_File {
  def create_file(path: Path): Unit = {
    val abs_path = path.canonical
    Isabelle_System.make_directory(abs_path.dir)
    if (!abs_path.file.createNewFile()) {
      val kind = if (abs_path.file.isDirectory) "directory" else "path"
      error(s"Path ${quote(abs_path.implode)} is an existing $kind")
    }
  }
}

class Tool_Create_File extends PIDE_MCP_Tool("create_file") {
  def description: String =
    "Create an empty file at the given path. " +
      "Creates missing parent directories if necessary. " +
      "Fails if the path already exists."

  private val path_arg = PIDE_MCP_Tool_Arg.string(
    "path",
    "File path to create (e.g. \"./Algebra/algebra_simp.ML\" or \"/path/to/My_Theory.thy\")")

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(List(path_arg))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val file_path = path_arg.get(args)
      Tool_Create_File.create_file(PIDE_MCP_Util.path(file_path))
      "File created"
    }
}

class Tools_Create_File extends PIDE_MCP_Tools(new Tool_Create_File)
