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
    "Look up what entities (constants, theorems, commands, methods, ML terms,...) in a file are defined where and how, i.e. find their origin with preview snippets. "
      + "Useful to learn more about concepts that you are uncertain about or for which you need more information (e.g. the actual theorem statement) and to study the content of a file. To get all entities defined in a file, select the file with full range and also pass it in filter_origins. "
      + PIDE_MCP_Tool_Schema.implicit_load_file
      + " Implicitly (re)loads theories containing source snippets if required."

  def input_schema: JSON.Object.T =
    JSON_Object("type" -> "object", "properties" -> JSON_Object(
      PIDE_MCP_Tool_Schema.origin_prop,
      PIDE_MCP_Tool_Schema.start_line_prop,
      PIDE_MCP_Tool_Schema.opt_end_line_prop,
      "snippet_lines" -> JSON_Object("type" -> "integer",
        "description" -> "Number of context lines per definition source snippet (use 0 to omit).",
        "minimum" -> 0, "default" -> snippet_preview_lines),
      "filter_origins" -> JSON_Object("type" -> "array", "items" -> JSON_Object("type" -> "string"),
        "description" -> "List of origins (session-qualified theory names or file paths). If provided, only returns entities whose definition originates from one of these. Use this if you want to explore the entities defined by a given origin.")
    ), "required" -> List("origin", "start_line"))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(params: JSON.Object.T): Exn.Result[JSON.T] = Exn.capture {
    val node_name = Exn.release(PIDE_MCP_Tool_Util.origin_param(session, params))
    val snapshot = PIDE_MCP_Tool_Util.require_loaded_origin_snapshot(session, node_name)
    val opt_start_line = JSON.int(params, "start_line")
    if (opt_start_line.isEmpty) error("Missing or invalid start_line")
    val opt_end_line = JSON.int(params, "end_line")
    val snippet_lines = JSON.int(params, "snippet_lines").getOrElse(snippet_preview_lines)
    val doc = Line.Document(snapshot.node.source)
    val (start_line, end_line) =
      Exn.release(PIDE_MCP_Tool_Util.resolve_lines(opt_start_line, opt_end_line, doc.lines.length))
    val filter_origins = JSON.strings(params, "filter_origins").getOrElse(Nil)
      .map(s => session.origin(Exn.release(session.node_name(s)))).toSet
    val range = PIDE_MCP_Util.text_range(doc, start_line, end_line).get
    Exn.release(PIDE_MCP_Name_Space_Entry.definitions_json(session, snapshot, Some(range),
      snippet_lines, filter_origins, definition_kinds,
      "The definition entry has not been loaded yet. " + PIDE_MCP_Tool_Util.retry_soon_message))
  }
}

class Tools_Find_Entities extends PIDE_MCP_Tools(new Tool_Find_Entities)
