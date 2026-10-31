/*  Title:      MCP/src/pide_mcp_command.scala
    Author:     Kevin Kappelmann

Command utilities.
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.mutable

object PIDE_MCP_Command {
  val bad = Markup.BAD

  enum Status(
    val name: String,
    val message_kind: Option[PIDE_MCP_Message.Message_Kind] = None
  ) {
    case unprocessed extends Status("unprocessed")
    // running confuses agents: "running" = "it worked"
    case running extends Status("processing")
    case failed extends Status("failed", Some(PIDE_MCP_Message.Message_Kind.errors))
    case warned extends Status("warned", Some(PIDE_MCP_Message.Message_Kind.warnings))
    case finished extends Status("finished")
    case canceled extends Status("canceled")
  }
  object Status {
    val unproblematic: Set[Status] = Set(unprocessed, finished)
    val problematic: Set[Status] = values.toSet -- unproblematic

    def key(name: String): String = "cmds_" + name

    def prefix_keys(obj: JSON.Object.T): JSON.Object.T = {
      val keys = values.map(_.name).toSet + bad
      obj.map { case (k, v) => if (keys.contains(k)) (key(k), v) else (k, v) }
    }
  }

  private def statuses(status: Document_Status.Command_Status): List[Status] =
    List(
      Option.when(status.is_unprocessed)(Status.unprocessed),
      Option.when(status.is_running)(Status.running),
      Option.when(status.is_failed)(Status.failed),
      Option.when(status.is_warned)(Status.warned),
      Option.when(status.is_finished)(Status.finished),
      Option.when(status.is_canceled)(Status.canceled)).flatten

  // cf. Rendering.overview_color
  def range_status(
    snapshot: Document.Snapshot,
    range: Text.Range
  ): Document_Status.Command_Status = {
    val markups = snapshot.cumulate[List[Markup]](range, Nil,
      Document_Status.Command_Status.liberal_elements,
      _ => { case (status, Text.Info(_, elem)) => Some(elem.markup :: status) }, status = true)
    Document_Status.Command_Status.make(Date.now(), markups = markups.flatMap(_.info))
  }

  def self_id(
    state: Document.State,
    cmd: Command
  )(id: Document_ID.Generic): Boolean =
    id == cmd.id || state.lookup_id(id).exists(_.command.id == cmd.id)

  def elem_positions(
    snapshot: Document.Snapshot,
    cmd: Command,
    elem: XML.Elem
  ): Set[Text.Range] = {
    val chunk_name = if (snapshot.node_name.is_theory) Symbol.Text_Chunk.Default
      else Symbol.Text_Chunk.File(snapshot.node_name.node)
    cmd.chunks.get(chunk_name) match {
      case None => Set.empty
      case Some(chunk) =>
        cmd.message_positions(self_id(snapshot.state, cmd), chunk_name, chunk, elem)
    }
  }

  private val here: String = Symbol.decode("\\<^here>")

  def resolve_positions(snapshot: Document.Snapshot, cmd: Command, body: XML.Body): XML.Body = {
    def same_file(file: String): Boolean = file == snapshot.node_name.node ||
      Path.is_valid(file) && Path.explode(file).expand.implode == snapshot.node_name.node
    def line_text(line: Int, file: String): String =
      "(line " + line + if_proper(!same_file(file), " of " + quote(file)) + ")"
    body.flatMap {
      case XML.Elem(Markup(Markup.POSITION, Position.Item_Id(id, range)), List(XML.Text(text)))
      if text == here => if (self_id(snapshot.state, cmd)(id)) Nil
        else snapshot.find_command_position(id, range.start)
          .map(pos => XML.Text(" " + line_text(pos.line1, pos.name))).toList
      case XML.Elem(markup @ Markup(Markup.POSITION, props @ Position.Line(line)), _)
      if Position.File.unapply(props).exists(same_file) =>
        List(XML.Elem(markup, XML.string(line_text(line, snapshot.node_name.node))))
      case XML.Elem(markup, body1) =>
        List(XML.Elem(markup, resolve_positions(snapshot, cmd, body1)))
      case text => List(text)
    }
  }

  def single_command(snapshot: Document.Snapshot): Option[Command] =
    if (!snapshot.node_name.is_theory) snapshot.commands_loading.headOption
    else snapshot.node.get_theory

  def select(
    snapshot: Document.Snapshot,
    range: Option[Text.Range]
     // commands intersecting range, with the intersection relative to their start
  ): List[(Command, Text.Range)] =
    single_command(snapshot) match {
      case Some(cmd) =>
        List(cmd -> PIDE_MCP_Util.restrict_text_range(snapshot.node.source, range))
      case None =>
        range.fold(snapshot.node.command_iterator())(snapshot.node.command_iterator(_))
          .map { case (cmd, start) =>
            cmd -> (PIDE_MCP_Util.intersect_range(cmd.range + start, range) - start)
          }.toList
    }

  enum Problem {
    case status(status: Status, range: Text.Range, timing_ms: Long)
    case bad(info: Text.Info[String])
    case message(msg: PIDE_MCP_Message.Message)

    def key: String =
      this match {
        case Problem.status(status, _, _) => status.name
        case Problem.bad(_) => PIDE_MCP_Command.bad
        case Problem.message(msg) => msg.kind.toString
      }

    def message_kind: Option[PIDE_MCP_Message.Message_Kind] =
      this match {
        case Problem.status(status, _, _) => status.message_kind
        case Problem.bad(_) => None
        case Problem.message(msg) => Some(msg.kind)
      }

    def ranges: List[Text.Range] =
      this match {
        case Problem.status(_, range, _) => List(range)
        case Problem.bad(info) => List(info.range)
        case Problem.message(msg) => msg.ranges
      }

    def text: Option[String] =
      this match {
        case Problem.status(_, _, _) => None
        case Problem.bad(info) => proper_string(info.info)
        case Problem.message(msg) => proper_string(msg.text)
      }

    def json(doc: Line.Document, snippet_lines: Int): JSON.Object.T =
      JSON_Object("snippets" -> ranges.map(PIDE_MCP_Util.snippet(doc, _, snippet_lines))) ++
      JSON.optional("message" -> text) ++
      JSON.optional("timing_ms" -> Some(this).collect {
        case Problem.status(Status.running, _, timing_ms) => timing_ms
      })
  }

  object State {
    enum Detail { case errors, warnings, infos, goals, src, types, facts, markup }

    object Detail {
      def of(kind: PIDE_MCP_Message.Message_Kind): Detail =
        kind match {
          case PIDE_MCP_Message.Message_Kind.goals => goals
          case PIDE_MCP_Message.Message_Kind.errors => errors
          case PIDE_MCP_Message.Message_Kind.warnings => warnings
          case PIDE_MCP_Message.Message_Kind.writeln | PIDE_MCP_Message.Message_Kind.information |
            PIDE_MCP_Message.Message_Kind.tracing => infos
        }
      require(
        PIDE_MCP_Message.Message_Kind.values.forall(kind =>
          PIDE_MCP_Message.Message_Kind.info.contains(kind) == (of(kind) == infos)),
        "Inconsistent info message kinds")
    }
  }

  sealed case class State private[PIDE_MCP_Command](
    snapshot: Document.Snapshot,
    cmd: Command,
    range: Text.Range,
    messages: List[PIDE_MCP_Message.Message]
  ) {
    lazy val command_status: Document_Status.Command_Status = snapshot.state.command_status(
      snapshot.version, cmd)
    private lazy val range_status = PIDE_MCP_Command.range_status(snapshot, range)
    lazy val status: List[Status] = statuses(command_status).filter {
      case Status.warned => range_status.is_warned
      case Status.failed => range_status.is_failed
      case _ => true
    }
    lazy val timing_ms: Long = command_status.timings.sum(Date.now()).ms
    private lazy val messages_by_kind = messages.groupBy(_.kind)
    def messages_of(kind: PIDE_MCP_Message.Message_Kind): List[PIDE_MCP_Message.Message] =
      messages_by_kind.getOrElse(kind, Nil)
    lazy val errors = messages_of(PIDE_MCP_Message.Message_Kind.errors)
    lazy val warnings = messages_of(PIDE_MCP_Message.Message_Kind.warnings)
    lazy val bad: List[Text.Info[String]] = PIDE_MCP_Markup.bad(snapshot, range).filterNot(info =>
      errors.nonEmpty && info.info.isEmpty)
    private def bad_json(doc: Line.Document): List[JSON.Object.T] =
      bad.map { case Text.Info(r, text) =>
        JSON_Object.flatten(
          proper_string(text).map("message" -> _),
          Some("src" ->
            PIDE_MCP_Util.numbered_lines(r.substring(snapshot.node.source),
              doc.position(r.start).line1)))
      }

    def problem(st: Status): Problem = Problem.status(st, range, timing_ms)
    lazy val problems: List[Problem] = status.filter(Status.problematic).map(problem) :::
      bad.map(Problem.bad(_)) ::: (errors ::: warnings).map(Problem.message(_))

    def is_ignorable_now: Boolean = cmd.is_ignored && messages.isEmpty && problems.isEmpty
    def is_ignorable_for_good: Boolean = is_ignorable_now && command_status.is_terminated

    def json(doc: Line.Document, details: Set[State.Detail]): JSON.Object.T = {
      lazy val source_line = doc.position(range.start).line1
      lazy val source = range.substring(snapshot.node.source).stripLineEnd
      val entries: List[Option[JSON.Object.Entry]] = List(
        proper_list(status.map(_.name)).map("status" -> _),
        Option.when(timing_ms > 0)("timing_ms" -> timing_ms),
        Some(
          if (details(State.Detail.src))
            "src" -> PIDE_MCP_Util.numbered_lines(source, source_line)
          else "lines" -> PIDE_MCP_Util.line_range(source_line,
            doc.position(range.start + source.length).line1)),
        proper_list(bad_json(doc)).map(PIDE_MCP_Command.bad -> _)) :::
        PIDE_MCP_Message.messages_json(messages_by_kind,
          PIDE_MCP_Message.Message_Kind.values.toList.filter(kind =>
            details(State.Detail.of(kind)))) :::
        List(
          Option.when(details(State.Detail.types))(
            proper_list(PIDE_MCP_Markup.types_json(snapshot, range)).map("types" -> _)).flatten,
          Option.when(details(State.Detail.facts))(
            proper_list(PIDE_MCP_Markup.facts_json(snapshot, range)).map("facts" -> _)).flatten,
          Option.when(details(State.Detail.markup))(
            "markup" -> PIDE_MCP_Markup.markup_json(snapshot, range, Markup.Elements.full)))
      JSON_Object.flatten(entries)
    }
  }

  object States {
    def command_spans(
      snapshot: Document.Snapshot,
      theory_cmd: Command,
      range: Option[Text.Range]
    ): Iterator[State] =
      snapshot.command_spans(PIDE_MCP_Util.restrict_text_range(snapshot.node.source, range))
        .iterator.map { span =>
          val restricted = PIDE_MCP_Util.intersect_range(span.range, range)
          State(snapshot, theory_cmd, restricted,
            Rendering.text_messages(snapshot, restricted).flatMap(info =>
              PIDE_MCP_Message.Message_Kind.of(info.info).map(kind =>
                PIDE_MCP_Message.Message(kind, info.info, List(info.range), snapshot,
                  Some(theory_cmd)))))
        }

    def apply(
      snapshot: Document.Snapshot,
      selection: List[(Command, Text.Range)]
    ): Iterator[State] =
      single_command(snapshot) match {
        case Some(single) =>
          selection.iterator.filter(_._1 == single).flatMap { case (cmd, range) =>
            if (snapshot.node_name.is_theory) command_spans(snapshot, cmd, Some(range))
            else {
              val messages = PIDE_MCP_Message.messages(snapshot, cmd, 0, range).toList
              Iterator(State(snapshot, cmd, range, messages))
            }
          }
        case None =>
          val starts = snapshot.node.command_iterator().toMap
          for ((cmd, part) <- selection.iterator; start <- starts.get(cmd)) yield {
            val range = part + start
            State(snapshot, cmd, range,
              PIDE_MCP_Message.messages(snapshot, cmd, start, range).toList)
          }
      }

    def apply(snapshot: Document.Snapshot, range: Option[Text.Range]): Iterator[State] =
      apply(snapshot, select(snapshot, range))

    sealed case class Args(
      summary_message_kinds: List[PIDE_MCP_Message.Message_Kind],
      problematic_limit: Option[Int],
      unproblematic_limit: Option[Int],
      occurrences_limit: Option[Int],
      snippet_lines: Int)

    def json(
      states: Iterator[State],
      doc: Line.Document,
      details: Set[State.Detail],
      args: Args
    ): JSON.Object.T = {
      def redundant_problem(state: State, problem: Problem): Boolean =
        problem.text.isEmpty && problem.message_kind.exists(state.messages_of(_).nonEmpty)
      val counts = mutable.Map[String, Int]().withDefaultValue(0)
      val occurrences = mutable.Map[String, mutable.ListBuffer[JSON.Object.T]]()
      val returned_commands = new mutable.ListBuffer[JSON.Object.T]
      var total_timing_ms = 0L
      var count = 0
      var count_problematic = 0
      var count_unproblematic = 0
      var count_listed_occurrences = 0
      val include_details = details.nonEmpty
      for (state <- states if !state.is_ignorable_now) {
        count += 1
        total_timing_ms += state.timing_ms
        for (status <- state.status if Status.unproblematic(status)) counts(status.name) += 1
        val problems = state.problems.filter(
          _.message_kind.forall(args.summary_message_kinds.contains))
        for (problem <- problems) {
          counts(problem.key) += 1
          if (args.occurrences_limit.forall(count_listed_occurrences < _) &&
              !redundant_problem(state, problem)) {
            occurrences.getOrElseUpdate(problem.key, new mutable.ListBuffer[JSON.Object.T]) +=
              problem.json(doc, args.snippet_lines)
            count_listed_occurrences += 1
          }
        }
        if (include_details) {
          if (problems.nonEmpty) {
            if (args.problematic_limit.forall(count_problematic < _))
              returned_commands += state.json(doc, details)
            count_problematic += 1
          }
          else {
            if (args.unproblematic_limit.forall(count_unproblematic < _))
              returned_commands += state.json(doc, details)
            count_unproblematic += 1
          }
        }
      }
      def occurrences_entry(
        key: String,
        message_kind: Option[PIDE_MCP_Message.Message_Kind]
      ): Option[JSON.Object.Entry] =
        Option.when(counts(key) > 0) {
          val name = message_kind.fold("occurrences")("without_" + _)
          key -> (JSON_Object("count" -> counts(key)) ++
            JSON.optional(name -> occurrences.get(key).map(_.toList)))
        }
      def count_entry(key: String): Option[JSON.Object.Entry] =
        Option.when(counts(key) > 0)(key -> counts(key))

      Status.prefix_keys(JSON_Object.flatten(
        Some("total_timing_ms" -> total_timing_ms) ::
        List(Status.running, Status.failed, Status.warned, Status.canceled)
          .map(status => occurrences_entry(status.name, status.message_kind)) :::
        List(bad, PIDE_MCP_Message.Message_Kind.errors.toString,
          PIDE_MCP_Message.Message_Kind.warnings.toString)
          .map(occurrences_entry(_, None)) :::
        List(Status.unprocessed, Status.finished).map(status => count_entry(status.name)))) +
      ("cmds" -> (JSON_Object("count" -> count) ++
        JSON_Object.if_proper(include_details, JSON_Object(
          "count_returned" -> returned_commands.length, "cmds" -> returned_commands.toList))))
    }
  }

  def await(
    session: PIDE_MCP_Session,
    commands: List[Command],
    stop: List[Document_Status.Command_Status] => Boolean,
    timeout: Option[Time],
    delay: Time,
    progress: Progress
  ): (Document.Snapshot, List[Command], Boolean) = {
    val start = Time.now()
    var terminated = 0
    session.await_while_ready(progress, s"commands ($terminated/${commands.length} terminated)",
      delay) {
      val snapshot = session.snapshot()
      val survivors = commands.filter(cmd =>
        snapshot.version.nodes(cmd.node_name).commands.contains(cmd))
      val statuses = survivors.map(snapshot.state.command_status(snapshot.version, _))
      terminated = statuses.count(_.is_terminated)
      val stopped = stop(statuses)
      Option.when(stopped || timeout.exists(Time.now() - start >= _))(
        (snapshot, survivors, !stopped))
    }
  }

  def await_states(
    session: PIDE_MCP_Session,
    snapshot: Document.Snapshot,
    range: Option[Text.Range],
    stop: List[Document_Status.Command_Status] => Boolean,
    timeout: Option[Time],
    delay: Time,
    progress: Progress
  ): (Document.Snapshot, Iterator[State], Int, Boolean) = {
    val selection = select(snapshot, range)
    val commands = selection.filterNot { case sel @ (cmd, _) =>
      cmd.is_ignored && States(snapshot, List(sel)).forall(_.is_ignorable_for_good) }.map(_._1)
    val (snapshot1, survivors, timed_out) = await(session, commands, stop, timeout, delay, progress)
    val node_snapshot = session.switch(snapshot1, snapshot.node_name)
    (node_snapshot, States(node_snapshot, selection),
      commands.count(cmd => !cmd.is_ignored && !survivors.contains(cmd)), timed_out)
  }
}
