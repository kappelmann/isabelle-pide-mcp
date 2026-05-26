/*  Title:      PIDE_MCP/pide_mcp_commands.scala
    Author:     Kevin Kappelmann

Command utilities for the PIDE MCP server.
*/

package isabelle.pide.mcp

import isabelle._


object PIDE_MCP_Commands {
  object Status {
    val unprocessed = "unprocessed"
    val running = "running"
    val warned = "warned"
    val failed = "failed"
    val finished = "finished"
    val canceled = "canceled"
    val all: List[String] = List(unprocessed, running, warned, failed, finished, canceled)
  }

  def status(snap: Document.Snapshot, command: Command): Document_Status.Command_Status =
    snap.state.command_status(snap.version, command)

  private def status_list(status: Document_Status.Command_Status): List[String] =
    List(
      Option.when(status.is_unprocessed)(Status.unprocessed),
      Option.when(status.is_running)(Status.running),
      Option.when(status.is_warned)(Status.warned),
      Option.when(status.is_failed)(Status.failed),
      Option.when(status.is_finished)(Status.finished),
      Option.when(status.is_canceled)(Status.canceled)).flatten

  /** (Pre)filter for commands overlapping a text range.
    * For session-built theories, the entire theory (loaded with read_theory) is one command,
    * so the filter is too coarse (hence a prefilter) - so we additionally check ranges in results/markup.
    */
  def commands(
    snap: Document.Snapshot,
    range: Option[Text.Range]
  ): Iterator[(Command, Text.Offset)] =
    range.fold(snap.node.command_iterator())(snap.node.command_iterator(_))

  private def restrict_cmd_range(
    cmd: Command,
    offset: Text.Offset,
    range: Option[Text.Range]
  ): Text.Range = {
    val full = cmd.range + offset
    range.flatMap(full.try_restrict).getOrElse(full)
  }

  /** Each result entry contains the (markup, list of ancestor positions and their markups). */
  private def markups_cumulated(
    snap: Document.Snapshot,
    range: Text.Range,
    elements: Markup.Elements
  ): List[Text.Info[(XML.Elem, List[(Text.Range, XML.Elem)])]] =
    snap.cumulate(range, List.empty[(Text.Range, XML.Elem)], elements,
      _ => { case (acc, Text.Info(r, m)) => Some((r, m) :: acc) }
    ).collect { case Text.Info(r, (_, current) :: ancestors) => Text.Info(r, (current, ancestors)) }

  private def markups(
    snap: Document.Snapshot,
    range: Text.Range,
    elements: Markup.Elements
  ): List[Text.Info[XML.Elem]] =
    markups_cumulated(snap, range, elements)
      .map { case Text.Info(r, (current, _)) => Text.Info(r, current) }

  def markup_json(
    snap: Document.Snapshot,
    range: Text.Range,
    elements: Markup.Elements
  ): List[JSON.Object.T] =
    markups(snap, range, elements).map { case Text.Info(_, elem) =>
      PIDE_MCP_Util.xml_to_json(elem)
    }

  val Markup_ML: String = "ML"

  def types_json( // this is hairy, but I think there is no good library function to obtain type information
    snap: Document.Snapshot,
    range: Text.Range
  ): List[JSON.Object.T] = {
    val term_kinds = Set(Markup.FREE, Markup.BOUND, Markup.VAR, Markup.SKOLEM, Markup.CONST)
    val elements =
      Markup.Elements(term_kinds.toSeq :+ Markup.TYPING :+ Markup.ENTITY :+ Markup.ML_TYPING: _*)
    markups_cumulated(snap, range, elements).flatMap { case Text.Info(r, (current, ancestors)) =>
      val at_range = (r, current) :: ancestors.filter { case (er, _) => er == r }
      def name_at: String =
        at_range.collectFirst { case (_, XML.Elem(Markup.Entity(entry), _)) => entry.name }
          .getOrElse(r.substring(snap.node.source))
      at_range.collectFirst { case (_, e) if e.name == Markup.ML_TYPING => e } match {
        case Some(ml_typing) =>
          Some(JSON.Object("name" -> name_at, "kind" -> Markup_ML, "type" -> XML.content(ml_typing.body)))
        case None =>
          for {
            kind_elem <- at_range.collectFirst { case (_, e) if term_kinds.contains(e.name) => e }
            typing_elem <- at_range.collectFirst { case (_, e) if e.name == Markup.TYPING => e }
          } yield
            JSON.Object("name" -> name_at, "kind" -> kind_elem.name, "type" -> XML.content(typing_elem.body))
      }
    }.distinct
  }

