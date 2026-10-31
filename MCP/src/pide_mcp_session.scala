/*  Title:      MCP/src/pide_mcp_session.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._
import isabelle.symbols.Symbols

import scala.annotation.tailrec
import scala.collection.immutable.Queue

object PIDE_MCP_Session {
  def build(
    spec: PIDE_MCP_Session_Spec,
    progress: Progress
  ): (Options, Sessions.Background) = {
    val base_options = Options.init()
    val options = base_options ++ spec.options
    val session_background = Sessions.background(options, spec.logic,
      progress = new Silent_Progress(progress),
      dirs = spec.dirs, session_ancestor = spec.session_ancestor,
      session_requirements = spec.session_requirements).check_errors
    Build.build(options, selection = Sessions.Selection.session(session_background.session_name),
      build_heap = true, dirs = spec.dirs, infos = session_background.infos,
      fresh_build = spec.fresh_build, no_build = spec.no_build, progress = progress).check
    val session_options = base_options ++ (Options.Spec.eq("show_states", "true") ::
      Options.Spec.eq("editor_output_state", "false") :: spec.options)
    (session_options, session_background)
  }

  def apply(
    spec: PIDE_MCP_Session_Spec,
    options: Options,
    session_background: Sessions.Background,
    log: Logger,
    progress: Progress
  ): Result[PIDE_MCP_Session, Throwable] =
    Exn.result {
      val id = spec.id.getOrElse(error("Missing session id"))
      val resources = Headless.Resources(options, session_background, log)
      progress.expose_interrupt()
      val session = resources.start_session(progress = progress)
      id -> session
    } match {
      case Exn.Exn(exn) => Result.Error(exn)
      case Exn.Res((id, session)) =>
        Exn.capture {
          progress.expose_interrupt()
          new PIDE_MCP_Session(id = id, dirs = spec.dirs, session = session)
        } match {
          case Exn.Res(session) => Result.Res(session)
          case Exn.Exn(exn) =>
            val stop_failure = Exn.capture(session.stop()) match {
              case Exn.Res(process_result) =>
                Option.when(!process_result.ok)(PIDE_MCP_Util.print_process_result(process_result))
              case Exn.Exn(stop_exn) => Some(Exn.message(stop_exn))
            }
            stop_failure match {
              case None => if (Exn.is_interrupt(exn)) throw exn else Result.Error(exn)
              case Some(message) => throw ERROR(cat_lines(List(
                Exn.message(exn),
                s"Failed to stop partially started PIDE session ${quote(id)}: " + message)))
            }
        }
    }
}

class PIDE_MCP_Session private(
  val id: String,
  val dirs: List[Path],
  val session: Headless.Session
) {
  def resources: Headless.Resources = session.resources
  def options: Options = resources.options
  def progress_delay: Time = options.seconds("pide_mcp_session_progress_delay")
  def poll_delay: Time = options.seconds("pide_mcp_poll_delay")
  def range_context: Int = options.int("pide_mcp_range_context")
  def statistics_limit: Int = options.int("pide_mcp_session_statistics_limit")
  def unicode_symbols_disk: Boolean = options.bool("pide_mcp_unicode_symbols_disk")
  def disk_text(path: Path, text: String): String =
    if (Symbols.supports_isabelle_symbols(path)) Symbol.output(unicode_symbols_disk, text)
    else text
  def pide_text(path: Path, text: String): String =
    if (Symbols.supports_isabelle_symbols(path)) Symbol.decode(text) else text

  def base_session: String = resources.current_background.session_name
  def directories(): List[Path] = Sessions.directories(dirs, Nil).map(_._2)

  private def await_message(what: String): String =
    s"Awaiting $what for session ${quote(id)}"

  private val lock = new Queue_Lock
  def with_lock[A](progress: Progress)(body: => A): A =
    lock.with_lock(progress, await_message("session lock"), poll_delay, progress_delay)(body)

  private val statistics = Synchronized[Queue[Properties.T]](Queue.empty)
  private val statistics_consumer = Session.Consumer[Session.Runtime_Statistics](this.class_name) {
    case Session.Runtime_Statistics(props) =>
      statistics.change { statistics =>
        val statistics1 = statistics.appended(props)
        if (statistics1.length > statistics_limit) statistics1.dequeue._2 else statistics1
      }
  }
  session.runtime_statistics += statistics_consumer

  private val syslog_consumer = Session.Consumer[Prover.Output](this.class_name) { output =>
    if (output.is_system || output.is_stderr)
      resources.log(s"PIDE session ${quote(id)}: " + XML.content(output.message))
  }
  session.syslog_messages += syslog_consumer

  def runtime_statistics(): List[Properties.T] = statistics.value.toList

  private val overlay_updates = Consumer_Thread.fork[() => Unit](s"pide_mcp_overlays_$id",
    { e => e(); true }, daemon = true, log = resources.log)

  def stop(): Process_Result = {
    session.runtime_statistics -= statistics_consumer
    session.syslog_messages -= syslog_consumer
    val result = session.stop()
    overlay_updates.shutdown()
    result
  }

  private def path_node_name(path: Path): Document.Node.Name = {
    val abs = path.canonical
    resources.find_theory(abs.file).getOrElse { // session theories
      val candidate = Document.Node.Name(abs.implode, // full path file
        theory = Thy_Header.theory_name(abs.implode))
      if (candidate.path.is_file) candidate
      // source_file can return identity for unknown files
      else session.store.source_file(path.implode) match {
        case Some(file) if Path.explode(file).is_file => node_name(file)
        case _ => error(s"Path ${quote(path.implode)} cannot be resolved: " +
          "it is neither a file on disk nor resolvable by PIDE")
      }
    }
  }

  def node_name(s: String): Document.Node.Name =
    Some(s).filter(Url.is_base_name(_)).flatMap(resources.find_theory_node)
      .getOrElse(path_node_name(PIDE_MCP_Util.path(s)))

  def origin(node_name: Document.Node.Name): String =
    if (node_name.is_theory && resources.find_theory_node(node_name.theory).isDefined)
      node_name.theory // session-qualified
    else node_name.node // full path

  def is_base_session_theory(node_name: Document.Node.Name): Boolean =
    resources.loaded_theory(node_name)

  def snapshot(): Document.Snapshot = session.snapshot()

  def require_ready(): Unit =
    if (!session.is_ready)
      error(s"PIDE session ${quote(id)} is not ready. " +
        s"Its session phase is ${quote(session.phase.print)}")

  def await_while_ready[A](
    progress: Progress,
    what: => String,
    delay: Time = poll_delay
  )(value: => Option[A]): A =
    PIDE_MCP_Progress.await(progress, await_message(what), delay, progress_delay) {
      require_ready()
      value
    }

  def tip_change: Document.Change = session.get_state().history.tip

  def await_stable(change: Document.Change, progress: Progress): Unit =
    await_while_ready(progress, "stable snapshot") {
      val state = session.get_state()
      val unstable = state.history.undo_list.iterator.takeWhile(!state.is_stable(_))
      Option.when(!unstable.contains(change))(())
    }

  def node_snapshot(node_name: Document.Node.Name): Document.Snapshot =
    switch(session.snapshot(), node_name)

  def switch(
    snapshot: => Document.Snapshot,
    node_name: Document.Node.Name
  ): Document.Snapshot = {
    if (is_base_session_theory(node_name))
      session.read_theory(node_name.theory, unicode_symbols = true)
    else {
      val snapshot1 = snapshot
      val new_snapshot = (if (is_base_session_theory(snapshot1.node_name)) session.snapshot()
        else snapshot1).switch(node_name)
      if (PIDE_MCP_Util.is_loaded_dynamic(new_snapshot.version.nodes(node_name))) new_snapshot
      else error(s"No PIDE snapshot available for ${quote(origin(node_name))}")
    }
  }

  def tip_version(progress: Progress): Document.Version = {
    progress.expose_interrupt()
    val version = session.get_state().history.tip.version
    await_while_ready(progress, "current document version")(version.peek.map(Exn.release))
  }

  def read_file_content(node_name: Document.Node.Name): String =
    resources.make_theory_content(node_name).getOrElse(
      pide_text(node_name.path, Line.normalize(File.read(node_name.path))))

  def write_file_content(path: Path, text: String): Unit =
    File.write(path, disk_text(path, Line.normalize(text)))

  def node_source(
    snapshot: Document.Snapshot,
    node_name: Document.Node.Name
  ): String =
    try { switch(snapshot, node_name).node.source }
    catch { case ERROR(_) => read_file_content(node_name) }

  def update(edits: List[Document.Edit_Text]): Document.Change = {
    if (edits.nonEmpty) {
      val blobs = edits.collect { case (name, Document.Node.Blob(blob)) => name -> blob }
      session.update(Document.Blobs(blobs.toMap), edits)
    }
    tip_change
  }

  private def edit_overlays(
    node_name: Document.Node.Name,
    change: Document.Node.Overlays => Document.Node.Overlays,
    require_theory: Boolean,
    progress: Progress
  ): Future[Unit] = {
    def edit(): Unit = {
      require_ready()
      with_lock(progress) {
        val node = tip_version(progress).nodes(node_name)
        if (PIDE_MCP_Util.is_loaded_dynamic(node))
          update(List(node_name -> PIDE_MCP_Document_Model.node_perspective(node)
            .copy(overlays = change(node.perspective.overlays))))
        else if (require_theory)
          error(s"${quote(origin(node_name))} is not a dynamically loaded theory")
      }
    }
    val result = Future.promise[Unit]
    def done(res: Exn.Result[Unit]): Unit = {
      res match {
        case Exn.Exn(exn) if !Exn.is_interrupt(exn) => progress.echo_error_message(Exn.message(exn))
        case _ =>
      }
      result.fulfill_result(res)
    }
    Exn.capture { require_ready(); overlay_updates.send(() => done(Exn.capture(edit()))) } match {
      case failure @ Exn.Exn(_) => done(failure)
      case Exn.Res(_) =>
    }
    result
  }

  def insert_overlay(
    command: Command,
    fn: String,
    args: List[String],
    progress: Progress
  ): Future[Unit] =
    edit_overlays(command.node_name, _.insert(command, fn, args), require_theory = true, progress)

  def remove_overlay(
    command: Command,
    fn: String,
    args: List[String],
    progress: Progress
  ): Future[Unit] =
    edit_overlays(command.node_name, _.remove(command, fn, args), require_theory = false, progress)

  def editor(progress: Progress): PIDE_MCP_Editor = new PIDE_MCP_Editor(this, progress)

  def read_update(
    nodes: List[(Document.Node.Name, Option[List[(Int, Option[Int])]])],
    hide_others: Boolean,
    range_context: Int = range_context,
    progress: Progress
  ): (Map[Document.Node.Name, String], Document.Change) = {
    val (models, change) = with_lock(progress) {
      val version = tip_version(progress)
      val models1 = nodes.map { case (name, visible_lines) =>
        PIDE_MCP_Document_Model.make(session, name, version.nodes(name), read_file_content(name),
          visible_lines, range_context)
      }
      val other_edits = if (!hide_others) Iterator.empty
        else PIDE_MCP_Document_Model.hide_edits(version.nodes, nodes.map(_._1).toSet)
      (models1, update((other_edits ++ models1.iterator.flatMap(_.edits)).toList))
    }
    (models.map(model => model.node_name -> model.text).toMap, change)
  }

  def sync(node_names: List[Document.Node.Name], progress: Progress): Document.Change =
    with_lock(progress) {
      val nodes = tip_version(progress).nodes
      read_update(
        node_names.filter(name => PIDE_MCP_Util.is_loaded_dynamic(nodes(name))).map(_ -> None),
        hide_others = false, progress = progress)._2
    }

  def sync_resolve(node_names: List[Document.Node.Name], progress: Progress): Document.Change =
    with_lock(progress) {
      sync(node_names, progress)
      resolve_dependencies(node_names, progress)
      tip_change
    }

  def sync_dependencies_resolve(
    node_names: List[Document.Node.Name],
    progress: Progress
  ): Document.Change =
    with_lock(progress) {
      sync_resolve(PIDE_MCP_Dependencies.known_dependencies(resources,
        tip_version(progress).nodes, node_names), progress)
    }

  def read_update_resolve(
    node_name: Document.Node.Name,
    visible_lines: List[(Int, Option[Int])],
    await_stable_before_resolve: Boolean,
    hide_others: Boolean,
    range_context: Int = range_context,
    progress: Progress
  ): String = {
    if (is_base_session_theory(node_name)) node_snapshot(node_name).node.source
    else {
      val (texts, change) = read_update(List(node_name -> Some(visible_lines)), hide_others,
        range_context, progress)
      if (await_stable_before_resolve) await_stable(change, progress)
      resolve_dependencies(List(node_name), progress)
      texts(node_name)
    }
  }

  def resolve_dependencies(
    origins: List[Document.Node.Name],
    progress: Progress
  ): Unit = {
    @tailrec def loop(seen: Set[Document.Node.Name], origins: List[Document.Node.Name]): Unit = {
      val names = PIDE_MCP_Dependencies.unloaded_dependencies(session,
        tip_version(progress).nodes, origins, seen, progress)
      if (names.nonEmpty) {
        read_update(names.map(_ -> Some(Nil)), hide_others = false, progress = progress)
        loop(seen ++ names, names)
      }
    }
    loop(Set.empty, origins)
  }

  def unload(
    node_names: List[Document.Node.Name],
    progress: Progress
  ): (List[Document.Node.Name], Document.Change) = {
    for (name <- node_names if is_base_session_theory(name))
      error(s"Cannot unload base session theory ${quote(origin(name))}")
    with_lock(progress) {
      val nodes = tip_version(progress).nodes
      val unloaded = PIDE_MCP_Dependencies.removable_names(resources, nodes, node_names)
      (unloaded, update(unloaded.flatMap(name =>
        PIDE_MCP_Document_Model.unload_edits(session, name, nodes(name)))))
    }
  }
}
