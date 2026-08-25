/*  Title:      PIDE_MCP/tool_get_progress.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object Tool_Get_Progress {

  def theory_status_key(key: String): String = "theories_" + key

  def prefix_theory_status_keys(obj: JSON.Object.T): JSON.Object.T = {
    val theory_status_keys = Document_Status.Overall_Status.values.map(_.toString).toSet
    obj.map { case (k, v) => if (theory_status_keys.contains(k)) (theory_status_key(k), v) else (k, v) }
  }

  sealed case class Theories_Progress(
    session: PIDE_MCP_Session,
    snapshot: Document.Snapshot,
    theories: List[Document.Node.Name]
  ) {
    def theory_states_json: JSON.Object.T = {
      val nodes_status = Document_Status.Nodes_Status.empty.update_nodes(
        Date.now(), session.resources, snapshot.state, snapshot.version, domain = Some(theories.toSet))
      val theory_states = JSON_Object(theories.map { name =>
        val node_status = nodes_status(name)
        // Node_Status.canceled is a boolean (is any command canceled), unlike get_state's "commands_canceled"
        // which is a per-command count; we hence omit it here to avoid confusion.
        val command_status_counts = JSON_Object(
          PIDE_MCP_Commands.Status.unprocessed -> node_status.unprocessed,
          PIDE_MCP_Commands.Status.running -> node_status.running,
          PIDE_MCP_Commands.Status.warned -> node_status.warned,
          PIDE_MCP_Commands.Status.failed -> node_status.failed,
          PIDE_MCP_Commands.Status.finished -> node_status.finished)
        session.origin(name) -> (JSON_Object(
          "overall_status" -> nodes_status.overall_status(name).toString,
          "percentage_commands_processed" -> node_status.percentage) ++
          PIDE_MCP_Commands.prefix_command_status_keys(command_status_counts))
      }: _*)

      val by_status = theories.groupMapReduce(nodes_status.overall_status(_).toString)(_ => 1)(_ + _)
      val theory_status_counts = JSON_Object(Document_Status.Overall_Status.values.toList.map(s =>
        s.toString -> by_status.getOrElse(s.toString, 0)): _*)

      prefix_theory_status_keys(theory_status_counts) + ("theories" -> theory_states)
    }

    def commands_running_json: List[JSON.Object.T] = {
      val opts = PIDE_MCP_Commands.State_Options()
      theories.flatMap { name =>
        val node_snapshot = snapshot.switch(name)
        val doc = Line.Document(node_snapshot.node.source)
        PIDE_MCP_Commands.state_entries_commands(node_snapshot, None).toList.flatMap { entry =>
          Option.when(entry.status.contains(PIDE_MCP_Commands.Status.running)) {
            val command_state = entry.json(doc, opts)
            JSON_Object("origin" -> session.origin(name)) ++ command_state
          }
        }
      }
    }
  }
}

class Tool_Get_Progress extends PIDE_MCP_Tool("get_progress") {
  def description: String = "Show progress of theories and list currently running commands. "
    + "**Use this for global progress overview, e.g. when you think the prover is stuck.** "
    + "Note that sorrys are not listed."

  def input_schema: JSON.Object.T =
    JSON_Object("type" -> "object", "properties" -> JSON_Object(
      "origins" -> JSON_Object("type" -> "array",
        "items" -> JSON_Object("type" -> "string"),
        "description" -> ("Restrict to these origins (session-qualified theory names or file paths). "
          + "Omit to include all theories."))
    ))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(params: JSON.Object.T): Exn.Result[JSON.T] = Exn.capture {
    val origins = JSON.strings(params, "origins").getOrElse(Nil)
      .map(s => session.origin(Exn.release(session.node_name(s)))).toSet

    val snapshot = session.snapshot()
    val (_, dynamic) = Tool_List_Loaded_Theories.loaded_theories(session, snapshot.version.nodes)
    val theory_names = if (origins.isEmpty) dynamic
      else dynamic.filter(name => origins.contains(session.origin(name)))
    val progress = Tool_Get_Progress.Theories_Progress(session, snapshot, theory_names)
    JSON_Object(
      PIDE_MCP_Commands.command_status_key(PIDE_MCP_Commands.Status.running) ->
        progress.commands_running_json) ++
      progress.theory_states_json
  }
}

class Tools_Get_Progress extends PIDE_MCP_Tools(new Tool_Get_Progress)