  def facts_json(snap: Document.Snapshot, range: Text.Range): List[String] = {
    val fact_kinds = Set(Markup.FACT, Markup.DYNAMIC_FACT, Markup.LITERAL_FACT)
    markups(snap, range, Markup.Elements(Markup.ENTITY)).collect {
      case Text.Info(_, XML.Elem(Markup.Entity(entry), _)) if fact_kinds.contains(entry.kind) => entry.name
    }
  }

  def bad_json(snap: Document.Snapshot, range: Text.Range): List[String] =
    markups(snap, range, Markup.Elements(Markup.BAD))
      .map { case Text.Info(r, _) => r.substring(snap.node.source) }

  sealed case class State_Options(
    include_types: Boolean = false,
    include_facts: Boolean = false,
    include_infos: Boolean = false,
    include_full_markup: Boolean = false
  )

  def results(
    snap: Document.Snapshot,
    cmd: Command,
    offset: Text.Offset,
    range: Option[Text.Range] = None
  ): Iterator[XML.Elem] =
    snap.command_results(cmd).iterator.collect { case (_, elem: XML.Elem) => elem }
      .filter { elem => PIDE_MCP_Util.result_in_range(elem, offset, range) }

  private def classify_results(elements: Iterator[XML.Elem]): Map[String, List[String]] =
    elements.flatMap { elem =>
      val text = PIDE_MCP_Util.elem_body_plain_text(elem)
      if (Protocol.is_state(elem)) Some("goal" -> text)
      else if (Protocol.is_error(elem)) Some("error" -> text)
      else if (Protocol.is_warning_or_legacy(elem)) Some("warning" -> text)
      else if (Protocol.is_writeln(elem)) Some("writeln" -> text)
      else if (Protocol.is_information(elem)) Some("information" -> text)
      else None
    }.toList.groupMap(_._1)(_._2)

  sealed case class State_Entry(cmd: Command, range: Text.Range, results: Iterator[XML.Elem])

  private def state_entry_json(
    snap: Document.Snapshot,
    entry: State_Entry,
    doc: Line.Document,
    opts: State_Options
  ): JSON.Object.T = {
    val cmd_status = status(snap, entry.cmd)
    val texts_by_kind = classify_results(entry.results)
    val source_text = entry.range.substring(snap.node.source)
    val source_line = doc.position(entry.range.start).line1
    val entries: List[Option[(String, JSON.T)]] = List(
      Some("status" -> status_list(cmd_status)),
      Some("timing_ms" -> cmd_status.timings.sum(Date.now()).ms),
      Some("source" -> PIDE_MCP_Util.numbered_lines(source_text, source_line)),
      proper_list(bad_json(snap, entry.range)).map("bad" -> _),
      texts_by_kind.get("goal").map("goal" -> _),
      texts_by_kind.get("error").map("error" -> _),
      texts_by_kind.get("warning").map("warning" -> _),
      Option.when(opts.include_types)(proper_list(types_json(snap, entry.range)).map("types" -> _)).flatten,
      Option.when(opts.include_facts)(proper_list(facts_json(snap, entry.range)).map("facts" -> _)).flatten,
      Option.when(opts.include_infos)(texts_by_kind.get("writeln").map("writeln" -> _)).flatten,
      Option.when(opts.include_infos)(texts_by_kind.get("information").map("information" -> _)).flatten,
      Option.when(opts.include_full_markup)("markup" -> markup_json(snap, entry.range, Markup.Elements.full)))
    JSON.Object(entries.flatten: _*)
  }

  private def state_entries_theory(
    snap: Document.Snapshot,
    range: Option[Text.Range]
  ): Iterator[State_Entry] =
    commands(snap, range).map { case (cmd, offset) =>
      State_Entry(cmd, restrict_cmd_range(cmd, offset, range), results(snap, cmd, offset, range))
    }

