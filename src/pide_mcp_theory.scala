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
      Loaded(session, base_session, dynamic.filter(name => PIDE_MCP_Util.is_loaded_dynamic(nodes(name))))
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
      val by_status = statuses.groupMapReduce(_.overall_status)(_ => 1)(_ + _)
      val theory_status_counts = JSON_Object(Document_Status.Overall_Status.values.flatMap(s =>
        by_status.get(s).map(s.toString -> _)))
      prefix_keys(theory_status_counts) ++
      JSON_Object.if_proper(statuses.nonEmpty,
        JSON_Object("theories" -> JSON_Object(statuses.map(status => status.origin -> status.json))))
    }
  }

  sealed case class Status(
    origin: String,
    node_status: Document_Status.Node_Status,
    overall_status: Document_Status.Overall_Status
  ) {
    def json: JSON.Object.T = {
      val command_status_counts = JSON_Object(List(
        PIDE_MCP_Command.Status.unprocessed.name -> node_status.unprocessed,
        PIDE_MCP_Command.Status.running.name -> node_status.running,
        PIDE_MCP_Command.Status.failed.name -> node_status.failed,
        PIDE_MCP_Command.Status.warned.name -> node_status.warned,
        PIDE_MCP_Command.Status.finished.name -> node_status.finished).filter(_._2 > 0))
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
    names.flatMap { name =>
      val node_snapshot = snapshot.switch(name)
      lazy val doc = Line.Document(node_snapshot.node.source)
      for {
        state <- PIDE_MCP_Command.States.commands(node_snapshot, None)
        problem <- state.problems.collectFirst {
          case problem @ PIDE_MCP_Command.Problem.status(PIDE_MCP_Command.Status.running, _, _) => problem
        }
      } yield JSON_Object("origin" -> session.origin(name)) ++
        problem.json(doc, PIDE_MCP_Command.States.default_snippet_lines)
    }
  }
}
