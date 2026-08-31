/*  Title:      PIDE_MCP/pide_mcp_tool_util.scala
    Author:     Kevin Kappelmann

Utilities for PIDE MCP Tools.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Tool_Util {
  val retry_soon_message: String = "Please retry soon."
  val default_start_line: Int = 1
  val all_tools_option: String = "*"

  def make_tool_table(tool_names: String = all_tools_option): Map[String, PIDE_MCP_Tool] = {
    val tools =
      Isabelle_System.make_services(classOf[PIDE_MCP_Tools]).flatMap(_.entries).
        foldLeft(Map.empty[String, PIDE_MCP_Tool]) {
          case (tools, tool) =>
            if (tools.isDefinedAt(tool.name))
              error(s"Duplicate PIDE_MCP tool: ${Library.quote(tool.name)}")
            else tools + (tool.name -> tool)
        }
    if (tool_names == all_tools_option) tools
    else {
      val names = space_explode(',', tool_names)
      val unknown = names.filterNot(tools.isDefinedAt)
      if (unknown.nonEmpty)
        error(s"Unknown PIDE_MCP tool(s): ${commas_quote(unknown)}. " +
          s"Available tool(s): ${commas_quote(tools.keys.toList.sorted)}")
      tools.filter { case (name, _) => names.contains(name) }
    }
  }

  def running_session_param(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T
  ): PIDE_MCP_Session = {
    def available: String = s"Available session(s): ${JSON.Format(sessions.state_json())}"
    PIDE_MCP_Tool_Schema.running_session_arg.get(args) match {
      case None =>
        sessions.all_running() match {
          case List(session) => session
          case Nil => error(s"No running session. $available")
          case _ => error(s"Multiple running sessions: specify ${quote("session")}. $available")
        }
      case Some(session_id) =>
        Result.release(sessions.get_running(Some(List(session_id)))).head
    }
  }

  def running_sessions_param(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T
  ): List[PIDE_MCP_Session] =
    Result.release(sessions.get_running(PIDE_MCP_Tool_Schema.running_sessions_arg.get(args)))

  def origin_param(session: PIDE_MCP_Session, args: JSON.Object.T): Document.Node.Name =
    session.node_name(PIDE_MCP_Tool_Schema.origin_arg.get(args))

  def require_loaded_origin_snapshot(
    session: PIDE_MCP_Session,
    node_name: Document.Node.Name,
    progress: Progress = new Progress
  ): Document.Snapshot = {
    val snapshot =
      Exn.result { session.node_snapshot(node_name) } match {
        case Exn.Res(snapshot) => snapshot
        case Exn.Exn(_) =>
          if (PIDE_MCP_Util.is_loaded_dynamic(session.tip_version(progress).nodes, node_name))
            error(s"The origin ${quote(session.origin(node_name))} is loaded but has not been " +
              "processed yet. " + retry_soon_message)
          else {
            session.read_update_resolve(node_name, List((1, Some(0))), // pass empty range to load empty nodes
              await_stable_before_resolve = false, hide_others = false, range_context = 0,
              progress = progress)
            error(s"The origin ${quote(session.origin(node_name))} was not loaded and has now " +
              "been queued for loading. " + retry_soon_message)
          }
      }
    if (!node_name.is_theory && snapshot.commands_loading.isEmpty)
      error(s"File ${quote(session.origin(node_name))} is not loaded by any theory. " +
        "Load the containing theory first.")
    snapshot
  }

  def require_ordered_lines(
    opt_start_line: Option[Int],
    opt_end_line: Option[Int]
  ): Unit = {
    for (start_line <- opt_start_line; end_line <- opt_end_line if end_line < start_line)
      error(s"end_line $end_line < start_line $start_line")
  }

  def require_lines_in_bounds(
    opt_start_line: Option[Int],
    opt_end_line: Option[Int],
    total_lines: Int
  ): Unit = {
    for (start_line <- opt_start_line 
        if start_line < 1 || start_line > (total_lines max default_start_line))
      error(s"start_line $start_line out of bounds (file has $total_lines lines)")
    for (end_line <- opt_end_line if end_line < 1 || end_line > total_lines)
      error(s"end_line $end_line out of bounds (file has $total_lines lines)")
  }

  def require_valid_lines(
    opt_start_line: Option[Int],
    opt_end_line: Option[Int],
    total_lines: Int
  ): Unit = {
    require_ordered_lines(opt_start_line, opt_end_line)
    require_lines_in_bounds(opt_start_line, opt_end_line, total_lines)
  }

  def resolve_lines(
    opt_start_line: Option[Int],
    opt_end_line: Option[Int],
    total_lines: Int
  ): (Int, Int) = {
    require_valid_lines(opt_start_line, opt_end_line, total_lines)
    (opt_start_line.getOrElse(default_start_line), opt_end_line.getOrElse(total_lines))
  }
}
