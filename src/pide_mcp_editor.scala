/*  Title:      PIDE_MCP/pide_mcp_editor.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Editor {
  sealed case class Context(node_name: Document.Node.Name, offset: Text.Offset)
}

class PIDE_MCP_Editor(mcp_session: PIDE_MCP_Session, progress: Progress) extends Editor {
  type Context = PIDE_MCP_Editor.Context
  type Session = Headless.Session

  def session: Headless.Session = mcp_session.session
  def flush(): Unit = ()
  def invoke(): Unit = ()
  def revoke(): Unit = ()

  def get_models(): Iterable[Document.Model] = Nil

  def current_node(context: Context): Option[Document.Node.Name] = Some(context.node_name)
  def node_snapshot(name: Document.Node.Name): Document.Snapshot = mcp_session.node_snapshot(name)
  def current_node_snapshot(context: Context): Option[Document.Snapshot] =
    Exn.the_res.lift(Exn.result(node_snapshot(context.node_name)))
  def current_command(context: Context, snapshot: Document.Snapshot): Option[Command] =
    snapshot.current_command(context.node_name, context.offset)

  def output_state(): Boolean = mcp_session.options.bool("editor_output_state")

  def node_overlays(name: Document.Node.Name): Document.Node.Overlays =
    mcp_session.snapshot().get_node(name).perspective.overlays
  def insert_overlay(command: Command, fn: String, args: List[String]): Unit =
    mcp_session.insert_overlay(command, fn, args, progress)
  def remove_overlay(command: Command, fn: String, args: List[String]): Unit =
    mcp_session.remove_overlay(command, fn, args, new Uncancellable_Progress(progress))

  def hyperlink_command(
    snapshot: Document.Snapshot,
    id: Document_ID.Generic,
    offset: Symbol.Offset = 0,
    description: String = "",
    focus: Boolean = false
  ): Option[Hyperlink] = None

  def assert_dispatcher[A](body: => A): A = session.assert_dispatcher(body)
  def require_dispatcher[A](body: => A): A = session.require_dispatcher(body)
  def send_dispatcher(body: => Unit): Unit = session.send_dispatcher(body)
  def send_wait_dispatcher(body: => Unit): Unit = session.send_wait_dispatcher(body)
}
