/*  Title:      PIDE_MCP/tool_find_entities.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

class Tool_Find_Entities extends PIDE_MCP_Tool("find_entities") {
  private val snippet_preview_lines: Int = 3

  private val definition_kinds =
    List(Markup.AXIOM, Markup.FACT, Markup.DYNAMIC_FACT, Markup.LITERAL_FACT,
      Markup.CONSTANT, Markup.TYPE_NAME,
      Markup.THEORY, Markup.SESSION, Markup.CLASS, Markup.LOCALE,
      Markup.COMMAND, Markup.CASE, Markup.BUNDLE,
      Markup.METHOD, Markup.ATTRIBUTE,
      Markup.ML_ANTIQUOTATION, Markup.ML_DEF,
      Markup.DOCUMENT_ANTIQUOTATION, Markup.DOCUMENT_ANTIQUOTATION_OPTION)

  def description: String =
    "Look up what entities (constants, theorems, commands, methods, ML terms,...) in a file are defined where and how, i.e. find their origin with preview snippets. " +
      "Useful to learn more about concepts that you are uncertain about or for which you need more information (e.g. the actual theorem statement) and to study the content of a file. To get all entities defined in a file, select the file with full range and also pass it in filter_origins. " +
      PIDE_MCP_Tool_Schema.implicit_load_file

  private val snippet_lines_arg = PIDE_MCP_Tool_Arg.int_default(
    "snippet_lines",
    "Number of context lines per definition source snippet (use 0 to omit).",
    snippet_preview_lines, minimum = Some(0))
  private val filter_origins_arg = PIDE_MCP_Tool_Arg.strings_default(
    "filter_origins",
    "List of origins (session-qualified theory names or file paths). If non-empty, only returns entities whose definition originates from one of these. Use this if you want to explore the entities defined by a given origin.",
    Nil)

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg,
      PIDE_MCP_Tool_Schema.start_line_arg, PIDE_MCP_Tool_Schema.opt_end_line_arg,
      snippet_lines_arg, filter_origins_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

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
      val opt_start_line = Some(PIDE_MCP_Tool_Schema.start_line_arg.get(args))
      val opt_end_line = PIDE_MCP_Tool_Schema.opt_end_line_arg.get(args)
      val snippet_lines = snippet_lines_arg.get(args)
      val doc = Line.Document(snapshot.node.source)
      val (start_line, end_line) =
        PIDE_MCP_Tool_Util.resolve_lines(opt_start_line, opt_end_line, doc.lines.length)
      val filter_origins = filter_origins_arg.get(args)
        .map(origin => session.origin(session.node_name(origin))).toSet
      val range = PIDE_MCP_Util.text_range(doc, start_line, end_line).get
      val definitions = PIDE_MCP_Definition.definitions(
        session, snapshot, Some(range), snippet_lines, definition_kinds, filter_origins,
        "The definition entry has not been loaded yet. " +
          PIDE_MCP_Tool_Util.retry_soon_message)
      PIDE_MCP_Definition.definitions_json(definitions)
    }
}

class Tools_Find_Entities extends PIDE_MCP_Tools(new Tool_Find_Entities)
