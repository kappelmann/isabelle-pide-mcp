/*  Title:      Tool_Extensions/src/tool_sledgehammer.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp.extensions

import isabelle._
import isabelle.pide.mcp._

class Tool_Sledgehammer extends PIDE_MCP_Tool("sledgehammer") {
  def description: String =
    "Run sledgehammer on command's goal at the given line without modifying the file. " +
    PIDE_MCP_Tool_Schema.implicit_load_file

  private val line_arg = JSON_Schema.Arg.required(
    "line", "", JSON_Schema.Format.int(minimum = Some(1)))
  private val prefix_arg = JSON_Schema.Arg.optional(
    "prefix",
    "Prefix of the line. Sledgehammer runs on the command following it. Default: end of line.",
    JSON_Schema.Format.string)
  private val provers_arg = JSON_Schema.Arg.optional(
    "provers",
    "Space-separated provers to run. Empty for Sledgehammer's default provers. " +
    "Default: sledgehammer_provers option.",
    JSON_Schema.Format.string)
  private val isar_proofs_arg = JSON_Schema.Arg.default(
    "isar_proofs", "Also generate structured Isar proofs.", JSON_Schema.Format.bool, false)
  private val try0_arg = JSON_Schema.Arg.default(
    "try0", "Try integrated provers with found facts before external ones.",
    JSON_Schema.Format.bool, true)
  private val proofs_limit_arg = JSON_Schema.Arg.default(
    "proofs_limit", "Proofs to be found.", JSON_Schema.Format.int(minimum = Some(1)), 1)
  private val timeout_arg = JSON_Schema.Arg.optional(
    "timeout", "Seconds before cancellation.", JSON_Schema.Format.int(minimum = Some(1)))

  def input_schema: JSON.Object.T = JSON_Schema.object_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg,
      line_arg, prefix_arg, provers_arg, isar_proofs_arg, try0_arg,
      proofs_limit_arg, timeout_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  private def caret_offset(
    source: String,
    line: Int,
    prefix: Option[String]
  ): Text.Offset = {
    val doc = Line.Document(source)
    val range = PIDE_MCP_Util.check_text_range(doc, line, Some(line))
    val text = range.substring(source).stripLineEnd
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
      session.await_stable(session.sync_dependencies_resolve(List(node_name), progress), progress)
      val snapshot = PIDE_MCP_Tool_Util.require_loaded_origin_snapshot(session, node_name, progress)
      val line = line_arg.get(args)
      val context = PIDE_MCP_Editor.Context(node_name,
        caret_offset(snapshot.node.source, line, prefix_arg.get(args)))
      val provers = provers_arg.get(args).getOrElse(
        session.options.string("sledgehammer_provers"))
      val query_args = List(provers,
        Value.Boolean(isar_proofs_arg.get(args)), Value.Boolean(try0_arg.get(args)))
      val proofs_limit = proofs_limit_arg.get(args)
      new PIDE_MCP_Query(session, context, "sledgehammer", progress)
        .run(query_args, timeout_arg.get(args).map(seconds => Time.seconds(seconds)),
          stop = output => proofs_count(output) >= proofs_limit)
        .json(PIDE_MCP_Message.Message_Kind.values.toList)
    }
}

class Tools_Sledgehammer extends PIDE_MCP_Tools(new Tool_Sledgehammer)
