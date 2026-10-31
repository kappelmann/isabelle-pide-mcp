/*  Title:      MCP/src/Tools/tool_unload.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Unload extends PIDE_MCP_Tool("unload") {
  def description: String =
    "Unload origins and their dependents. " +
    "Use only if a theory is stuck or resources must be freed."

  private val origins_arg = JSON_Schema.Arg.required("origins", "",
    JSON_Schema.Format.list(JSON_Schema.Format.string))

  def input_schema: JSON.Object.T = JSON_Schema.object_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, origins_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("destructiveHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      val origins = origins_arg.get(args)
      val node_names = origins.map(session.node_name)
      val (unloaded, change) = session.unload(node_names, progress)
      if (unloaded.nonEmpty) session.await_stable(change, progress)
      JSON_Object("unloaded" -> unloaded.map(session.origin))
    }
}

class Tools_Unload extends PIDE_MCP_Tools(new Tool_Unload)
