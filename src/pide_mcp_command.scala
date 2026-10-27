/*  Title:      PIDE_MCP/pide_mcp_command.scala
    Author:     Kevin Kappelmann

Command utilities.
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.mutable

object PIDE_MCP_Command {
  enum Status(val name: String) {
    case unprocessed extends Status("unprocessed")
    // experimental verbose name: remind agents of possible non-termination
    case running extends Status("still_running_possibly_nonterminating")
    case warned extends Status("warned")
    case failed extends Status("failed")
    case finished extends Status("finished")
    case canceled extends Status("canceled")
  }
  object Status {
    def key(name: String): String = "commands_" + name

    def prefix_keys(obj: JSON.Object.T): JSON.Object.T = {
      val keys = values.map(_.name).toSet + bad
      obj.map { case (k, v) => if (keys.contains(k)) (key(k), v) else (k, v) }
    }
  }

  enum Message_Kind { case goals, errors, warnings, writeln, information, tracing }
  object Message_Kind {
    val important: List[Message_Kind] = List(goals, errors, warnings)
    val info: List[Message_Kind] = List(writeln, information, tracing)
    val all: List[Message_Kind] = values.toList
  }

  val bad = "bad"
  val Markup_ML = "ML"

  private def statuses(status: Document_Status.Command_Status): List[Status] =
    List(
      Option.when(status.is_unprocessed)(Status.unprocessed),
      Option.when(status.is_running)(Status.running),
      Option.when(status.is_warned)(Status.warned),
      Option.when(status.is_failed)(Status.failed),
      Option.when(status.is_finished)(Status.finished),
      Option.when(status.is_canceled)(Status.canceled)).flatten

  def iterator(snapshot: Document.Snapshot, range: Option[Text.Range]): Iterator[(Command, Text.Offset)] =
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

  def results(
    snapshot: Document.Snapshot,
    cmd: Command,
    command_start: Text.Offset,
    range: Text.Range
  ): Iterator[(XML.Elem, List[Text.Range])] = {
    val chunk_name = snapshot.commands_loading.headOption match {
        case None => Symbol.Text_Chunk.Default
        case Some(_) => Symbol.Text_Chunk.File(snapshot.node_name.node)
      }
    val chunk = cmd.chunks.get(chunk_name)
    def message_ranges(elem: XML.Elem): List[Text.Range] = {
      val positions =
        chunk.fold(Set.empty[Text.Range])(cmd.message_positions(self_id(snapshot, cmd), chunk_name, _, elem))
      if (positions.isEmpty) List(range)
      else positions.toList.map(_ + command_start).filter(_.overlaps(range)).sortBy(_.start).map(_.restrict(range))
    }
    def output_message(elem: XML.Elem): XML.Elem = // cf. Editor.output
      elem match {
        case Protocol.ML_Profiling(report) => XML.Elem(elem.markup, XML.string(report.print))
        case _ => elem
      }
    snapshot.command_results(cmd).iterator.collect {
      case (_, elem: XML.Elem) => output_message(elem) -> message_ranges(elem)
    }.filter(_._2.nonEmpty)
  }

  def messages_json(
    elements: Iterator[XML.Elem],
    kinds: List[Message_Kind]
  ): List[Option[JSON.Object.Entry]] = {
    val texts = elements.flatMap { elem =>
        val text = PIDE_MCP_Util.elem_body_plain_text(elem)
        if (Protocol.is_state(elem)) Some(Message_Kind.goals -> text)
        else if (Protocol.is_error(elem)) Some(Message_Kind.errors -> text)
        else if (Protocol.is_warning_or_legacy(elem)) Some(Message_Kind.warnings -> text)
        else if (Protocol.is_writeln(elem)) Some(Message_Kind.writeln -> text)
        else if (Protocol.is_information(elem)) Some(Message_Kind.information -> text)
        else if (Protocol.is_tracing(elem)) Some(Message_Kind.tracing -> text)
        else None
      }.toList.groupMap(_._1)(_._2)
    kinds.map(kind => texts.get(kind).map(kind.toString -> _))
  }

  sealed case class Problem(
    key: String,
    message_kind: Option[Message_Kind],
    ranges: List[Text.Range],
    elem: Option[XML.Elem])

  object State {
    sealed case class Options(
      message_kinds: List[Message_Kind],
      include_types: Boolean = false,
      include_facts: Boolean = false,
      include_full_markup: Boolean = false)
  }

  sealed case class State private[PIDE_MCP_Command](
    snapshot: Document.Snapshot,
    cmd: Command,
    range: Text.Range,
    results: List[(XML.Elem, List[Text.Range])]
  ) {
    lazy val command_status: Document_Status.Command_Status =
      snapshot.state.command_status(snapshot.version, cmd)

    private def has_markup(elements: Markup.Elements): Boolean =
      snapshot.select(range, elements, _ => { case _ => Some(()) }).nonEmpty

    lazy val status: List[Status] =
      statuses(command_status).filter { // range filter
        case Status.warned => has_markup(Markup.Elements(Markup.WARNING, Markup.LEGACY))
        case Status.failed => has_markup(Markup.Elements(Markup.FAILED, Markup.ERROR))
        case _ => true
      }
    lazy val timing_ms: Long = command_status.timings.sum(Date.now()).ms
    lazy val bad: List[Text.Markup] =
      snapshot.select(range, Markup.Elements(Markup.BAD), _ => Some(_)).map(_.info)
    private def bad_json(doc: Line.Document): List[JSON.Object.T] = bad.map { case Text.Info(r, elem) =>
      JSON_Object.flatten(
        proper_string(PIDE_MCP_Util.elem_body_plain_text(elem)).map("message" -> _),
        Some("source" ->
          PIDE_MCP_Util.numbered_lines(r.substring(snapshot.node.source), doc.position(r.start).line1)))
    }
    lazy val errors: List[(XML.Elem, List[Text.Range])] = results.filter(r => Protocol.is_error(r._1))
    lazy val warnings: List[(XML.Elem, List[Text.Range])] =
      results.filter(r => Protocol.is_warning_or_legacy(r._1))
    lazy val problems: List[Problem] =
      status.filterNot(Set(Status.unprocessed, Status.finished)).map { status =>
        val message_kind = status match {
          case Status.warned => Some(Message_Kind.warnings)
          case Status.failed => Some(Message_Kind.errors)
          case _ => None
        }
        Problem(status.name, message_kind, List(range), None)
      } :::
      bad.map(info => Problem(PIDE_MCP_Command.bad, None, List(info.range), Some(info.info))) :::
      errors.map((elem, ranges) =>
        Problem(Message_Kind.errors.toString, Some(Message_Kind.errors), ranges, Some(elem))) :::
      warnings.map((elem, ranges) =>
        Problem(Message_Kind.warnings.toString, Some(Message_Kind.warnings), ranges, Some(elem)))
    def is_ignorable: Boolean = cmd.is_ignored && results.isEmpty && problems.isEmpty

    def json(doc: Line.Document, opts: State.Options): JSON.Object.T = {
      val source_line = doc.position(range.start).line1
      val source = range.substring(snapshot.node.source).stripLineEnd
      val elements = results.map(_._1)
      val entries: List[Option[JSON.Object.Entry]] =
        List(
          proper_list(status.map(_.name)).map("status" -> _),
          Some("timing_ms" -> timing_ms),
          Some("source" -> PIDE_MCP_Util.numbered_lines(source, source_line)),
          proper_list(bad_json(doc)).map(PIDE_MCP_Command.bad -> _)) :::
        messages_json(elements.iterator, opts.message_kinds.filter(Message_Kind.important.contains)) :::
        List(
          Option.when(opts.include_types)(
            proper_list(types_json(snapshot, range)).map("types" -> _)).flatten,
          Option.when(opts.include_facts)(
            proper_list(facts_json(snapshot, range)).map("facts" -> _)).flatten) :::
        messages_json(elements.iterator, opts.message_kinds.filter(Message_Kind.info.contains)) :::
        List(
          Option.when(opts.include_full_markup)(
            "markup" -> markup_json(snapshot, range, Markup.Elements.full)))
      JSON_Object.flatten(entries)
    }
  }

  object States {
    def commands(snapshot: Document.Snapshot, range: Option[Text.Range]): Iterator[State] =
      iterator(snapshot, range).map { case (cmd, command_start) =>
        val restricted = PIDE_MCP_Util.intersect_range(cmd.range + command_start, range)
        State(snapshot, cmd, restricted, results(snapshot, cmd, command_start, restricted).toList)
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
            Rendering.text_messages(snapshot, restricted).map(info => info.info -> List(info.range)))
        }

    def blob(snapshot: Document.Snapshot, range: Option[Text.Range]): Option[State] =
      snapshot.commands_loading.headOption.map { cmd =>
        val restricted = PIDE_MCP_Util.restrict_text_range(snapshot.node.source, range)
        State(snapshot, cmd, restricted, results(snapshot, cmd, 0, restricted).toList)
      }

    def apply(snapshot: Document.Snapshot, range: Option[Text.Range]): Iterator[State] =
      if (!snapshot.node_name.is_theory) blob(snapshot, range).iterator
      else {
        snapshot.node.get_theory match {
          case Some(theory_cmd) => command_spans(snapshot, theory_cmd, range)
          case None => commands(snapshot, range)
        }
      }

    sealed case class Options(
      include_details: Boolean = false,
      problematic_limit: Option[Int] = None,
      unproblematic_limit: Option[Int] = None,
      snippet_lines: Int = 1)

    def json(
      states: Iterator[State],
      doc: Line.Document,
      state_opts: State.Options,
      opts: Options
    ): JSON.Object.T = {
      def snippets(ranges: List[Text.Range]): List[String] =
        ranges.map { range =>
          val start = doc.position(range.start).line1
          val (shown, rest) =
            Line.logical_lines(range.substring(doc.text).stripLineEnd).splitAt(opts.snippet_lines)
          val omitted = Option.when(rest.nonEmpty)(
            PIDE_MCP_Util.line_range(start + shown.length, start + shown.length + rest.length - 1) + ": ...")
          cat_lines(proper_string(PIDE_MCP_Util.numbered_lines(shown, start)).toList ::: omitted.toList)
        }
      def occurrence(state: State, problem: Problem): JSON.Object.T =
        JSON_Object("source_snippets" -> snippets(problem.ranges)) ++
        JSON.optional("message" ->
          problem.elem.flatMap(elem => proper_string(PIDE_MCP_Util.elem_body_plain_text(elem)))) ++
        JSON_Object.if_proper(problem.key == Status.running.name, JSON_Object("timing_ms" -> state.timing_ms))
      val counts = mutable.Map[String, Int]().withDefaultValue(0)
      val occurrences = mutable.Map[String, mutable.ListBuffer[JSON.Object.T]]()
      val returned_commands = new mutable.ListBuffer[JSON.Object.T]
      var total_timing_ms = 0L
      var count = 0
      var count_problematic = 0
      var count_unproblematic = 0
      for (state <- states if !state.is_ignorable) {
        count += 1
        total_timing_ms += state.timing_ms
        for (status <- state.status) counts(status.name) += 1
        val problems = state.problems.filter(_.message_kind.forall(state_opts.message_kinds.contains))
        for (problem <- problems) {
          occurrences.getOrElseUpdate(problem.key, new mutable.ListBuffer[JSON.Object.T]) +=
            occurrence(state, problem)
        }
        if (opts.include_details) {
          if (problems.nonEmpty) {
            if (opts.problematic_limit.forall(count_problematic < _))
              returned_commands += state.json(doc, state_opts)
            count_problematic += 1
          }
          else {
            if (opts.unproblematic_limit.forall(count_unproblematic < _))
              returned_commands += state.json(doc, state_opts)
            count_unproblematic += 1
          }
        }
      }
      def occurrences_entry(key: String): Option[JSON.Object.Entry] =
        occurrences.get(key).map(occs =>
          key -> JSON_Object("count" -> occs.length, "occurrences" -> occs.toList))
      def count_entry(key: String): Option[JSON.Object.Entry] =
        Option.when(counts(key) > 0)(key -> counts(key))

      Status.prefix_keys(JSON_Object.flatten(
        Some("total_timing_ms" -> total_timing_ms) ::
        (List(Status.running, Status.warned, Status.failed, Status.canceled).map(_.name) :::
          List(bad, Message_Kind.errors.toString, Message_Kind.warnings.toString)).map(occurrences_entry) :::
        List(Status.unprocessed, Status.finished).map(status => count_entry(status.name)))) +
      ("commands" -> (JSON_Object("count" -> count) ++
        JSON_Object.if_proper(opts.include_details, JSON_Object(
          "count_returned" -> returned_commands.length, "commands" -> returned_commands.toList))))
    }
  }
}
