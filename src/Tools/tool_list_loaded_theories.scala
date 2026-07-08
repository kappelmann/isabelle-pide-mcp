/*  Title:      PIDE_MCP/tool_list_loaded_theories.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object Tool_List_Loaded_Theories {
  def loaded_theories(session: PIDE_MCP_Session)
    : (List[Document.Node.Name], List[Document.Node.Name]) = {
    val all_nodes = session.session.snapshot().version.nodes.topological_order
    val base_session_names = session.resources.session_base.loaded_theories.keys.toSet
    all_nodes.partition(n => base_session_names.contains(n.theory))
  }
}

class Tool_List_Loaded_Theories extends PIDE_MCP_Tool("list_loaded_theories") {
  def description: String = "List all loaded theories from the session."

  def input_schema: JSON.Object.T =
    JSON.Object("type" -> "object", "properties" -> JSON.Object.empty)

  override def annotations: Option[JSON.Object.T] = Some(JSON.Object("readOnlyHint" -> true))

  def handle(params: JSON.Object.T): Exn.Result[JSON.T] = Exn.capture {
    val (base_session, dynamic) = Tool_List_Loaded_Theories.loaded_theories(session)
    def to_entry(node_name: Document.Node.Name) = JSON.Object("origin" -> session.origin(node_name))
    JSON.Object(
      "dynamic" -> dynamic.map(to_entry),
      "base_session" -> base_session.map(to_entry)
    )
  }
}

class Tools_List_Loaded_Theories extends PIDE_MCP_Tools(new Tool_List_Loaded_Theories)
