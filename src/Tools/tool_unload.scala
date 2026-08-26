/*  Title:      PIDE_MCP/tool_unload.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Unload extends PIDE_MCP_Tool("unload") {
  def description: String = "Unload the given theories and their dependents."

  def input_schema: JSON.Object.T =
    JSON_Object("type" -> "object", "properties" -> JSON_Object(
      "origins" -> JSON_Object("type" -> "array",
        "items" -> JSON_Object("type" -> "string"),
        "description" -> "Origins (session-qualified theory names or file paths) to unload.")
    ), "required" -> List("origins"))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("destructiveHint" -> true))

  def handle(args: JSON.Object.T): Exn.Result[JSON.T] = Exn.capture {
    val origins = JSON.strings(args, "origins").getOrElse(error("Missing origins"))
    val node_names = origins.map { s => Exn.release(session.node_name(s)) }
    val unloaded = Exn.release(session.unload(node_names))
    if (unloaded.nonEmpty) session.await_stable_snapshot()
    JSON_Object("unloaded" -> unloaded.map(session.origin))
  }
}

class Tools_Unload extends PIDE_MCP_Tools(new Tool_Unload)
