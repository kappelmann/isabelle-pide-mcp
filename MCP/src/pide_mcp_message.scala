/*  Title:      MCP/src/pide_mcp_message.scala
    Author:     Kevin Kappelmann

Message utilities.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Message {
  enum Message_Kind { case goals, errors, warnings, writeln, information, tracing }
  object Message_Kind {
    val info: List[Message_Kind] = List(writeln, information, tracing)

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

  sealed case class Message(
    kind: Message_Kind,
    elem: XML.Elem,
    ranges: List[Text.Range],
    snapshot: Document.Snapshot,
    cmd: Option[Command]
  ) {
    lazy val text: String = PIDE_MCP_Util.elem_body_plain_text(
      cmd.fold(elem)(output_message(snapshot, _, elem)))
  }

  def output_message(snapshot: Document.Snapshot, cmd: Command, msg: XML.Elem): XML.Elem =
    XML.Elem(msg.markup, msg match {
        case Protocol.ML_Profiling(report) => XML.string(report.print)
        case _ => PIDE_MCP_Command.resolve_positions(snapshot, cmd, msg.body)
      })

  def messages(
    snapshot: Document.Snapshot,
    cmd: Command,
    command_start: Text.Offset,
    range: Text.Range
  ): Iterator[Message] = {
    def message_ranges(elem: XML.Elem): List[Text.Range] = {
      val ranges = PIDE_MCP_Command.elem_positions(snapshot, cmd, elem)
      if (ranges.isEmpty) List(range)
      else {
        val sorted = ranges.toList.sortBy(_.start).map(_ + command_start)
        PIDE_MCP_Util.restrict_ranges(sorted, range)
      }
    }
    snapshot.command_results(cmd).iterator.flatMap((_, elem) =>
      for {
        kind <- Message_Kind.of(elem)
        ranges = message_ranges(elem) if ranges.nonEmpty
      } yield Message(kind, elem, ranges, snapshot, Some(cmd)))
  }

  def messages_json(
    messages: Map[Message_Kind, List[Message]],
    kinds: List[Message_Kind]
  ): List[Option[JSON.Object.Entry]] =
    kinds.map(kind => messages.get(kind).map(msgs => kind.toString -> msgs.map(_.text)))
}
