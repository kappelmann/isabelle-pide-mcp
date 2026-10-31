/*  Title:      MCP/src/Tools/tool_find_entities.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object Tool_Find_Entities {
  enum Kind(val markups: List[String], val hint: String = "") {
    case facts extends Kind(
      List(Markup.FACT, Markup.DYNAMIC_FACT, Markup.LITERAL_FACT, Markup.AXIOM))
    case constant extends Kind(List(Markup.CONSTANT))
    case type_name extends Kind(List(Markup.TYPE_NAME))
    case `class` extends Kind(List(Markup.CLASS))
    case fixed extends Kind(List(Markup.FIXED))
    case ml extends Kind(List(Markup.ML_DEF, Markup.ML_ANTIQUOTATION))
    case modules extends Kind(List(Markup.THEORY, Markup.SESSION, Markup.LOCALE, Markup.BUNDLE),
      "theories, sessions, locales, bundles,...")
    case isar extends Kind(List(Markup.COMMAND, Markup.METHOD, Markup.ATTRIBUTE, Markup.CASE),
      "commands, methods, attributes,...")
    case presentation extends Kind(
      List(Markup.DOCUMENT_ANTIQUOTATION, Markup.DOCUMENT_ANTIQUOTATION_OPTION,
        "antiquotation_style", "document_marker"), "document antiquotations, markers,...")
    case misc extends Kind(
      List("simproc", Markup.ORACLE, "plugin", "antiquotation", "system_option", "tool"),
      "simprocs, system options,...")

    def name: String = if (markups.length == 1) markups.head else toString
  }
}

class Tool_Find_Entities extends PIDE_MCP_Tool("find_entities") {
  def description: String =
    "Find definition locations for entities (theorems, commands,...). " +
    PIDE_MCP_Tool_Schema.implicit_load_file

  private val snippet_lines_arg = JSON_Schema.Arg.default(
    "snippet_lines", "Number of source lines shown per hit (0 to omit).",
    JSON_Schema.Format.int(minimum = Some(0)), 3)
  private val filter_origins_arg = JSON_Schema.Arg.optional(
    "filter_origins",
    "Return entities defined in one of these origins. Default: all.",
    JSON_Schema.Format.list(JSON_Schema.Format.string))
  private val kinds_arg = JSON_Schema.Arg.default(
    "kinds", "Kinds to return. " +
    Tool_Find_Entities.Kind.values.filter(_.hint.nonEmpty)
      .map(kind => kind.name + " = " + kind.hint).mkString("; ") + ".",
    JSON_Schema.Format.list(
      JSON_Schema.Format.enumeration(Tool_Find_Entities.Kind.values, _.name)),
    List(Tool_Find_Entities.Kind.facts, Tool_Find_Entities.Kind.constant,
      Tool_Find_Entities.Kind.type_name, Tool_Find_Entities.Kind.`class`))

  def input_schema: JSON.Object.T = JSON_Schema.object_schema(
    List(PIDE_MCP_Tool_Schema.running_session_arg, PIDE_MCP_Tool_Schema.origin_arg,
      PIDE_MCP_Tool_Schema.start_line_arg, PIDE_MCP_Tool_Schema.opt_end_line_arg,
      snippet_lines_arg, filter_origins_arg, kinds_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

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
      val snippet_lines = snippet_lines_arg.get(args)
      val filter_origins = filter_origins_arg.get(args)
        .map(_.map(origin => session.origin(session.node_name(origin))).toSet)
      val range = PIDE_MCP_Util.check_text_range(Line.Document(snapshot.node.source),
        PIDE_MCP_Tool_Schema.start_line_arg.get(args),
        PIDE_MCP_Tool_Schema.opt_end_line_arg.get(args))
      val definition_kinds = kinds_arg.get(args).flatMap(_.markups)
      val definitions = PIDE_MCP_Definition.definitions(
        session, snapshot, Some(range), snippet_lines, definition_kinds, filter_origins,
        "The definition entry has not been loaded yet. " +
        PIDE_MCP_Tool_Util.retry_soon_message)
      PIDE_MCP_Definition.definitions_json(definitions)
    }
}

class Tools_Find_Entities extends PIDE_MCP_Tools(new Tool_Find_Entities)
