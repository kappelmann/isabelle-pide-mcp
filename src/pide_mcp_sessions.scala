/*  Title:      PIDE_MCP/pide_mcp_sessions.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.immutable.VectorMap

class PIDE_MCP_Sessions(
  val tool_table: Map[String, PIDE_MCP_Tool],
  val log: Logger,
  val base_progress: Progress,
  val options: Options
) {
  private val build_lock = new Queue_Lock
  private def build_delay: Time = options.seconds("build_delay")
  private def build_progress_delay: Time = options.seconds("pide_mcp_session_progress_delay")

  private enum Phase { case Building, Starting, Running, Stopping }

  private val state = Synchronized[VectorMap[String, Entry]](VectorMap.empty)

  private def state_json(entries: VectorMap[String, Entry]): JSON.Object.T = {
    val grouped = entries.toList.groupBy { case (_, entry) => entry.phase }
    JSON_Object(
      for (phase <- Phase.values.toList) yield Word.lowercase(phase.toString) ->
        grouped.getOrElse(phase, Nil).map(_._1))
  }
  def state_json(): JSON.Object.T = state_json(state.value)

  private def remove_session(session_id: String): Unit = state.change(_ - session_id)

  private def select[A](
    entries: VectorMap[String, Entry],
    what: String,
    candidates: List[(String, A)],
    session_ids: Option[List[String]]
  ): Result[List[(String, A)], Throwable] =
    session_ids match {
      case None => Result.Res(candidates)
      case Some(ids) =>
        val table = candidates.toMap
        val (missing, found) = ids.partitionMap(id => table.get(id).map(id -> _).toRight(id))
        if (missing.nonEmpty) Result.Error(ERROR(
          s"No $what session(s) ${commas_quote(missing)} found. " +
          s"Available session(s): ${JSON.Format(state_json(entries))}"))
        else Result.Res(found)
    }

  def running_sessions(
    session_ids: Option[List[String]]
  ): Result[List[PIDE_MCP_Session], Throwable] = {
    val entries = state.value
    val running = entries.toList.flatMap { case (id, entry) => entry.running.map(id -> _) }
    select(entries, "running", running, session_ids) match {
      case Result.Error(exn) => Result.Error(exn)
      case Result.Res(found) => Result.Res(found.map(_._2))
    }
  }

  private def fresh_id(base: String, entries: VectorMap[String, Entry]): String =
    if (!entries.contains(base)) base
    else Iterator.from(2).map(base + "#" + _).find(!entries.contains(_)).get

  private class Entry(val id: String, spec: PIDE_MCP_Session.Spec, caller_progress: Progress) {
    private val session_future = Future.promise[PIDE_MCP_Session]
    private val start_thread = Synchronized[Option[Thread]](None)
    private val start_progress = new Sub_Progress(caller_progress)
    private val built = Synchronized(false)
    private val phase_consumer =
      Synchronized[Option[Session.Consumer[Session.Phase]]](None)

    private val stop_request = Synchronized[Option[Promise[List[Throwable]]]](None)

    private def claim_stop(): (Boolean, Promise[List[Throwable]]) =
      stop_request.change_result {
        case Some(result) => ((false, result), Some(result))
        case None =>
          val result = Future.promise[List[Throwable]]
          ((true, result), Some(result))
      }

    def phase: Phase = if (stop_request.value.isDefined) Phase.Stopping
      else session_future.peek match {
        case None => if (built.value) Phase.Starting else Phase.Building
        case Some(Exn.Res(_)) => Phase.Running
        case Some(Exn.Exn(_)) => Phase.Stopping // a failed start automatically stops
      }

    def running: Option[PIDE_MCP_Session] =
      if (phase == Phase.Running) session_future.peek.collect { case Exn.Res(session) => session }
      else None

    private def run_tools_lifecycle(
      operation: String,
      run: PIDE_MCP_Tool => Unit
    ): List[Throwable] = {
      def failure_message(tool: PIDE_MCP_Tool, message: String): String =
        s"Fatal error during $operation of tool ${quote(tool.name)} " +
        s"for session ${quote(id)}: $message"
      PIDE_MCP_Util.capture_failures(tool_table.valuesIterator, run).map { case (tool, exn) =>
        Exn.capture { log(failure_message(tool, Exn.print(exn))) }
        if (Exn.is_interrupt(exn)) exn else ERROR(failure_message(tool, Exn.message(exn)))
      }.toList
    }

    private def shutdown(session: PIDE_MCP_Session, progress: Progress): List[Throwable] = {
      Exn.capture { log(s"Stopping PIDE session ${quote(id)}...") }
      for (consumer <- phase_consumer.change_result(consumer => (consumer, None)))
        session.session.phase_changed -= consumer
      val hook_failures = run_tools_lifecycle("stop", _.stop(
        PIDE_MCP_Sessions.this, session, new Uncancellable_Progress(progress)))
      Exn.capture { Isabelle_Thread.try_uninterruptible { session.stop() } } match {
        case Exn.Res(process_result) =>
          if (!process_result.ok) Exn.capture {
            log(s"PIDE session ${quote(id)} stopped with " +
              PIDE_MCP_Util.print_process_result(process_result))
          }
          hook_failures
        case Exn.Exn(exn) =>
          Exn.capture {
            log(s"Error stopping PIDE session ${quote(id)}: ${Exn.print(exn)}")
          }
          hook_failures ::: List(exn)
      }
    }

    def start(): PIDE_MCP_Session = {
      val started_before =
        start_thread.change_result {
          case None => (false, Some(Thread.currentThread().nn))
          case starter => (true, starter)
        }
      if (started_before) error(s"PIDE session ${quote(id)} was already started")
      var started: Option[PIDE_MCP_Session] = None
      try {
        log(s"Starting PIDE session ${quote(id)} " +
          s"with base session ${quote(spec.logic)}...")
        start_progress.expose_interrupt()
        val (options, session_background) =
          build_lock.with_lock(start_progress, s"Awaiting build queue for session ${quote(id)}",
            build_delay, build_progress_delay) { PIDE_MCP_Session.build(spec, start_progress) }
        built.change(_ => true)
        start_progress.expose_interrupt()
        val session =
          Result.release(PIDE_MCP_Session(spec, options, session_background, log, start_progress))
        started = Some(session)
        val consumer = Session.Consumer[Session.Phase]("pide_mcp_prover") {
          case Session.Terminated(result) if stop_request.value.isEmpty =>
            Exn.capture {
              val syslog = session.session.syslog.content()
              log(s"PIDE session ${quote(id)} terminated unexpectedly: " +
                PIDE_MCP_Util.print_process_result(result) +
                if_proper(syslog, "\n" + syslog))
            }
            Isabelle_Thread.fork(name = "pide_mcp_stop_" + id) {
              for (exn <- stop(base_progress))
                Exn.capture { log(Exn.message(exn)) }
            }
          case _ =>
        }
        phase_consumer.change(_ => Some(consumer))
        session.session.phase_changed += consumer
        consumer.consume(session.session.phase) // session might have stopped already
        start_progress.expose_interrupt()
        log(s"PIDE session ${quote(id)} started")
        PIDE_MCP_Util.check_failures(
          run_tools_lifecycle("start", tool => {
            start_progress.expose_interrupt()
            tool.start(PIDE_MCP_Sessions.this, session, start_progress)
          }))
        if (stop_request.value.isDefined || !session.session.is_ready)
          error(s"PIDE session ${quote(id)} was stopped during startup")
        session_future.fulfill(session)
        session
      }
      catch {
        case exn: Throwable =>
          val (is_owner, result) = claim_stop()
          val failures =
            try { started.toList.flatMap(shutdown(_, start_progress)) }
            finally { remove_session(id) }
          if (is_owner) result.fulfill_result(Exn.Res(failures))
          val failure = PIDE_MCP_Util.failure(exn, failures)
          session_future.fulfill_result(Exn.Exn(failure))
          throw failure
      }
    }

    def stop(progress: Progress): List[Throwable] = {
      val (is_owner, result) = claim_stop()
      if (is_owner) result.fulfill_result(Exn.capture {
        start_progress.stop()
        if (start_thread.value.contains(Thread.currentThread().nn) && !session_future.is_finished)
          Nil
        else
          session_future.join_result match {
            case Exn.Exn(_) => Nil
            case Exn.Res(session) =>
              try { shutdown(session, progress) }
              finally { remove_session(id) }
          }
      })
      result.join
    }
  }

  def start_session(
    spec: PIDE_MCP_Session.Spec,
    progress: Progress
  ): Result[PIDE_MCP_Session, Throwable] =
    Exn.result {
      val entry = state.change_result { entries =>
        val session_id = spec.id.getOrElse(fresh_id(spec.logic, entries))
        if (session_id.isEmpty) error("Session id must not be empty")
        else if (entries.contains(session_id))
          error(s"Session id ${quote(session_id)} is already used")
        else {
          val entry = new Entry(session_id, spec.copy(id = Some(session_id)), progress)
          (entry, entries + (session_id -> entry))
        }
      }
      entry.start()
    } match {
      case Exn.Res(session) => Result.Res(session)
      case Exn.Exn(exn) => Result.Error(exn)
    }

  def stop_sessions(
    session_ids: Option[List[String]],
    progress: Progress
  ): Result[List[String], Throwable] = {
    val entries = state.value
    val stoppable = entries.toList.filter { case (_, entry) => entry.phase != Phase.Stopping }
    select(entries, "stoppable", stoppable, session_ids.map(_.distinct)) match {
      case Result.Error(exn) => Result.Error(exn)
      case Result.Res(selected) =>
        val failures = selected.flatMap { case (_, entry) =>
          Exn.capture { entry.stop(progress) } match {
            case Exn.Res(exns) => exns
            case Exn.Exn(exn) => List(exn)
          }
        }
        Exn.result { PIDE_MCP_Util.check_failures(failures) } match {
          case Exn.Res(_) => Result.Res(selected.map(_._1))
          case Exn.Exn(exn) => Result.Error(exn)
        }
    }
  }
}
