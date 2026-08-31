/*  Title:      PIDE_MCP/tool_get_session_state.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._
import isabelle.pide.mcp.ML_Statistics

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
      unapply, _.toString)
  }

  def options_json(options: Options, mode: Options_Mode): JSON.Object.T = {
    def json(entries: Iterator[Options.Entry]): JSON.Object.T =
      JSON_Object("options" ->
        JSON_Object(entries.toList.sortBy(_.name).map(entry => entry.name -> entry.value)))
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

  private def sum[A](xs: List[A], value: A => Option[Double]): Option[Double] = {
    val values = xs.map(value)
    Option.when(values.nonEmpty && values.forall(_.isDefined))(values.flatten.sum)
  }

  private def space_entry[A](
    xs: List[A],
    key: String,
    value: A => Option[Double]
  ): Option[(String, JSON.T)] =
    sum(xs, value).map(bytes => key -> Space.B(bytes).print)

  private def space_average_maximum(
    ml_stats: List[ML_Statistics],
    key: String,
    field: ML_Statistics.Field
  ): List[Option[(String, JSON.T)]] = {
    def entry(suffix: String, aggregate: ML_Statistics => Double) =
      space_entry(ml_stats, key + suffix,
        stats => Option.when(stats.content.nonEmpty)(Space.MiB(aggregate(stats)).B))
    List(entry("_average", ML_Statistics.average(_, field)),
      entry("_maximum", ML_Statistics.maximum(_, field)))
  }

  private def count_entry(
    statistics: List[Properties.T],
    key: String,
    field: ML_Statistics.Field
  ): Option[(String, JSON.T)] =
    sum(statistics, field.unapply).map(count => key -> count.toLong)

  object ML_Stats {
    def apply(statistics: List[Properties.T]): ML_Stats = ML_Stats(
      samples = statistics.length, ml = ML_Statistics(statistics),
      after_gc = ML_Statistics(statistics.filter(ml_heap_collected)),
      latest = statistics.lastOption)
  }

  sealed case class ML_Stats(
    samples: Int,
    ml: ML_Statistics,
    after_gc: ML_Statistics,
    latest: Option[Properties.T]
  )

  def ml_stats_json(stats: List[ML_Stats]): JSON.Object.T = {
    val ml_stats = stats.map(_.ml)
    val after_gc_stats = stats.map(_.after_gc)
    val latest = stats.flatMap(_.latest)
    JSON_Object.flatten(List(
      Option.when(stats.nonEmpty)("samples" -> stats.map(_.samples).sum.toLong),
      ml_stats.map(_.duration).maxOption.map(duration =>
        "duration" -> Time.seconds(duration).message)) :::
      space_average_maximum(after_gc_stats, "ml_heap_used_after_gc", ML_Heap_Used) :::
      space_average_maximum(ml_stats, "ml_heap", ML_Statistics.Heap_Size) :::
      space_average_maximum(ml_stats, "ml_code", ML_Statistics.Program_Code) :::
      space_average_maximum(ml_stats, "ml_stack", ML_Statistics.Program_Stack) :::
      List(count_entry(latest, "tasks_running", ML_Statistics.Tasks_Running),
        count_entry(latest, "tasks_total", ML_Statistics.Tasks_Total),
        count_entry(latest, "workers_active", ML_Statistics.Workers_Active),
        count_entry(latest, "workers_total", ML_Statistics.Workers_Total),
        count_entry(latest, "ml_threads", ML_Statistics.Threads_ML)))
  }

  def jvm_stats_json(): JSON.Object.T = {
    val statistics = List(ML_Statistics.jvm_statistics())
    JSON_Object.flatten(
      space_entry(statistics, "java_heap_used", ML_Statistics.Java_Heap_Used.unapply),
      space_entry(statistics, "java_heap", ML_Statistics.Java_Heap_Size.unapply),
      count_entry(statistics, "java_workers_active", ML_Statistics.Java_Workers_Active),
      count_entry(statistics, "java_workers_total", ML_Statistics.Java_Workers_Total),
      count_entry(statistics, "java_threads_total", ML_Statistics.Java_Threads_Total))
  }
}

class Tool_Get_Session_State extends PIDE_MCP_Tool("get_session_state") {
  def description: String =
    "Inspect the state of running PIDE sessions, their theories (including status and progress), their resource statistics, and the currently running commands. " +
      "**Use this for a global overview, e.g. when you think the prover is stuck or you want to know what is being processed.** " +
      "Note that sorrys are not listed."

  private val filter_origins_arg = PIDE_MCP_Tool_Arg.dictionary_default(
    "filter_origins",
    "Mapping from session id to dynamic origins (session-qualified theory names or file paths). " +
      "If provided for a session, only returns theory information and running commands for those origins. " +
      "Sessions without an entry include all their dynamic theories. " +
      "Does not affect resource statistics.",
    PIDE_MCP_Tool_Arg.Format.list(PIDE_MCP_Tool_Arg.Format.string),
    VectorMap.empty)
  private val include_commands_running_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_commands_running", "Include commands that are currently being processed.", true)
  private val include_theory_status_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_theory_status", "Include the status and progress of theories.", true)
  private val include_loaded_theories_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_loaded_theories", "List loaded theories per session.", false)
  private val include_session_dirs_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_session_dirs",
    "List directories from which the session attempts to load files. " +
      "Use this when you want to learn what libraries you have access to and where they are located. " +
      "Note that session names, which you have to use for session-qualified loading of (library) theories, are stored in the ROOT files of these directories.",
    false)
  private val include_options_arg = PIDE_MCP_Tool_Arg.default(
    "include_options",
    "List Isabelle session options. " +
      s"Use ${quote("changed")} to only show options that differ from their default value and " +
      s"${quote("true")} to show all options.",
    Tool_Get_Session_State.Options_Mode.format, Tool_Get_Session_State.Options_Mode.default)
  private val include_statistics_arg = PIDE_MCP_Tool_Arg.bool_default(
    "include_statistics",
    "Include each session's recent Isabelle/ML statistics (heap, threads,...), their summary, and the MCP server's JVM statistics (heap, threads,...). " +
      "Use this to check memory consumption and when you think the server is stuck.",
    false)

  def input_schema: JSON.Object.T = PIDE_MCP_Tool_Schema.input_schema(
    List(PIDE_MCP_Tool_Schema.running_sessions_arg, filter_origins_arg,
      include_commands_running_arg, include_theory_status_arg, include_loaded_theories_arg,
      include_session_dirs_arg, include_options_arg, include_statistics_arg))

  override def annotations: Option[JSON.Object.T] = Some(JSON_Object("readOnlyHint" -> true))

  def handle(
    sessions: PIDE_MCP_Sessions,
    args: JSON.Object.T,
    progress: Progress
  ): PIDE_MCP_Tool_Result =
    PIDE_MCP_Tool_Result.result {
      val selected_sessions = PIDE_MCP_Tool_Util.running_sessions_param(sessions, args)
      val include_commands_running = include_commands_running_arg.get(args)
      val include_theory_status = include_theory_status_arg.get(args)
      val include_loaded_theories = include_loaded_theories_arg.get(args)
      val include_session_dirs = include_session_dirs_arg.get(args)
      val include_statistics = include_statistics_arg.get(args)
      val include_options = include_options_arg.get(args)
      val filter_origins = filter_origins_arg.get(args)
      val selected_ids = selected_sessions.map(_.id)
      val unknown = filter_origins.keysIterator.filterNot(selected_ids.toSet).toList
      if (unknown.nonEmpty) {
        error(s"Session(s) ${commas_quote(unknown)} of filter_origins not contained in " +
          s"selection ${commas_quote(selected_ids)}.")
      }
      lazy val session_statistics: VectorMap[String, Tool_Get_Session_State.ML_Stats] =
        VectorMap.from(selected_sessions.flatMap { session =>
          val statistics = session.runtime_statistics()
          Option.when(statistics.nonEmpty)(
            session.id -> Tool_Get_Session_State.ML_Stats(statistics))
        })
      val session_states = selected_sessions.map { session =>
        lazy val snapshot = session.snapshot()
        lazy val theories = PIDE_MCP_Theory.Loaded(session, snapshot.version.nodes)
        lazy val names =
          filter_origins.get(session.id) match {
            case None => theories.dynamic
            case Some(session_origins) =>
              val origins = session_origins.map(origin =>
                session.origin(session.node_name(origin))).toSet
              theories.dynamic.filter(name => origins.contains(session.origin(name)))
          }
        JSON_Object("session" -> session.id, "base_session" -> session.base_session) ++
          JSON.optional(PIDE_MCP_Command.Status.key(PIDE_MCP_Command.Status.running) ->
            Option.when(include_commands_running) {
              PIDE_MCP_Theory.commands_running_json(session, snapshot, names)
            }) ++
          JSON_Object.if_proper(include_theory_status,
            PIDE_MCP_Theory.Status.statuses_json(
              PIDE_MCP_Theory.Status.statuses(session, snapshot, names))) ++
          JSON.optional("loaded_theories" ->
            Option.when(include_loaded_theories)(theories.json)) ++
          JSON.optional("session_directories" ->
            Option.when(include_session_dirs)(session.directories().map(_.implode))) ++
          Tool_Get_Session_State.options_json(session.options, include_options) ++
          JSON_Object.if_proper(include_statistics, session_statistics.get(session.id)
            .map(stats => Tool_Get_Session_State.ml_stats_json(List(stats)))
            .getOrElse(JSON_Object("note" -> "no ML statistics reported yet")))
      }
      JSON_Object("sessions" -> session_states) ++
        JSON_Object.if_proper(include_statistics,
          Tool_Get_Session_State.ml_stats_json(session_statistics.values.toList) ++
            Tool_Get_Session_State.jvm_stats_json())
    }
}

class Tools_Get_Session_State extends PIDE_MCP_Tools(new Tool_Get_Session_State)
