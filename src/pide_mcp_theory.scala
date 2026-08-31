/*  Title:      PIDE_MCP/pide_mcp_theory.scala
    Author:     Kevin Kappelmann

Theory utilities.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Theory {
  object Loaded {
    def apply(session: PIDE_MCP_Session, nodes: Document.Nodes): Loaded = {
      val all_nodes = nodes.topological_order.filter(_.is_theory)
      val (base_session, dynamic) = all_nodes.partition(session.is_base_session_theory)
      Loaded(session, base_session, dynamic.filter(PIDE_MCP_Util.is_loaded_dynamic(nodes, _)))
    }
  }

  sealed case class Loaded private(
    session: PIDE_MCP_Session,
    base_session: List[Document.Node.Name],
    dynamic: List[Document.Node.Name]
  ) {
    def json: JSON.Object.T = {
      def entry(name: Document.Node.Name): JSON.Object.T =
        JSON_Object("origin" -> session.origin(name))
      JSON_Object("dynamic" -> dynamic.map(entry),
        "base_session" -> base_session.map(entry))
    }
  }

  object Status {
    def statuses(
      session: PIDE_MCP_Session,
      snapshot: Document.Snapshot,
      names: List[Document.Node.Name]
    ): List[Status] = {
      val nodes_status = Document_Status.Nodes_Status.empty.update_nodes(
        Date.now(), session.resources, snapshot.state, snapshot.version,
        domain = Some(names.toSet))
      names.map(name =>
        Status(session.origin(name), nodes_status(name), nodes_status.overall_status(name)))
    }

    def key(status: String): String = "theories_" + status

    def prefix_keys(obj: JSON.Object.T): JSON.Object.T = {
      val keys = Document_Status.Overall_Status.values.map(_.toString).toSet
      obj.map { case (k, v) => if (keys.contains(k)) (key(k), v) else (k, v) }
    }

    def statuses_json(statuses: List[Status]): JSON.Object.T = {
      val by_status = statuses.groupMapReduce(_.overall_status.toString)(_ => 1)(_ + _)
      val theory_status_counts = JSON_Object(Document_Status.Overall_Status.values.map(s =>
        s.toString -> by_status.getOrElse(s.toString, 0)))
      val theory_statuses = JSON_Object(statuses.map(
        status => status.origin -> status.json))
      prefix_keys(theory_status_counts) + ("theories" -> theory_statuses)
    }
  }

  sealed case class Status(
    origin: String,
    node_status: Document_Status.Node_Status,
    overall_status: Document_Status.Overall_Status
  ) {
    def json: JSON.Object.T = {
      val command_status_counts = JSON_Object(
        PIDE_MCP_Command.Status.unprocessed -> node_status.unprocessed,
        PIDE_MCP_Command.Status.running -> node_status.running,
        PIDE_MCP_Command.Status.warned -> node_status.warned,
        PIDE_MCP_Command.Status.failed -> node_status.failed,
        PIDE_MCP_Command.Status.finished -> node_status.finished)
      JSON_Object(
        "overall_status" -> overall_status.toString,
        "percentage_commands_processed" -> node_status.percentage) ++
        PIDE_MCP_Command.Status.prefix_keys(command_status_counts)
    }
  }

  def commands_running_json(
    session: PIDE_MCP_Session,
    snapshot: Document.Snapshot,
    names: List[Document.Node.Name]
  ): List[JSON.Object.T] = {
    val opts = PIDE_MCP_Command.State.Options()
    names.flatMap { name =>
      val node_snapshot = snapshot.switch(name)
      PIDE_MCP_Command.State.commands(node_snapshot, None).flatMap { entry =>
        Option.when(entry.status.contains(PIDE_MCP_Command.Status.running)) {
          JSON_Object("origin" -> session.origin(name)) ++
            entry.json(Line.Document(node_snapshot.node.source), opts)
        }
      }
    }
  }
}
