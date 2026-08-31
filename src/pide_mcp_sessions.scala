/*  Title:      PIDE_MCP/pide_mcp_sessions.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.immutable.VectorMap

class PIDE_MCP_Sessions(
  val tool_table: Map[String, PIDE_MCP_Tool],
  val log: Logger,
  val options: Options
) {
  private val build_lock = new Queue_Lock
  private def build_delay: Time = options.seconds("build_delay")
  private def build_progress_delay: Time = options.seconds("pide_mcp_session_progress_delay")

  private enum Entry {
    case Starting extends Entry
    case Running(session: PIDE_MCP_Session) extends Entry
    case Stopping(session: PIDE_MCP_Session) extends Entry
  }
  private val state = Synchronized[VectorMap[String, Entry]](VectorMap.empty)

  private def state_json(entries: VectorMap[String, Entry]): JSON.Object.T = {
    val starting = List.newBuilder[String]
    val running = List.newBuilder[String]
    val stopping = List.newBuilder[String]
    for ((session_id, entry) <- entries) {
      entry match {
        case Entry.Starting => starting += session_id
        case Entry.Running(_) => running += session_id
        case Entry.Stopping(_) => stopping += session_id
      }
    }
    JSON_Object(
      "starting" -> starting.result(),
      "running" -> running.result(),
      "stopping" -> stopping.result())
  }
  def state_json(): JSON.Object.T = state_json(state.value)

  private def all_running(entries: VectorMap[String, Entry]): List[PIDE_MCP_Session] =
    entries.valuesIterator.collect { case Entry.Running(session) => session }.toList
  def all_running(): List[PIDE_MCP_Session] = all_running(state.value)

  private def get_running(
    entries: VectorMap[String, Entry],
    session_ids: Option[List[String]]
  ): Result[List[PIDE_MCP_Session], Throwable] = {
    val running = all_running(entries)
    session_ids match {
      case None => Result.Res(running)
      case Some(ids) =>
        val (missing, sessions) =
          ids.partitionMap(id => running.find(_.id == id).toRight(id))
        if (missing.nonEmpty) Result.Error(ERROR(
          s"No running session(s) ${commas_quote(missing)} found. " +
          s"Available session(s): ${JSON.Format(state_json(entries))}"))
        else Result.Res(sessions)
    }
  }
  def get_running(
    session_ids: Option[List[String]]
  ): Result[List[PIDE_MCP_Session], Throwable] = get_running(state.value, session_ids)

  private def fresh_id(base: String, entries: VectorMap[String, Entry]): String =
    if (!entries.contains(base)) base
    else Iterator.from(2).map(base + "#" + _).find(!entries.contains(_)).get

  private def update_sessions(
    entries: VectorMap[String, Entry],
    session_ids: List[String],
    update: PartialFunction[Option[Entry], Option[Entry]]
  ): VectorMap[String, Entry] =
    session_ids.foldLeft(entries) { (entries, session_id) =>
      entries.updatedWith(session_id)(update.applyOrElse(_, identity[Option[Entry]]))
    }
  private def update_sessions(
    session_ids: List[String],
    update: PartialFunction[Option[Entry], Option[Entry]]
  ): Unit = state.change(update_sessions(_, session_ids, update))

  private def insert_starting(
    spec: PIDE_MCP_Session.Spec
  ): Result[(String, PIDE_MCP_Session.Spec), Throwable] =
    state.change_result { entries =>
      val session_id = spec.id.getOrElse(fresh_id(spec.logic, entries))
      if (session_id.isEmpty)
        (Result.Error(ERROR("Session id must not be empty")), entries)
      else if (entries.contains(session_id))
        (Result.Error(ERROR(s"Session id ${quote(session_id)} is already used")), entries)
      else
        (Result.Res((session_id, spec.copy(id = Some(session_id)))),
          update_sessions(entries, List(session_id), { case None => Some(Entry.Starting) }))
    }

  private def update_running_stopping(
    session_ids: Option[List[String]]
  ): Result[List[PIDE_MCP_Session], Throwable] =
    state.change_result { entries =>
      get_running(entries, session_ids) match {
        case Result.Error(exn) => (Result.Error(exn), entries)
        case Result.Res(sessions) =>
          (Result.Res(sessions), update_sessions(entries, sessions.map(_.id),
            { case Some(Entry.Running(session)) => Some(Entry.Stopping(session)) }))
      }
    }

  private def tool_failure_message(
    operation: String,
    session_id: String,
    tool: PIDE_MCP_Tool,
    message: String
  ): String =
    s"Fatal error during $operation of tool ${quote(tool.name)} " +
    s"for session ${quote(session_id)}: $message"

  private def run_tools_lifecycle(
    operation: String,
    session_id: String,
    run: PIDE_MCP_Tool => Unit
  ): List[Throwable] =
    PIDE_MCP_Util.capture_failures(tool_table.valuesIterator)(run).map { case (tool, exn) =>
      Exn.capture {
        log.error_message(tool_failure_message(operation, session_id, tool, Exn.print(exn)))
      }
      if (Exn.is_interrupt(exn)) exn
      else ERROR(tool_failure_message(operation, session_id, tool, Exn.message(exn)))
    }.toList

  private enum Start_Result {
    case Error(exn: Throwable) extends Start_Result
    case Running(session: PIDE_MCP_Session) extends Start_Result
    case Stopping(session: PIDE_MCP_Session, exn: Throwable) extends Start_Result
  }

  def start(
    spec: PIDE_MCP_Session.Spec,
    progress: Progress = new Progress
  ): Result[PIDE_MCP_Session, Throwable] =
    insert_starting(spec) match {
      case Result.Error(exn) => Result.Error(exn)
      case Result.Res((session_id, spec1)) =>
        val start_result =
          try {
            log(s"Starting PIDE session ${quote(session_id)} " +
              s"with base session ${quote(spec1.logic)}...")
            progress.expose_interrupt()
            Exn.result {
              build_lock.with_lock(progress,
                s"Awaiting build queue for session ${quote(session_id)}",
                build_delay, build_progress_delay) { PIDE_MCP_Session.build(spec1, progress) }
            } match {
              case Exn.Exn(exn) => Start_Result.Error(exn)
              case Exn.Res((options, session_background)) =>
                PIDE_MCP_Session(spec1, options, session_background, log, progress) match {
                  case Result.Error(exn) => Start_Result.Error(exn)
                  case Result.Res(session) =>
                    Exn.capture {
                      progress.expose_interrupt()
                      log(s"PIDE session ${quote(session.id)} started")
                      PIDE_MCP_Util.check_failures(
                        run_tools_lifecycle("start", session.id, tool => {
                          progress.expose_interrupt()
                          tool.start(this, session, progress)
                        }))
                    } match {
                      case Exn.Res(_) =>
                        update_sessions(List(session.id),
                          { case Some(Entry.Starting) => Some(Entry.Running(session)) })
                        Start_Result.Running(session)
                      case Exn.Exn(exn) =>
                        update_sessions(List(session.id),
                          { case Some(Entry.Starting) => Some(Entry.Stopping(session)) })
                        Start_Result.Stopping(session, exn)
                    }
                }
            }
          } finally update_sessions(List(session_id), { case Some(Entry.Starting) => None })
        start_result match {
          case Start_Result.Error(exn) => Result.Error(exn)
          case Start_Result.Running(session) => Result.Res(session)
          case Start_Result.Stopping(session, exn) =>
            Exn.capture { PIDE_MCP_Util.check_failures(stop_stopping(session, progress)) } match {
              case Exn.Res(_) => throw exn
              case Exn.Exn(stop_exn) =>
                throw ERROR(cat_lines(List(Exn.message(exn), Exn.message(stop_exn))))
            }
        }
    }

  private def stop_stopping(
    session: PIDE_MCP_Session,
    progress: Progress
  ): List[Throwable] =
    Exn.capture {
      val log_exn =
        Exn.capture { log(s"Stopping PIDE session ${quote(session.id)}...") } match {
          case Exn.Res(_) => None
          case Exn.Exn(exn) => Some(exn)
        }
      val exns = log_exn.toList :::
        run_tools_lifecycle("stop", session.id, _.stop(this, session, progress))
      Exn.capture(session.stop()) match {
        case Exn.Res(process_result) if process_result.ok =>
          update_sessions(List(session.id), { case Some(Entry.Stopping(_)) => None })
          exns
        case Exn.Res(process_result) =>
          exns ::: List(ERROR(s"Failed to stop PIDE session ${quote(session.id)}: " +
            PIDE_MCP_Util.print_process_result(process_result)))
        case Exn.Exn(exn) =>
          Exn.capture {
            log.error_message(
              s"Error stopping PIDE session ${quote(session.id)}: ${Exn.print(exn)}")
          }
          exns ::: List(exn)
      }
    } match {
      case Exn.Res(exns) => exns
      case Exn.Exn(exn) => List(exn)
    }

  def stop_running(
    session_ids: Option[List[String]] = None,
    progress: Progress = new Progress
  ): Result[List[String], Throwable] =
    update_running_stopping(session_ids.map(_.distinct)) match {
      case Result.Error(exn) => Result.Error(exn)
      case Result.Res(sessions) =>
        PIDE_MCP_Util.check_failures(sessions.flatMap(stop_stopping(_, progress)))
        Result.Res(sessions.map(_.id))
    }
}
