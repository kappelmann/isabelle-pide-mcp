/*  Title:      PIDE_MCP/pide_mcp_server.scala
    Author:     Kevin Kappelmann

JSON-RPC server loop for the Model Context Protocol.
*/

package isabelle.pide.mcp

import isabelle._
import PIDE_MCP_Protocol._

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}

class PIDE_MCP_Server(
  sessions: PIDE_MCP_Sessions,
  log: Logger,
  base_progress: Progress.Status,
  log_messages: Boolean
) {

  private def respond(out: BufferedWriter, body: JSON.Object.T): Unit = {
    val text = JSON.Format(body)
    if (log_messages) log(text)
    out.write(text)
    out.newLine()
    out.flush()
  }

  private def respond_internal_error(
    id: JSON_RPC.Id | Null,
    out: BufferedWriter,
    exn: Throwable
  ): Unit = {
    Exn.capture {
      log.error_message(s"Internal error for request ${JSON.Format(id)}: ${Exn.print(exn)}")
    }
    respond(out, JSON_RPC.error(id, JSON_RPC.Error_Code.INTERNAL_ERROR,
      s"Internal error: ${Exn.message(exn)}"))
  }

  private enum Event {
    case Startup_Failure(exn: Throwable) extends Event
    case Input(line: String) extends Event
    case Input_Closed extends Event
    case Input_Failure(exn: Throwable) extends Event
    case Interrupt extends Event
    case Tool_Progress(id: JSON_RPC.Id,
      token: Progress_Token,
      serial: Long,
      message: String
    ) extends Event
    case Tool_Result(id: JSON_RPC.Id, result: Exn.Result[PIDE_MCP_Tool_Result]) extends Event
  }

  private class Tool_Task(
    id: JSON_RPC.Id,
    tool: PIDE_MCP_Tool,
    val call: Tool_Call,
    events: Mailbox[Event]
  ) extends Progress_Task(
    name = "pide_mcp_tool_call_" + id,
    progress = new PIDE_MCP_Task_Progress(base_progress,
      call.progress_token.map(token => (serial: Long, message: String) =>
        events.send(Event.Tool_Progress(id, token, serial, message)))),
    daemon = true
  ) {
    override protected def run(): Unit = {
      val result = Exn.capture {
        progress.expose_interrupt()
        tool.handle(sessions, call.args, progress)
      }
      events.send(Event.Tool_Result(id, result))
    }
  }

  private class Run {
    val events = Mailbox[Event]()
    val out = new BufferedWriter(new OutputStreamWriter(System.out, UTF8.charset))
    var tool_tasks = Map.empty[JSON_RPC.Id, Tool_Task]
    def progress_token_owner(token: Progress_Token): Option[JSON_RPC.Id] =
      tool_tasks.collectFirst {
        case (id, task) if task.call.progress_token.contains(token) => id
      }
  }

  private def read_input(events: Mailbox[Event]): Unit =
    Exn.capture { new BufferedReader(new InputStreamReader(System.in, UTF8.charset)) } match {
      case Exn.Exn(exn) => events.send(Event.Input_Failure(exn))
      case Exn.Res(in) =>
        var finished = false
        while (!finished) {
          Exn.capture(in.readLine()) match {
            case Exn.Res(null) =>
              events.send(Event.Input_Closed)
              finished = true
            case Exn.Res(line: String) => events.send(Event.Input(line))
            case Exn.Exn(exn) =>
              events.send(Event.Input_Failure(exn))
              finished = true
          }
        }
    }

  def run(startup: Progress => Unit): Unit = {
    val run_state = new Run
    Isabelle_Thread.fork(name = "pide_mcp_input", daemon = true) {
      read_input(run_state.events)
    }

    val startup_task = new Progress_Task(
      "pide_mcp_startup", new PIDE_MCP_Task_Progress(base_progress, None)) {
      override protected def run(): Unit =
        Exn.capture { startup(progress) } match {
          case Exn.Exn(exn) => run_state.events.send(Event.Startup_Failure(exn))
          case Exn.Res(_) =>
        }
    }
    startup_task.start()

    val result =
      Exn.capture {
        Exn.Interrupt.signal_handler(run_state.events.send(Event.Interrupt)) {
          var running = true
          while (running) {
            for (event <- run_state.events.receive() if running) event match {
              case Event.Startup_Failure(exn) =>
                Exn.capture { log.error_message(s"Startup failure: ${Exn.print(exn)}") }
                throw exn
              case Event.Input(line) => handle_input(line, run_state)
              case Event.Input_Closed => running = false
              case Event.Interrupt =>
                log("Interrupt: shutting down...")
                running = false
              case Event.Input_Failure(exn) =>
                Exn.capture { log.error_message(s"Input failure: ${Exn.print(exn)}") }
                throw exn
              case Event.Tool_Progress(id, token, serial, message) =>
                if (run_state.tool_tasks.contains(id))
                  respond(run_state.out, progress_notification(token, serial, message))
              case Event.Tool_Result(id, tool_result) =>
                val opt_task = run_state.tool_tasks.get(id)
                require(opt_task.isDefined,
                  s"Active tool task expected: ${JSON.Format(id)}")
                run_state.tool_tasks -= id
                handle_tool_result(id, opt_task.get, tool_result, run_state.out)
            }
          }
        }
      }
    val log_result = Exn.capture { log("Stopping tasks...") }
    val tasks = startup_task :: run_state.tool_tasks.valuesIterator.toList
    val stop_result = Exn.capture {
      val stop_results = tasks.map(task => Exn.capture(task.stop()))
      val join_results = tasks.map(task => Exn.capture(task.join()))
      Exn.release_first(stop_results ::: join_results)
      ()
    }
    Exn.release_first(List(result, log_result, stop_result))
  }

  private def handle_input(line: String, run: Run): Unit = {
    if (log_messages) log(s"<<< $line")
    Exn.result { JSON.parse(line, strict = false) } match {
      case Exn.Exn(exn) =>
        log.error_message(s"Parse error: ${Exn.print(exn)}")
        respond(run.out, JSON_RPC.error(null, JSON_RPC.Error_Code.PARSE_ERROR,
          s"Parse error: ${Exn.message(exn)}"))
      case Exn.Res(JSON.Object(request)) => handle_request(request, run)
      case Exn.Res(_) => respond(run.out, JSON_RPC.error(null,
        JSON_RPC.Error_Code.INVALID_REQUEST, "Bad request: expected a JSON object"))
    }
  }

  private def handle_request(request: JSON.Object.T, run: Run): Unit =
    Exn.result {
      val method = JSON.string(request, "method")
      val opt_id = JSON_RPC.id(request)
      if (JSON.string(request, "jsonrpc") != Some(Config.jsonrpc_version)) {
        respond(run.out, JSON_RPC.error(opt_id.getOrElse(null),
          JSON_RPC.Error_Code.INVALID_REQUEST,
          s"Bad request: expected jsonrpc version ${Config.jsonrpc_version}"))
      }
      // messages without id are notifications, requiring a method but no response
      else if (!request.contains("id")) {
        method match {
          case Some("notifications/cancelled") => handle_cancelled(request, run.tool_tasks)
          case Some(_) =>
          case None => respond(run.out, JSON_RPC.error(null,
            JSON_RPC.Error_Code.INVALID_REQUEST, "Bad request: no method"))
        }
      }
      else opt_id match {
        case None => respond(run.out, JSON_RPC.error(null,
          JSON_RPC.Error_Code.INVALID_REQUEST,
          s"Bad id: requests require a string or integer id, but got ${JSON.Format(request("id"))}"))
        case Some(id) if run.tool_tasks.contains(id) =>
          respond(run.out, JSON_RPC.error(id, JSON_RPC.Error_Code.INVALID_REQUEST,
            s"Request id ${JSON.Format(id)} is already active"))
        case Some(id) =>
          method match {
            case Some("initialize") => handle_initialize(id, request, run.out)
            case Some("ping") => respond(run.out, JSON_RPC.result(id, JSON.Object.empty))
            case Some("tools/list") => handle_tools_list(id, run.out)
            case Some("tools/call") => handle_tool_call(id, request, run)
            case Some(m) => respond(run.out, JSON_RPC.error(id,
              JSON_RPC.Error_Code.METHOD_NOT_FOUND, s"Method not found: ${quote(m)}"))
            case None => respond(run.out, JSON_RPC.error(id,
              JSON_RPC.Error_Code.INVALID_REQUEST, "Bad request: no method"))
          }
      }
    } match {
      case Exn.Res(_) =>
      case Exn.Exn(exn) =>
        for (id <- JSON_RPC.id(request))
          Exn.capture { respond_internal_error(id, run.out, exn) }
        throw exn
    }

  private def handle_cancelled(
    request: JSON.Object.T,
    tool_tasks: Map[JSON_RPC.Id, Tool_Task]
  ): Unit =
    Exn.result { parse_cancellation_notification(request) } match {
      case Exn.Res(cancellation) =>
        for (task <- tool_tasks.get(cancellation.id) if !task.stopped) {
          log("Cancelling tool call " + JSON.Format(cancellation.id) +
            cancellation.reason.map(reason =>
              s" (reason: ${JSON.Format(reason)})").getOrElse("") + "...")
          task.stop()
        }
      case Exn.Exn(exn) =>
        log.error_message("Malformed cancellation notification: " + Exn.message(exn))
    }

  private def handle_initialize(
    id: JSON_RPC.Id,
    request: JSON.Object.T,
    out: BufferedWriter
  ): Unit = {
    Exn.result { parse_initialize_request(request) } match {
      case Exn.Exn(exn) => respond(out, JSON_RPC.error(id,
        JSON_RPC.Error_Code.INVALID_PARAMS, Exn.message(exn)))
      case Exn.Res(client_version) => respond(out, JSON_RPC.result(id, JSON_Object(
        "protocolVersion" -> decide_protocol_version(client_version),
        "capabilities" -> JSON_Object("tools" -> JSON_Object()),
        "serverInfo" -> JSON_Object("name" -> Config.name, "version" -> Config.version),
        "instructions" -> Config.instructions)))
    }
  }

  private def handle_tools_list(id: JSON_RPC.Id, out: BufferedWriter): Unit = {
    val tools = sessions.tool_table.values.toList.sortBy(_.name).map { tool =>
      val entry = JSON_Object("name" -> tool.name, "description" -> tool.description,
        "inputSchema" -> tool.input_schema)
      tool.annotations match {
        case Some(a) => entry + ("annotations" -> a)
        case None => entry
      }
    }
    respond(out, JSON_RPC.result(id, JSON_Object("tools" -> tools)))
  }

  private def handle_tool_call(
    id: JSON_RPC.Id,
    request: JSON.Object.T,
    run: Run
  ): Unit = {
    Exn.result { parse_tool_call(request) } match {
      case Exn.Exn(exn) => respond(run.out, JSON_RPC.error(id,
        JSON_RPC.Error_Code.INVALID_PARAMS, Exn.message(exn)))
      case Exn.Res(call) => call.progress_token.flatMap(token =>
        run.progress_token_owner(token).map(token -> _)) match {
          case Some((token, owner)) => respond(run.out, JSON_RPC.error(id,
            JSON_RPC.Error_Code.INVALID_PARAMS,
            s"Progress token ${JSON.Format(token)} is already used " +
            s"for request ${JSON.Format(owner)}"))
          case None => sessions.tool_table.get(call.name) match {
            case None => respond(run.out, JSON_RPC.error(id,
              JSON_RPC.Error_Code.INVALID_PARAMS, s"Tool not found: ${quote(call.name)}"))
            case Some(tool) =>
              val task = new Tool_Task(id, tool, call, run.events)
              task.start()
              run.tool_tasks += id -> task
          }
        }
    }
  }

  private def handle_tool_result(
    id: JSON_RPC.Id,
    task: Tool_Task,
    result: Exn.Result[PIDE_MCP_Tool_Result],
    out: BufferedWriter
  ): Unit =
    result match {
      case Exn.Res(_) if task.stopped =>
      case Exn.Res(PIDE_MCP_Tool_Result.Res(result)) =>
        respond(out, JSON_RPC.result(id, tool_result(result)))
      case Exn.Res(PIDE_MCP_Tool_Result.Error(result)) =>
        respond(out, JSON_RPC.result(id, tool_result(result, is_error = true)))
      case Exn.Exn(exn) if task.stopped && Exn.is_interrupt(exn) =>
      case Exn.Exn(exn) =>
        Exn.capture { respond_internal_error(id, out, exn) }
        throw exn
    }
}
