/*  Title:      PIDE_MCP/tool_sledgehammer.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp.extensions

import isabelle._
import isabelle.pide.mcp._

class Tool_Sledgehammer extends PIDE_MCP_Tool("sledgehammer") {
  def description: String =
    "Run sledgehammer on the goal of the command at the given line without modifying the file. " +
      "Returns the suggested proof snippets. " +
      PIDE_MCP_Tool_Schema.implicit_load_file

  private val provers_arg = PIDE_MCP_Tool_Arg.opt_string(
    "provers",
    "Space-separated provers to run. Empty for Sledgehammer's default provers. " +
      "Defaults to the session's sledgehammer_provers option.")
  private val isar_proofs_arg = PIDE_MCP_Tool_Arg.bool_default(
    "isar_proofs", "Also generate structured Isar proofs.", false)
  private val try0_arg = PIDE_MCP_Tool_Arg.bool_default(
    "try0", "Try standard integrated provers with the found facts before external provers.", true)
  private val prefix_arg = PIDE_MCP_Tool_Arg.opt_string(
    "prefix",
    "Prefix of the line. Sledgehammer runs on the state after the prefix. Omit to use end of the line.")
  private val proofs_limit_arg = PIDE_MCP_Tool_Arg.int_default(
    "proofs_limit", "Maximium number of proofs to be found.", 1, minimum = Some(1))
  private val timeout_arg = PIDE_MCP_Tool_Arg.opt_int(
    "timeout", "Seconds to wait before cancellation.",
    minimum = Some(1))

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg,
      PIDE_MCP_Tool_Schema.start_line_arg, prefix_arg, provers_arg, isar_proofs_arg, try0_arg,
      proofs_limit_arg, timeout_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  private def caret_offset(
    snapshot: Document.Snapshot,
    line: Int,
    prefix: Option[String]
  ): Text.Offset = {
    val doc = Line.Document(snapshot.node.source)
    PIDE_MCP_Tool_Util.require_lines_in_bounds(Some(line), None, doc.lines.length)
    val range = PIDE_MCP_Util.text_range(doc, line, line).get
    val text = range.substring(snapshot.node.source).stripLineEnd
    val caret = prefix match {
        case None => text.length
        case Some(p) =>
          if (!text.startsWith(p))
            error(s"Line $line does not start with ${quote(p)}. Actual line: ${quote(text)}")
          p.length
      }
    range.start + caret
  }

  private def proofs_count(output: Editor.Output): Int =
    output.messages.count(msg => Protocol.sendback_snippets(List(msg)).nonEmpty)

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val session = PIDE_MCP_Tool_Util.running_session_param(sessions, args)
      val node_name = PIDE_MCP_Tool_Util.origin_param(session, args)
      val snapshot =
        PIDE_MCP_Tool_Util.require_loaded_origin_snapshot(session, node_name, progress)
      val line = PIDE_MCP_Tool_Schema.start_line_arg.get(args)
      val context =
        PIDE_MCP_Editor.Context(node_name, caret_offset(snapshot, line, prefix_arg.get(args)))
      val provers =
        provers_arg.get(args).getOrElse(session.options.string("sledgehammer_provers"))
      val query_args = List(provers,
        isar_proofs_arg.get(args).toString, try0_arg.get(args).toString)
      val proofs_limit = proofs_limit_arg.get(args)
      new PIDE_MCP_Query(session, context, "sledgehammer", progress)
        .run(query_args, timeout_arg.get(args).map(seconds => Time.seconds(seconds)),
          stop = output => proofs_count(output) >= proofs_limit)
        .json(PIDE_MCP_Command.Message_Kind.all)
    }
}

class Tools_Sledgehammer extends PIDE_MCP_Tools(new Tool_Sledgehammer)
