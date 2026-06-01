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

  def status(snapshot: Document.Snapshot, command: Command): Document_Status.Command_Status =
    snapshot.state.command_status(snapshot.version, command)

  private def status_list(status: Document_Status.Command_Status): List[String] =
    List(
      Option.when(status.is_unprocessed)(Status.unprocessed),
      Option.when(status.is_running)(Status.running),
      Option.when(status.is_warned)(Status.warned),
      Option.when(status.is_failed)(Status.failed),
      Option.when(status.is_finished)(Status.finished),
      Option.when(status.is_canceled)(Status.canceled)).flatten

  private def has_markup(snapshot: Document.Snapshot, range: Text.Range, elements: Markup.Elements)
      : Boolean =
    snapshot.select(range, elements, _ => { case _ => Some(()) }).nonEmpty

  private def warned(snapshot: Document.Snapshot, range: Text.Range): Boolean =
    has_markup(snapshot, range, Markup.Elements(Markup.WARNING, Markup.LEGACY))

  private def failed(snapshot: Document.Snapshot, range: Text.Range): Boolean =
    has_markup(snapshot, range, Markup.Elements(Markup.FAILED, Markup.ERROR))

  private def status_list_range(
    snapshot: Document.Snapshot,
    cmd_status: Document_Status.Command_Status,
    range: Text.Range
  ): List[String] =
    status_list(cmd_status).filter {
      case Status.warned => warned(snapshot, range)
      case Status.failed => failed(snapshot, range)
      case _ => true
    }

  def commands(snapshot: Document.Snapshot, range: Option[Text.Range]): Iterator[(Command, Text.Offset)] =
    range.fold(snapshot.node.command_iterator())(snapshot.node.command_iterator(_))

  /** Like select, but returns all covering markups (full markup stack) at each sub-range. */
  private def select_covering(
    snapshot: Document.Snapshot,
    range: Text.Range,
    elements: Markup.Elements
  ): List[Text.Info[List[Text.Info[XML.Elem]]]] =
    snapshot.cumulate(range, List.empty[Text.Info[XML.Elem]], elements,
      _ => { case (acc, info) => Some(info :: acc) })

  val Markup_ML: String = "ML"

  def types_json( // this is hairy, but I think there is no good library function to obtain type information
    snapshot: Document.Snapshot,
    range: Text.Range
  ): List[JSON.Object.T] = {
    val term_kinds = Set(Markup.FREE, Markup.BOUND, Markup.VAR, Markup.SKOLEM, Markup.CONST)
    val elements = Markup.Elements(term_kinds.toSeq :+ Markup.TYPING :+ Markup.ENTITY :+ Markup.ML_TYPING: _*)

    def kind_and_type(at_range: List[Text.Info[XML.Elem]]): Option[(String, String)] =
      at_range.collectFirst { case Text.Info(_, e) if e.name == Markup.ML_TYPING =>
        (Markup_ML, XML.content(e.body))
      }.orElse(for {
        kind_elem <- at_range.collectFirst { case Text.Info(_, e) if term_kinds.contains(e.name) => e }
        typing_elem <- at_range.collectFirst { case Text.Info(_, e) if e.name == Markup.TYPING => e }
      } yield (kind_elem.name, XML.content(typing_elem.body)))

    select_covering(snapshot, range, elements).flatMap {
      case Text.Info(r, infos @ (info :: _)) if info.range == r => // only keep leaf nodes
        val at_range = infos.filter(_.range == r)
        val name_at = PIDE_MCP_Util.display_name(
          at_range.collectFirst { case Text.Info(_, XML.Elem(Markup.Entity(entry), _)) => entry },
          r, snapshot.node.source)
        kind_and_type(at_range).map { case (kind, t) =>
          JSON.Object("name" -> name_at, "kind" -> kind, "type" -> t)
        }
      case _ => None
    }.distinct
  }

  def facts_json(snapshot: Document.Snapshot, range: Text.Range): List[String] = {
    val fact_kinds = Set(Markup.FACT, Markup.DYNAMIC_FACT, Markup.LITERAL_FACT)
    snapshot.select(range, Markup.Elements(Markup.ENTITY), _ => {
      case Text.Info(_, XML.Elem(Markup.Entity(entry), _)) if fact_kinds.contains(entry.kind) =>
        Some(entry.name)
      case _ => None
    }).map(_.info)
  }

  def bad_json(snapshot: Document.Snapshot, range: Text.Range): List[String] =
    snapshot.select(range, Markup.Elements(Markup.BAD), _ => {
      case Text.Info(r, _) => Some(r.substring(snapshot.node.source))
    }).map(_.info)

  def markup_json(
    snapshot: Document.Snapshot,
    range: Text.Range,
    elements: Markup.Elements
  ): List[JSON.Object.T] =
    select_covering(snapshot, range, elements).flatMap {
      case Text.Info(r, infos) =>
        infos.filter(_.range == r).map(i => PIDE_MCP_Util.xml_to_json(i.info))
    }

  sealed case class State_Options(
    include_types: Boolean = false,
    include_facts: Boolean = false,
    include_infos: Boolean = false,
    include_full_markup: Boolean = false
  )

  def results(
    snapshot: Document.Snapshot,
    cmd: Command,
    offset: Text.Offset,
    range: Text.Range
  ): Iterator[XML.Elem] =
    snapshot.command_results(cmd).iterator.collect { case (_, elem: XML.Elem) => elem }
      .filter { elem => PIDE_MCP_Util.result_in_range(elem, offset, range) }

  private def span_serials(
    snapshot: Document.Snapshot,
    range: Text.Range
  ): Set[Long] = {
    val elements = Markup.Elements(Markup.WARNING, Markup.LEGACY, Markup.ERROR,
      Markup.WRITELN, Markup.INFORMATION, Markup.STATE)
    snapshot.cumulate[Set[Long]](range, Set.empty, elements,
      _ => { case (serials, Text.Info(_, elem)) =>
        Markup.Serial.unapply(elem.markup.properties).map(serials + _)
      }).foldLeft(Set.empty)(_ ++ _.info)
  }

  private def span_results(
    snapshot: Document.Snapshot,
    cmd: Command,
    range: Text.Range
  ): Iterator[XML.Elem] = {
    val serials = span_serials(snapshot, range)
    snapshot.command_results(cmd).iterator.collect { case (id, elem) if serials.contains(id) => elem }
  }

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

  sealed case class State_Entry(cmd: Command, range: Text.Range, results: List[XML.Elem])

  def state_entry_json(
    snapshot: Document.Snapshot,
    entry: State_Entry,
    doc: Line.Document,
    opts: State_Options
  ): JSON.Object.T = {
    val cmd_status = status(snapshot, entry.cmd)
    val timing_ms = cmd_status.timings.sum(Date.now()).ms
    val texts_by_kind = classify_results(entry.results.iterator)
    val source_text = entry.range.substring(snapshot.node.source).stripLineEnd
    val source_line = doc.position(entry.range.start).line1
    val entries: List[Option[(String, JSON.T)]] = List(
      Some("status" -> status_list_range(snapshot, cmd_status, entry.range)),
      Some("timing_ms" -> timing_ms),
      Some("source" -> PIDE_MCP_Util.numbered_lines(source_text, source_line)),
      proper_list(bad_json(snapshot, entry.range)).map("bad" -> _),
      texts_by_kind.get("goal").map("goal" -> _),
      texts_by_kind.get("error").map("error" -> _),
      texts_by_kind.get("warning").map("warning" -> _),
      Option.when(opts.include_types)(proper_list(types_json(snapshot, entry.range)).map("types" -> _)).flatten,
      Option.when(opts.include_facts)(proper_list(facts_json(snapshot, entry.range)).map("facts" -> _)).flatten,
      Option.when(opts.include_infos)(texts_by_kind.get("writeln").map("writeln" -> _)).flatten,
      Option.when(opts.include_infos)(texts_by_kind.get("information").map("information" -> _)).flatten,
      Option.when(opts.include_full_markup)("markup" -> markup_json(snapshot, entry.range, Markup.Elements.full)))
    JSON.Object(entries.flatten: _*)
  }

  def state_entries_json(
    snapshot: Document.Snapshot,
    entries: List[State_Entry],
    doc: Line.Document,
    opts: State_Options
  ): List[JSON.Object.T] =
    entries.map(state_entry_json(snapshot, _, doc, opts))

  def state_entries_theory_dynamic(
    snapshot: Document.Snapshot,
    range: Option[Text.Range]
  ): Iterator[State_Entry] =
    commands(snapshot, range).map { case (cmd, offset) =>
      val restricted = PIDE_MCP_Util.intersect_range(cmd.range + offset, range)
      State_Entry(cmd, restricted, results(snapshot, cmd, offset, restricted).toList)
    }

  def state_entries_theory_base_session(
    snapshot: Document.Snapshot,
    range: Option[Text.Range]
  ): Exn.Result[Iterator[State_Entry]] = Exn.capture {
    val cmd = snapshot.node.get_theory.get
    snapshot.command_spans(PIDE_MCP_Util.restrict_source_range(snapshot, range)).iterator.map { span =>
      val restricted = PIDE_MCP_Util.intersect_range(span.range, range)
      State_Entry(cmd, restricted, span_results(snapshot, cmd, restricted).toList)
    }
  }

  def state_entry_file(
    snapshot: Document.Snapshot,
    range: Option[Text.Range]
  ): Option[State_Entry] = {
    val restricted = PIDE_MCP_Util.restrict_source_range(snapshot, range)
    PIDE_MCP_Util.find_loading_command(snapshot, snapshot.node_name).map { cmd =>
      State_Entry(cmd, restricted, results(snapshot, cmd, 0, restricted).toList)
    }
  }

  def state_summary_json(snapshot: Document.Snapshot, entries: Iterator[State_Entry]): JSON.Object.T = {
    val cmd_counts = scala.collection.mutable.Map[String, Int]().withDefaultValue(0)
    var total_timing_ms = 0L
    entries.foreach { case State_Entry(cmd, range, res) =>
      val st = status(snapshot, cmd)
      total_timing_ms += st.timings.sum(Date.now()).ms

      for (flag <- status_list_range(snapshot, st, range)) cmd_counts(flag) += 1
      cmd_counts("bad") += bad_json(snapshot, range).length
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

  def definitions_json(
    session: PIDE_MCP_Session,
    snapshot: Document.Snapshot,
    range: Option[Text.Range],
    snippet_lines: Int,
    filter_origins: Set[String],
    definition_kinds: List[String],
    def_entry_not_loaded: String
  ): Exn.Result[Map[String, List[JSON.Object.T]]] = Exn.capture {
    val restricted = PIDE_MCP_Util.restrict_source_range(snapshot, range)
    snapshot.select(restricted, Markup.Elements(Markup.ENTITY), _ => {
      case Text.Info(r, XML.Elem(Markup.Entity(entry), _))
        if definition_kinds.contains(entry.kind) =>
        val name = PIDE_MCP_Util.display_name(Some(entry), r, snapshot.node.source)
        Some(Exn.release(PIDE_MCP_Name_Space_Entry.definition_json(session, snapshot, entry, name,
          snippet_lines, filter_origins, def_entry_not_loaded)))
      case _ => None
    }).flatMap(_.info.toList).groupBy(e => JSON.string(e, "origin").getOrElse("")).map { case (origin, entries) =>
      origin -> entries.sortBy(e => (JSON.int(e, "line"), JSON.string(e, "name"), JSON.string(e, "kind")))
        .distinctBy(e => (JSON.int(e, "line"), JSON.string(e, "name")))
    }
  }
}
