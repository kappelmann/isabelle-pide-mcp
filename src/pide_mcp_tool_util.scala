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

  def origin_param(session: PIDE_MCP_Session, args: JSON.Object.T): Exn.Result[Document.Node.Name] =
    Exn.capture {
      val origin = JSON.string(args, "origin").getOrElse(error("Missing origin parameter"))
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
          if (PIDE_MCP_Util.is_loaded_dynamic(Exn.release(session.tip_version()).nodes, node_name))
            error(s"The origin ${session.origin(node_name)} is loaded but has not been processed yet. "
              + retry_soon_message)
          else {
            Exn.release(session.read_update_resolve(node_name, List((1, Some(0))), // pass empty range to load empty nodes
              await_stable_before_resolve = false, hide_others = false, range_context = 0))
            error(s"The origin ${session.origin(node_name)} was not loaded and has now been queued for loading. "
              + retry_soon_message)
          }
      }
    if (!node_name.is_theory && snapshot.commands_loading.isEmpty)
      error(s"File ${session.origin(node_name)} is not loaded by any theory. Load the containing theory first.")
    snapshot
  }

  def require_ordered_lines(
    opt_start_line: Option[Int],
    opt_end_line: Option[Int]
  ): Exn.Result[Unit] = Exn.capture {
    for (start_line <- opt_start_line; end_line <- opt_end_line if end_line < start_line)
      error(s"end_line $end_line < start_line $start_line")
  }

  def require_lines_in_bounds(
    opt_start_line: Option[Int],
    opt_end_line: Option[Int],
    total_lines: Int
  ): Exn.Result[Unit] = Exn.capture {
    for (start_line <- opt_start_line if start_line < 1 || start_line > total_lines)
      error(s"start_line $start_line out of bounds (file has $total_lines lines)")
    for (end_line <- opt_end_line if end_line < 1 || end_line > total_lines)
      error(s"end_line $end_line out of bounds (file has $total_lines lines)")
  }

  def require_valid_lines(
    opt_start_line: Option[Int],
    opt_end_line: Option[Int],
    total_lines: Int
  ): Exn.Result[Unit] = Exn.capture {
    Exn.release(require_ordered_lines(opt_start_line, opt_end_line))
    Exn.release(require_lines_in_bounds(opt_start_line, opt_end_line, total_lines))
  }

  def resolve_lines(
    opt_start_line: Option[Int],
    opt_end_line: Option[Int],
    total_lines: Int
  ): Exn.Result[(Int, Int)] =
    Exn.capture {
      Exn.release(require_valid_lines(opt_start_line, opt_end_line, total_lines))
      (opt_start_line.getOrElse(1), opt_end_line.getOrElse(total_lines))
    }
}