  private def state_entry_file(
    snap: Document.Snapshot,
    range: Option[Text.Range]
  ): Option[State_Entry] = {
    val restricted = PIDE_MCP_Util.restrict_source_range(snap, range)
    PIDE_MCP_Util.find_loading_command(snap, snap.node_name).map { cmd =>
      State_Entry(cmd, restricted, results(snap, cmd, 0, Some(restricted)))
    }
  }

  private def state_summary_json(snap: Document.Snapshot, entries: Iterator[State_Entry]): JSON.Object.T = {
    val cmd_counts = scala.collection.mutable.Map[String, Int]().withDefaultValue(0)
    var total_timing_ms = 0L
    entries.foreach { case State_Entry(cmd, range, res) =>
      val st = status(snap, cmd)
      total_timing_ms += st.timings.sum(Date.now()).ms
      for (flag <- status_list(st)) cmd_counts(flag) += 1
      cmd_counts("bad") += bad_json(snap, range).length
      res.foreach { elem =>
        if (Protocol.is_error(elem)) cmd_counts("errors") += 1
        else if (Protocol.is_warning_or_legacy(elem)) cmd_counts("warnings") += 1
      }
    }
    def entry(k: String): (String, JSON.T) = k -> cmd_counts(k)
    JSON.Object(
      "total_timing_ms" -> total_timing_ms,
      entry(Status.unprocessed),
      entry(Status.running),
      entry(Status.warned),
      entry(Status.failed),
      entry(Status.finished),
      entry(Status.canceled),
      entry("bad"),
      entry("errors"),
      entry("warnings"))
  }

  def state_summary_theory_json(
    snap: Document.Snapshot,
    range: Option[Text.Range] = None
  ): JSON.Object.T =
    state_summary_json(snap, state_entries_theory(snap, range))

  def state_summary_file_json(
    snap: Document.Snapshot,
    range: Option[Text.Range] = None
  ): JSON.Object.T =
    state_summary_json(snap, state_entry_file(snap, range).iterator)

  def state_theory_json(
    snap: Document.Snapshot,
    cmd: Command,
    offset: Text.Offset,
    doc: Line.Document,
    range: Option[Text.Range] = None,
    opts: State_Options = State_Options()
  ): JSON.Object.T = {
    val entry = State_Entry(cmd, restrict_cmd_range(cmd, offset, range), results(snap, cmd, offset, range))
    state_entry_json(snap, entry, doc, opts)
  }

  def states_theory_json(
    snap: Document.Snapshot,
    doc: Line.Document,
    range: Option[Text.Range] = None,
    opts: State_Options = State_Options(),
    commands_limit: Int = 500
  ): List[JSON.Object.T] =
    commands(snap, range).take(commands_limit).map { case (cmd, offset) =>
      state_theory_json(snap, cmd, offset, doc, range, opts)
    }.toList

  def states_file_json(
    snap: Document.Snapshot,
    doc: Line.Document,
    range: Option[Text.Range] = None,
    opts: State_Options = State_Options()
  ): List[JSON.Object.T] =
    state_entry_file(snap, range).toList.map(entry => state_entry_json(snap, entry, doc, opts))

  def definitions_json(
    session: PIDE_MCP_Session,
    snap: Document.Snapshot,
    range: Option[Text.Range],
    snippet_lines: Int,
    filter_origins: Set[String],
    definition_kinds: List[String],
    def_entry_not_loaded: String
  ): Exn.Result[Map[String, List[JSON.Object.T]]] = Exn.capture {
    val restricted = PIDE_MCP_Util.restrict_source_range(snap, range)
    markups(snap, restricted, Markup.Elements(Markup.ENTITY)).flatMap {
      case Text.Info(_, XML.Elem(Markup.Entity(entry), _)) if definition_kinds.contains(entry.kind) =>
        Exn.release(PIDE_MCP_Name_Space_Entry.definition_json(session, snap, entry, snippet_lines,
          filter_origins, def_entry_not_loaded))
      case _ => Nil
    }.groupBy(e => JSON.string(e, "origin").getOrElse("")).map { case (origin, entries) =>
      origin -> entries.sortBy(e => (JSON.int(e, "line"), JSON.string(e, "name"), JSON.string(e, "kind")))
        .distinctBy(e => (JSON.int(e, "line"), JSON.string(e, "name")))
    }
  }
}
