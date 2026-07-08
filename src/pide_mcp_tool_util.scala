/*  Title:      PIDE_MCP/pide_mcp_tool_util.scala
    Author:     Kevin Kappelmann

Utilities for PIDE MCP Tools.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Tool_Util {
  val retry_soon_message: String = "Please retry soon."

  def make_tool_table: Exn.Result[Map[String, PIDE_MCP_Tool]] = Exn.capture {
    Isabelle_System.make_services(classOf[PIDE_MCP_Tools]).flatMap(_.entries).
      foldLeft(Map.empty[String, PIDE_MCP_Tool]) {
        case (tools, tool) =>
          if (tools.isDefinedAt(tool.name))
            error("Duplicate PIDE_MCP tool: " + Library.quote(tool.name))
          else tools + (tool.name -> tool)
      }
  }

  def origin_param(session: PIDE_MCP_Session, params: JSON.Object.T): Exn.Result[Document.Node.Name] =
    Exn.capture {
      val origin = JSON.string(params, "origin").getOrElse(error("Missing origin parameter"))
      Exn.release(session.node_name(origin))
    }

  def require_loaded_origin_snapshot(
    session: PIDE_MCP_Session,
    node_name: Document.Node.Name
  ): Document.Snapshot = {
    val snapshot =
      session.node_snapshot(node_name) match {
        case Exn.Res(snapshot) => snapshot
        case Exn.Exn(_) =>
          Exn.release(session.load(node_name))
          error(s"The origin ${session.origin(node_name)} was not previously loaded and has now been queued for loading. "
            + "The requested information was thus not ready yet. "
            + retry_soon_message)
      }
    if (!node_name.is_theory && !PIDE_MCP_Util.is_file_loaded(snapshot, node_name))
      error(s"File ${session.origin(node_name)} is not loaded by any theory. Load the containing theory first.")
    snapshot
  }

  private def require_valid_lines(
    start_line: Option[Int],
    end_line: Option[Int],
    total_lines: Int
  ): Exn.Result[Unit] = Exn.capture {
    for (s <- start_line if s < 1 || s > total_lines)
      error(s"start_line $s out of bounds (file has $total_lines lines)")
    for (e <- end_line if e < 1 || e > total_lines)
      error(s"end_line $e out of bounds (file has $total_lines lines)")
    for (s <- start_line; e <- end_line if e < s)
      error(s"end_line $e < start_line $s")
  }

  def resolve_lines(start_line: Option[Int], end_line: Option[Int], total_lines: Int): Exn.Result[(Int, Int)] =
    Exn.capture {
      Exn.release(require_valid_lines(start_line, end_line, total_lines))
      (start_line.getOrElse(1), end_line.getOrElse(total_lines))
    }
}
