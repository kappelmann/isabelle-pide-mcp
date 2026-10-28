/*  Title:      PIDE_MCP/tool_get_session_state.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.immutable.VectorMap

object Tool_Get_Session_State {
  enum Options_Mode { case `false`, `true`, changed }

  object Options_Mode {
    val default: Options_Mode = `false`
    def unapply(json: JSON.T): Option[Options_Mode] =
      json match {
        case mode: String => values.find(_.toString == mode)
        case JSON.Value.Boolean(mode) => Some(if (mode) `true` else `false`)
        case _ => None
      }
    val format = PIDE_MCP_Tool_Arg.Format(
      JSON_Object("type" -> List("string", "boolean"),
        "enum" -> (List[JSON.T](false, true) ::: values.toList.map(_.toString))),
      unapply, {
        case `false` => false
        case `true` => true
        case mode => mode.toString
      })
  }

  def options_json(options: Options, mode: Options_Mode): JSON.Object.T = {
    def json(entries: Iterator[Options.Entry]): JSON.Object.T =
      JSON_Object("options" ->
        JSON_Object(entries.map(entry => entry.name -> entry.value)))
    mode match {
      case Options_Mode.`false` => JSON.Object.empty
      case Options_Mode.`true` => json(options.iterator)
      case Options_Mode.changed =>
        json(options.iterator.filter(entry => entry.value != entry.default_value))
    }
  }

  val ML_Heap_Used: ML_Statistics.Field_MiB =
    new ML_Statistics.Field_MiB("size_heap_used", description = "heap used") {
      override val domain: List[String] =
        List(ML_Statistics.Heap_Size.name, ML_Statistics.Heap_Free_Minor.name)
      override def unapply(props: Properties.T): Option[Double] =
        for {
          size <- ML_Statistics.Heap_Size.unapply(props)
          free <- ML_Statistics.Heap_Free_Minor.unapply(props)
        } yield Space.B(size).used(Space.B(free)).B
    }

  def ml_heap_collected(props: Properties.T): Boolean =
    List(ML_Statistics.GCs_Minor, ML_Statistics.GCs_Major).exists(_.unapply(props).exists(_ > 0))

  private def space_entry(key: String, bytes: Option[Double]): Option[(String, JSON.T)] =
    bytes.map(bytes => key -> Space.B(bytes).print)

  private def space_average_maximum(
    ml: ML_Statistics,
    key: String,
    field: ML_Statistics.Field
  ): List[Option[(String, JSON.T)]] = {
    def entry(suffix: String, aggregate: ML_Statistics => Double) =
      Option.when(ml.content.nonEmpty)(key + suffix -> Space.MiB(aggregate(ml)).print)
    List(entry("_average", _.average(field)), entry("_maximum", _.maximum(field)))
  }

  private def count_entry(
    props: Properties.T,
    key: String,
    field: ML_Statistics.Field
  ): Option[(String, JSON.T)] =
    field.unapply(props).map(count => key -> count.toLong)

  def ml_stats_json(statistics: List[Properties.T]): JSON.Object.T = {
    val ml = ML_Statistics(statistics)
    val after_gc = ML_Statistics(statistics.filter(ml_heap_collected))
    val latest = statistics.last
    JSON_Object.flatten(List(
      Some("samples" -> statistics.length.toLong),
      Some("duration_ms" -> Time.seconds(ml.duration).ms)) :::
      space_average_maximum(after_gc, "ml_heap_used_after_gc", ML_Heap_Used) :::
      space_average_maximum(ml, "ml_heap", ML_Statistics.Heap_Size) :::
      space_average_maximum(ml, "ml_code", ML_Statistics.Program_Code) :::
      space_average_maximum(ml, "ml_stack", ML_Statistics.Program_Stack) :::
      List(count_entry(latest, "tasks_running", ML_Statistics.Tasks_Running),
        count_entry(latest, "tasks_total", ML_Statistics.Tasks_Total),
        count_entry(latest, "workers_active", ML_Statistics.Workers_Active),
        count_entry(latest, "workers_total", ML_Statistics.Workers_Total),
        count_entry(latest, "ml_threads", ML_Statistics.Threads_ML)))
  }

  def java_heap_collected(props: Properties.T): Boolean =
    ML_Statistics.Java_Heap_Size_Major.unapply(props).exists(_ > 0)

  def java_heap(props: Properties.T, field: ML_Statistics.Field): Option[Double] =
    if (java_heap_collected(props)) field.unapply(props) else None

  def jvm_stats_json(): JSON.Object.T = {
    val props = ML_Statistics.jvm_statistics()
    JSON_Object.flatten(
      space_entry("java_heap_used_after_gc", java_heap(props, ML_Statistics.Java_Heap_Used_Major)),
      space_entry("java_heap", java_heap(props, ML_Statistics.Java_Heap_Size_Major)),
      count_entry(props, "java_workers_active", ML_Statistics.Java_Workers_Active),
      count_entry(props, "java_workers_total", ML_Statistics.Java_Workers_Total),
      count_entry(props, "java_threads_total", ML_Statistics.Java_Threads_Total))
  }
}

class Tool_Get_Session_State extends PIDE_MCP_Tool("get_session_state") {
  def description: String =
    "State of running sessions, information for their theories " +
    "(status, commands being processed,...), resource statistics,... " +
    "Sorrys are not listed."

  private val filter_origins_arg = PIDE_MCP_Tool_Arg.dictionary_default(
    "filter_origins",
    "Session id to theory origins whose information is returned. " +
    "Default for entry-less session: all dynamic theories.",
    PIDE_MCP_Tool_Arg.Format.list(PIDE_MCP_Tool_Arg.Format.string),
    VectorMap.empty)
  private val include_cmds_processing_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_cmds_processing", "", true)
  private val include_theory_status_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_theory_status", "Status, progress, command counts.", true)
  private val list_loaded_theories_arg = PIDE_MCP_Tool_Arg.bool_default(
    "list_loaded_theories", "", false)
  private val list_session_dirs_arg = PIDE_MCP_Tool_Arg.bool_default(
    "list_session_dirs", "Directories from which a session attempts to resolve files.",
    false)
  private val list_options_arg = PIDE_MCP_Tool_Arg.default(
    "list_options",
    "Isabelle session options. " +
    s"Use ${quote("changed")} to only show options that differ from their default and " +
    "true to show all options.",
    Tool_Get_Session_State.Options_Mode.format, Tool_Get_Session_State.Options_Mode.default)
  private val include_statistics_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_statistics",
    "Each session's ML and the server's JVM statistics (heap, threads,...).",
    false)

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_sessions_arg, filter_origins_arg,
      include_cmds_processing_arg, include_theory_status_arg, list_loaded_theories_arg,
      list_session_dirs_arg, list_options_arg, include_statistics_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val selected_sessions = PIDE_MCP_Tool_Util.running_sessions_param(sessions, args)
      val include_cmds_processing = include_cmds_processing_arg.get(args)
      val include_theory_status = include_theory_status_arg.get(args)
      val list_loaded_theories = list_loaded_theories_arg.get(args)
      val list_session_dirs = list_session_dirs_arg.get(args)
      val include_statistics = include_statistics_arg.get(args)
      val list_options = list_options_arg.get(args)
      val filter_origins = filter_origins_arg.get(args)
      val selected_ids = selected_sessions.map(_.id)
      val unknown = filter_origins.keysIterator.filterNot(selected_ids.toSet).toList
      if (unknown.nonEmpty) {
        error(s"Session(s) ${commas_quote(unknown)} of filter_origins not contained in " +
          s"selection ${commas_quote(selected_ids)}.")
      }
      val session_states = selected_sessions.map { session =>
        lazy val snapshot = session.snapshot()
        lazy val theories = PIDE_MCP_Theory.Loaded(session, snapshot.version.nodes)
        lazy val names = filter_origins.get(session.id) match {
          case None => theories.dynamic
          case Some(session_origins) =>
            val origins = session_origins.map(origin =>
              session.origin(session.node_name(origin))).toSet
            theories.dynamic.filter(name => origins.contains(session.origin(name)))
        }
        JSON_Object("session" -> session.id, "base_session" -> session.base_session) ++
        JSON.optional(PIDE_MCP_Command.Status.key(PIDE_MCP_Command.Status.running.name) ->
          Option.when(include_cmds_processing) {
            PIDE_MCP_Theory.commands_running_json(session, snapshot, names)
          }.filter(_.nonEmpty)) ++
        JSON_Object.if_proper(include_theory_status,
          PIDE_MCP_Theory.Status.statuses_json(
            PIDE_MCP_Theory.Status.statuses(session, snapshot, names))) ++
        JSON.optional("loaded_thys" ->
          Option.when(list_loaded_theories)(theories.json)) ++
        JSON.optional("session_dirs" ->
          Option.when(list_session_dirs)(session.directories().map(_.implode))) ++
        Tool_Get_Session_State.options_json(session.options, list_options) ++
        JSON_Object.if_proper(include_statistics, {
          val statistics = session.runtime_statistics()
          if (statistics.isEmpty) JSON_Object("note" -> "no ML statistics reported yet")
          else Tool_Get_Session_State.ml_stats_json(statistics)
        })
      }
      JSON_Object("sessions" -> session_states) ++
      JSON_Object.if_proper(include_statistics, Tool_Get_Session_State.jvm_stats_json())
    }
}

class Tools_Get_Session_State extends PIDE_MCP_Tools(new Tool_Get_Session_State)
