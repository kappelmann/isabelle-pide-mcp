/*  Title:      PIDE_MCP/pide_mcp_query.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Query {
  enum Outcome { case finished, stopped, timeout }
  sealed case class Result(outcome: Outcome, elapsed: Time, output: Editor.Output) {
    def json(kinds: List[String]): JSON.Object.T =
      JSON_Object.flatten(
        List(Some("status" -> outcome.toString), Some("elapsed_ms" -> elapsed.ms)) :::
        PIDE_MCP_Command.message_entries(output.messages.iterator, kinds))
  }
}

class PIDE_MCP_Query(
  session: PIDE_MCP_Session,
  context: PIDE_MCP_Editor.Context,
  operation: String,
  progress: Progress
) {
  private val editor = session.editor(progress)
  private val status = Synchronized(Query_Operation.Status.waiting)
  private val output = Synchronized(Editor.Output.init)
  private val query = new Query_Operation(editor, context, operation,
    st => { status.change(_ => st); progress.echo(s"$operation $st") },
    out =>
      for (msg <- output.change_result(old => (out.messages.diff(old.messages), out))) {
        val text = PIDE_MCP_Util.elem_body_plain_text(msg)
        if (Protocol.is_error(msg)) progress.echo_error_message(text)
        else if (Protocol.is_warning_or_legacy(msg)) progress.echo_warning(text)
        else progress.echo(text)
      })

  private def origin: String = quote(session.origin(context.node_name))

  def finished: Boolean = status.value == Query_Operation.Status.finished

  private def require_alive(): Unit = {
    session.require_ready()
    for (command <- query.get_location)
      if (!session.node_snapshot(command.node_name).node.commands.contains(command))
        error(s"The command of the $operation query in $origin is gone")
  }

  def run(
    args: List[String],
    timeout: Option[Time] = None,
    stop: Editor.Output => Boolean = _ => false
  ): PIDE_MCP_Query.Result = {
    if (session.is_base_session_theory(context.node_name))
      error(s"Cannot query base session theory $origin")
    val snapshot = editor.current_node_snapshot(context).getOrElse(
      error(s"No PIDE snapshot available for $origin"))
    if (editor.current_command(context, snapshot).isEmpty)
      error(s"No command at the given position of $origin")

    val start = Time.now()
    query.activate()
    try {
      editor.send_wait_dispatcher { query.apply_query(args) }
      val outcome =
        PIDE_MCP_Progress.await(progress, s"Awaiting $operation",
          session.session.output_delay, session.progress_delay) {
          if (finished) Some(PIDE_MCP_Query.Outcome.finished)
          else if (stop(output.value)) Some(PIDE_MCP_Query.Outcome.stopped)
          else {
            require_alive()
            if (timeout.exists(Time.now() - start > _)) Some(PIDE_MCP_Query.Outcome.timeout)
            else None
          }
        }
      PIDE_MCP_Query.Result(outcome, Time.now() - start, output.value)
    }
    finally {
      if (!finished) Exn.capture { editor.send_wait_dispatcher { query.cancel_query() } }
      query.deactivate()
    }
  }
}
