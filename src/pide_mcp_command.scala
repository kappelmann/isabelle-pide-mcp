/*  Title:      PIDE_MCP/pide_mcp_command.scala
    Author:     Kevin Kappelmann

Command utilities.
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.mutable

object PIDE_MCP_Command {
  enum Message_Kind { case goals, errors, warnings, writeln, information, tracing }
  object Message_Kind {
    val info: List[Message_Kind] = List(writeln, information, tracing)
    val all: List[Message_Kind] = values.toList

    def of(elem: XML.Elem): Option[Message_Kind] =
      if (Protocol.is_state(elem)) Some(goals)
      else if (Protocol.is_error(elem)) Some(errors)
      else if (Protocol.is_warning_or_legacy(elem)) Some(warnings)
      else if (Protocol.is_writeln(elem)) Some(writeln)
      else if (Protocol.is_information(elem)) Some(information)
      else if (Protocol.is_tracing(elem)) Some(tracing)
      else None

    require(Markup.messages.keySet.forall(name => of(XML.Elem(Markup(name, Nil), Nil)).isDefined),
      "Unclassified message kinds")
  }

  sealed case class Message(kind: Message_Kind, elem: XML.Elem, ranges: List[Text.Range]) {
    lazy val text: String = PIDE_MCP_Util.elem_body_plain_text(elem)
  }

  enum Status(val name: String, val message_kind: Option[Message_Kind] = None) {
    case unprocessed extends Status("unprocessed")
    // running confuses agents: "running" = "it worked"
    case running extends Status("processing")
    case failed extends Status("failed", Some(Message_Kind.errors))
    case warned extends Status("warned", Some(Message_Kind.warnings))
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

  val bad = Markup.BAD
  val Markup_ML = "ML"

  private def statuses(status: Document_Status.Command_Status): List[Status] =
    List(
      Option.when(status.is_unprocessed)(Status.unprocessed),
      Option.when(status.is_running)(Status.running),
      Option.when(status.is_failed)(Status.failed),
      Option.when(status.is_warned)(Status.warned),
      Option.when(status.is_finished)(Status.finished),
      Option.when(status.is_canceled)(Status.canceled)).flatten

  def iterator(
    snapshot: Document.Snapshot,
    range: Option[Text.Range]
  ): Iterator[(Command, Text.Offset)] =
    range.fold(snapshot.node.command_iterator())(snapshot.node.command_iterator(_))

  // like select, but returns all covering markups (full markup stack) at each sub-range
  private def select_covering(
    snapshot: Document.Snapshot,
    range: Text.Range,
    elements: Markup.Elements
  ): List[Text.Info[List[Text.Info[XML.Elem]]]] =
    snapshot.cumulate(range, List.empty[Text.Info[XML.Elem]], elements,
      _ => { case (acc, info) => Some(info :: acc) })

  def facts_json(snapshot: Document.Snapshot, range: Text.Range): List[String] = {
    val fact_kinds = Set(Markup.FACT, Markup.DYNAMIC_FACT, Markup.LITERAL_FACT)
    snapshot.select(range, Markup.Elements(Markup.ENTITY), _ => {
      case Text.Info(_, XML.Elem(Markup.Entity(entry), _)) if fact_kinds.contains(entry.kind) =>
        Some(entry.name)
      case _ => None
    }).map(_.info)
  }

  // this is hairy, but I think there is no good library function to obtain type information
  def types_json(snapshot: Document.Snapshot, range: Text.Range): List[JSON.Object.T] = {
    val term_kinds = Set(Markup.FREE, Markup.BOUND, Markup.VAR, Markup.SKOLEM, Markup.CONST)
    val elements =
      Markup.Elements(term_kinds.toSeq :+ Markup.TYPING :+ Markup.ENTITY :+ Markup.ML_TYPING: _*)

    def kind_and_type(at_range: List[Text.Info[XML.Elem]]): Option[(String, String)] =
      at_range.collectFirst { case Text.Info(_, e) if e.name == Markup.ML_TYPING =>
        (Markup_ML, XML.content(e.body))
      }.orElse(for {
        kind_elem <- at_range.collectFirst {
          case Text.Info(_, e) if term_kinds.contains(e.name) => e }
        typing_elem <- at_range.collectFirst {
          case Text.Info(_, e) if e.name == Markup.TYPING => e }
      } yield (kind_elem.name, XML.content(typing_elem.body)))

    select_covering(snapshot, range, elements).flatMap {
      case Text.Info(r, infos @ (info :: _)) if info.range == r => // only keep leaf nodes
        val at_range = infos.filter(_.range == r)
        val name_at = PIDE_MCP_Util.display_name(
          at_range.collectFirst { case Text.Info(_, XML.Elem(Markup.Entity(entry), _)) => entry },
          r, snapshot.node.source)
        kind_and_type(at_range).map { case (kind, t) =>
          JSON_Object("name" -> name_at, "kind" -> kind, "type" -> t)
        }
      case _ => None
    }.distinct
  }

  def markup_json(
    snapshot: Document.Snapshot,
    range: Text.Range,
    elements: Markup.Elements
  ): List[JSON.Object.T] =
    select_covering(snapshot, range, elements).flatMap {
      case Text.Info(r, infos) =>
        infos.filter(_.range == r).map(i => PIDE_MCP_JSON.from_xml(i.info))
    }

  private def self_id(
    snapshot: Document.Snapshot,
    cmd: Command
  )(id: Document_ID.Generic): Boolean =
    id == cmd.id || snapshot.state.lookup_id(id).exists(_.command.id == cmd.id)

  private val here: String = Symbol.decode("\\<^here>")

  def output_positions(snapshot: Document.Snapshot, cmd: Command, elem: XML.Elem): XML.Elem = {
    def same_file(file: String): Boolean =
      file == snapshot.node_name.node ||
        Path.is_valid(file) && Path.explode(file).expand.implode == snapshot.node_name.node
    def line_text(line: Int, file: String): String =
      "(line " + line + if_proper(!same_file(file), " of " + quote(file)) + ")"
    def output(body: XML.Body): XML.Body =
      body.flatMap {
        case XML.Elem(Markup(Markup.POSITION, Position.Item_Id(id, range)), List(XML.Text(text)))
        if text == here =>
          if (self_id(snapshot, cmd)(id)) Nil
          else snapshot.find_command_position(id, range.start)
            .map(pos => XML.Text(" " + line_text(pos.line1, pos.name))).toList
        case XML.Elem(markup @ Markup(Markup.POSITION, props @ Position.Line(line)), _)
        if Position.File.unapply(props).exists(same_file) =>
          List(XML.Elem(markup, XML.string(line_text(line, snapshot.node_name.node))))
        case XML.Elem(markup, body1) => List(XML.Elem(markup, output(body1)))
        case text => List(text)
      }
    XML.Elem(elem.markup, output(elem.body))
  }

  def messages(
    snapshot: Document.Snapshot,
    cmd: Command,
    command_start: Text.Offset,
    range: Text.Range
  ): Iterator[Message] = {
    val chunk_name = snapshot.commands_loading.headOption match {
        case None => Symbol.Text_Chunk.Default
        case Some(_) => Symbol.Text_Chunk.File(snapshot.node_name.node)
      }
    val chunk = cmd.chunks.get(chunk_name)
    def message_ranges(elem: XML.Elem): List[Text.Range] = {
      val positions =
        chunk.fold(Set.empty[Text.Range])(
          cmd.message_positions(self_id(snapshot, cmd), chunk_name, _, elem))
      if (positions.isEmpty) List(range)
      else positions.toList.map(_ + command_start).filter(_.overlaps(range))
        .sortBy(_.start).map(_.restrict(range))
    }
    def output_message(elem: XML.Elem): XML.Elem = // cf. Editor.output
      elem match {
        case Protocol.ML_Profiling(report) => XML.Elem(elem.markup, XML.string(report.print))
        case _ => output_positions(snapshot, cmd, elem)
      }
    snapshot.command_results(cmd).iterator.flatMap((_, elem) =>
      for {
        kind <- Message_Kind.of(elem)
        ranges = message_ranges(elem) if ranges.nonEmpty
      } yield Message(kind, output_message(elem), ranges))
  }

  def messages_json(
    messages: Map[Message_Kind, List[Message]],
    kinds: List[Message_Kind]
  ): List[Option[JSON.Object.Entry]] =
    kinds.map(kind => messages.get(kind).map(msgs => kind.toString -> msgs.map(_.text)))

  enum Problem {
    case status(status: Status, range: Text.Range, timing_ms: Long)
    case bad(info: Text.Info[String])
    case message(msg: Message)

    def key: String =
      this match {
        case Problem.status(status, _, _) => status.name
        case Problem.bad(_) => PIDE_MCP_Command.bad
        case Problem.message(msg) => msg.kind.toString
      }

    def message_kind: Option[Message_Kind] =
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
      def of(kind: Message_Kind): Detail =
        kind match {
          case Message_Kind.goals => goals
          case Message_Kind.errors => errors
          case Message_Kind.warnings => warnings
          case Message_Kind.writeln | Message_Kind.information | Message_Kind.tracing => infos
        }
      require(
        Message_Kind.all.forall(kind => Message_Kind.info.contains(kind) == (of(kind) == infos)),
        "Inconsistent info message kinds")
    }
  }

  sealed case class State private[PIDE_MCP_Command](
    snapshot: Document.Snapshot,
    cmd: Command,
    range: Text.Range,
    messages: List[Message]
  ) {
    lazy val command_status: Document_Status.Command_Status =
      snapshot.state.command_status(snapshot.version, cmd)
    // cf. Rendering.overview_color
    private lazy val range_status: Document_Status.Command_Status = {
      val markups =
        snapshot.cumulate[List[Markup]](range, Nil, Document_Status.Command_Status.liberal_elements,
          _ => { case (status, Text.Info(_, elem)) => Some(elem.markup :: status) }, status = true)
      Document_Status.Command_Status.make(Date.now(), markups = markups.flatMap(_.info))
    }
    lazy val status: List[Status] =
      statuses(command_status).filter {
        case Status.warned => range_status.is_warned
        case Status.failed => range_status.is_failed
        case _ => true
      }
    lazy val timing_ms: Long = command_status.timings.sum(Date.now()).ms
    private lazy val messages_by_kind: Map[Message_Kind, List[Message]] = messages.groupBy(_.kind)
    def messages_of(kind: Message_Kind): List[Message] = messages_by_kind.getOrElse(kind, Nil)
    lazy val errors: List[Message] = messages_of(Message_Kind.errors)
    lazy val warnings: List[Message] = messages_of(Message_Kind.warnings)
    lazy val bad: List[Text.Info[String]] =
      snapshot.select(range, Markup.Elements(Markup.BAD), _ => {
        case Text.Info(r, elem) => Some(Text.Info(r, PIDE_MCP_Util.elem_body_plain_text(elem)))
      }).map(_.info).filterNot(info => errors.nonEmpty && info.info.isEmpty)
    private def bad_json(doc: Line.Document): List[JSON.Object.T] =
      bad.map { case Text.Info(r, text) =>
        JSON_Object.flatten(
          proper_string(text).map("message" -> _),
          Some("src" ->
            PIDE_MCP_Util.numbered_lines(r.substring(snapshot.node.source),
              doc.position(r.start).line1)))
      }

    def problem(st: Status): Problem = Problem.status(st, range, timing_ms)
    lazy val problems: List[Problem] =
      status.filter(Status.problematic).map(problem) :::
      bad.map(Problem.bad(_)) :::
      (errors ::: warnings).map(Problem.message(_))

    def is_ignorable: Boolean = cmd.is_ignored && messages.isEmpty && problems.isEmpty

    def json(doc: Line.Document, details: Set[State.Detail]): JSON.Object.T = {
      lazy val source_line = doc.position(range.start).line1
      lazy val source = range.substring(snapshot.node.source).stripLineEnd
      val entries: List[Option[JSON.Object.Entry]] =
        List(
          proper_list(status.map(_.name)).map("status" -> _),
          Option.when(timing_ms > 0)("timing_ms" -> timing_ms),
          Some(
            if (details(State.Detail.src))
              "src" -> PIDE_MCP_Util.numbered_lines(source, source_line)
            else "lines" -> PIDE_MCP_Util.line_range(source_line,
              doc.position(range.start + source.length).line1)),
          proper_list(bad_json(doc)).map(PIDE_MCP_Command.bad -> _)) :::
        messages_json(messages_by_kind,
          Message_Kind.all.filter(kind => details(State.Detail.of(kind)))) :::
        List(
          Option.when(details(State.Detail.types))(
            proper_list(types_json(snapshot, range)).map("types" -> _)).flatten,
          Option.when(details(State.Detail.facts))(
            proper_list(facts_json(snapshot, range)).map("facts" -> _)).flatten,
          Option.when(details(State.Detail.markup))(
            "markup" -> markup_json(snapshot, range, Markup.Elements.full)))
      JSON_Object.flatten(entries)
    }
  }

  object States {
    def commands(snapshot: Document.Snapshot, range: Option[Text.Range]): Iterator[State] =
      iterator(snapshot, range).map { case (cmd, command_start) =>
        val restricted = PIDE_MCP_Util.intersect_range(cmd.range + command_start, range)
        State(snapshot, cmd, restricted, messages(snapshot, cmd, command_start, restricted).toList)
      }

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
              Message_Kind.of(info.info).map(
                Message(_, output_positions(snapshot, theory_cmd, info.info), List(info.range)))))
        }

    def blob(snapshot: Document.Snapshot, range: Option[Text.Range]): Option[State] =
      snapshot.commands_loading.headOption.map { cmd =>
        val restricted = PIDE_MCP_Util.restrict_text_range(snapshot.node.source, range)
        State(snapshot, cmd, restricted, messages(snapshot, cmd, 0, restricted).toList)
      }

    def apply(snapshot: Document.Snapshot, range: Option[Text.Range]): Iterator[State] =
      if (!snapshot.node_name.is_theory) blob(snapshot, range).iterator
      else {
        snapshot.node.get_theory match {
          case Some(theory_cmd) => command_spans(snapshot, theory_cmd, range)
          case None => commands(snapshot, range)
        }
      }

    val default_snippet_lines = 1
    sealed case class Options(
      summary_message_kinds: List[Message_Kind],
      problematic_limit: Option[Int] = None,
      unproblematic_limit: Option[Int] = None,
      occurrences_limit: Option[Int] = None,
      snippet_lines: Int = default_snippet_lines)

    def json(
      states: Iterator[State],
      doc: Line.Document,
      details: Set[State.Detail],
      opts: Options
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
      for (state <- states if !state.is_ignorable) {
        count += 1
        total_timing_ms += state.timing_ms
        for (status <- state.status if Status.unproblematic(status)) counts(status.name) += 1
        val problems =
          state.problems.filter(_.message_kind.forall(opts.summary_message_kinds.contains))
        for (problem <- problems) {
          counts(problem.key) += 1
          if (opts.occurrences_limit.forall(count_listed_occurrences < _) &&
              !redundant_problem(state, problem)) {
            occurrences.getOrElseUpdate(problem.key, new mutable.ListBuffer[JSON.Object.T]) +=
              problem.json(doc, opts.snippet_lines)
            count_listed_occurrences += 1
          }
        }
        if (include_details) {
          if (problems.nonEmpty) {
            if (opts.problematic_limit.forall(count_problematic < _))
              returned_commands += state.json(doc, details)
            count_problematic += 1
          }
          else {
            if (opts.unproblematic_limit.forall(count_unproblematic < _))
              returned_commands += state.json(doc, details)
            count_unproblematic += 1
          }
        }
      }
      def occurrences_entry(
        key: String,
        message_kind: Option[Message_Kind]
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
        List(bad, Message_Kind.errors.toString, Message_Kind.warnings.toString)
          .map(occurrences_entry(_, None)) :::
        List(Status.unprocessed, Status.finished).map(status => count_entry(status.name)))) +
      ("cmds" -> (JSON_Object("count" -> count) ++
        JSON_Object.if_proper(include_details, JSON_Object(
          "count_returned" -> returned_commands.length, "cmds" -> returned_commands.toList))))
    }
  }
}
