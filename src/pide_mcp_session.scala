/*  Title:      PIDE_MCP/pide_mcp_session.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.annotation.tailrec
import scala.collection.immutable.Queue

object PIDE_MCP_Session {
  object Spec {
    def default_logic: String = Isabelle_System.default_logic()

    val usage: String = """Session options are:

    -A NAME      ancestor session for option -R (default: parent)
    -R NAME      build image with requirements from other sessions
    -d DIR       include session directory
    -f           fresh build
    -l NAME      logic session name (default ISABELLE_LOGIC=""" + quote(default_logic) + """)
    -n           no build of session image on startup
    -o OPTION    override Isabelle system OPTION (via NAME=VAL or NAME)
    -z ID        PIDE MCP session id
"""
    private object Parsers extends Scan.Parsers {
      val blanks: Parser[String] = many(character(Symbol.is_ascii_blank))
      val word: Parser[String] = quoted("\"") ^^ (s => quoted_content("\"", s))
        | many1(sym => sym != "\"" && !character(Symbol.is_ascii_blank)(sym))
      val words: Parser[List[String]] = blanks ~> rep(word <~ blanks)
    }

    def dir(s: String): Path = PIDE_MCP_Util.path(s)
    def option(s: String): Options.Spec = Options.Spec.make(s)

    class Builder {
      private var current: Option[Spec] = None
      private var session_option = false
      def spec: Option[Spec] = current
      def has_session_option: Boolean = session_option
      private def update(f: Spec => Spec): Unit = current = Some(f(current.getOrElse(Spec())))
      private def update_session(f: Spec => Spec): Unit =
        { session_option = true; update(f) }
      val option_specs: List[(String, String => Unit)] =
        List(
          "A:" -> (arg => update_session(_.copy(session_ancestor = Some(arg)))),
          "R:" -> (arg => update_session(_.copy(logic = arg, session_requirements = true))),
          "d:" -> (arg =>
            update_session(spec => spec.copy(dirs = spec.dirs ::: List(dir(arg))))),
          "f" -> (_ => update_session(_.copy(fresh_build = true))),
          "l:" -> (arg => update_session(_.copy(logic = arg))),
          "n" -> (_ => update_session(_.copy(no_build = true))),
          "o:" -> (arg => update(spec => spec.copy(
            options = spec.options ::: List(option(arg))))),
          "z:" -> (arg => update_session(_.copy(id = Some(arg)))))
    }

    def parse(spec: String): Spec = {
      val words = Parsers.parseAll(Parsers.words, spec) match {
        case Parsers.Success(res, _) => res
        case bad => cat_error(bad.toString, usage)
      }
      val builder = new Builder
      val more_args = Getopts(usage, builder.option_specs: _*)(words, true)
      if (more_args.nonEmpty) cat_error("Bad session options: " + quote(spec), usage)
      builder.spec.getOrElse(Spec())
    }
  }

  sealed case class Spec(
    session_ancestor: Option[String] = None,
    session_requirements: Boolean = false,
    dirs: List[Path] = Nil,
    fresh_build: Boolean = false,
    no_build: Boolean = false,
    logic: String = Spec.default_logic,
    options: Options.Update = Nil,
    id: Option[String] = None
  )

  def build(
    spec: Spec,
    progress: Progress
  ): (Options, Sessions.Background) = {
    val options = Options.init(update = spec.options)
    val session_background = Sessions.background(options, spec.logic,
      progress = new Silent_Progress(progress),
      dirs = spec.dirs, session_ancestor = spec.session_ancestor,
      session_requirements = spec.session_requirements).check_errors
    Build.build(options, selection = Sessions.Selection.session(session_background.session_name),
      build_heap = true, dirs = spec.dirs, infos = session_background.infos,
      fresh_build = spec.fresh_build, no_build = spec.no_build, progress = progress).check
    (options, session_background)
  }

  def apply(
    spec: Spec,
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
            Exn.capture(session.stop()) match {
              case Exn.Res(process_result) if process_result.ok =>
                if (Exn.is_interrupt(exn)) throw exn else Result.Error(exn)
              case Exn.Res(process_result) => throw ERROR(cat_lines(List(
                Exn.message(exn),
                s"Failed to stop partially started PIDE session ${quote(id)}: " +
                  PIDE_MCP_Util.print_process_result(process_result))))
              case Exn.Exn(stop_exn) => throw ERROR(cat_lines(List(
                Exn.message(exn),
                s"Failed to stop partially started PIDE session ${quote(id)}: " +
                  Exn.message(stop_exn))))
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
  def range_context: Int = options.int("pide_mcp_range_context")
  def statistics_limit: Int = options.int("pide_mcp_session_statistics_limit")
  def base_session: String = resources.current_background.session_name
  def directories(): List[Path] = Sessions.directories(dirs, Nil).map(_._2)

  private def await_message(what: String): String =
    s"Awaiting $what for session ${quote(id)}"

  private val lock = new Queue_Lock
  def with_lock[A](progress: Progress)(body: => A): A =
    lock.with_lock(progress, await_message("session lock"),
      session.output_delay, progress_delay)(body)

  private val statistics = Synchronized[Queue[Properties.T]](Queue.empty)
  private val statistics_consumer =
    Session.Consumer[Session.Runtime_Statistics](this.class_name) {
      case Session.Runtime_Statistics(props) =>
        statistics.change { statistics =>
          val statistics1 = statistics.appended(props)
          if (statistics1.length > statistics_limit) statistics1.dequeue._2 else statistics1
        }
    }
  session.runtime_statistics += statistics_consumer

  def runtime_statistics(): List[Properties.T] = statistics.value.toList

  private val overlay_updates = Consumer_Thread.fork[() => Unit](s"pide_mcp_overlays_$id",
    { e => e(); true }, daemon = true, log = resources.log)

  def stop(): Process_Result = {
    session.runtime_statistics -= statistics_consumer
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
      else session.store.source_file(path.implode) match { // source_file can return identity for unknown files
        case Some(file) if Path.explode(file).is_file => node_name(file)
        case _ => error(s"Path ${quote(path.implode)} cannot be resolved: " +
          "it is neither a file on disk nor resolvable by PIDE")
      }
    }
  }

  def node_name(s: String): Document.Node.Name =
    resources.find_theory_node(s).getOrElse(path_node_name(PIDE_MCP_Util.path(s)))

  def origin(node_name: Document.Node.Name): String =
    if (node_name.is_theory) {
      resources.find_theory_node(node_name.theory) match {
        case Some(_) => node_name.theory // session-qualified
        case None => node_name.node // full path
      }
    } else node_name.node // full path

  def is_base_session_theory(node_name: Document.Node.Name): Boolean =
    resources.loaded_theory(node_name)

  def snapshot(): Document.Snapshot = session.snapshot()

  def require_ready(): Unit =
    if (!session.is_ready)
      error(s"PIDE session ${quote(id)} is not ready. " +
        s"Its session phase is ${quote(session.phase.print)}")

  private def await_ready[A](progress: Progress, what: String)(value: => Option[A]): A =
    PIDE_MCP_Progress.await(progress, await_message(what), session.output_delay, progress_delay) {
      require_ready()
      value
    }

  def await_stable_snapshot(progress: Progress): Document.Snapshot =
    await_ready(progress, "stable snapshot") {
      val snapshot = session.snapshot()
      Option.when(!snapshot.is_outdated)(snapshot)
    }

  def node_snapshot(node_name: Document.Node.Name): Document.Snapshot =
    switch(session.snapshot(), node_name)

  def switch(
    snapshot: => Document.Snapshot,
    node_name: Document.Node.Name
  ): Document.Snapshot = {
    if (is_base_session_theory(node_name)) session.read_theory(node_name.theory, unicode_symbols = true)
    else {
      val snapshot1 = snapshot
      val new_snapshot =
        (if (is_base_session_theory(snapshot1.node_name)) session.snapshot() else snapshot1).switch(node_name)
      if (PIDE_MCP_Util.is_loaded_dynamic(new_snapshot.version.nodes(node_name))) new_snapshot
      else error(s"No PIDE snapshot available for ${quote(origin(node_name))}")
    }
  }

  def tip_version(progress: Progress): Document.Version = {
    progress.expose_interrupt()
    val version = session.get_state().history.tip.version
    await_ready(progress, "current document version")(version.peek.map(Exn.release))
  }

  def read_file_content(node_name: Document.Node.Name): String =
    resources.make_theory_content(node_name).getOrElse(
      Symbol.decode(Line.normalize(File.read(node_name.path))))

  def write_file_content(path: Path, text: String): Unit =
    File.write(path, Symbol.encode(Line.normalize(text)))

  def node_source(
    snapshot: Document.Snapshot,
    node_name: Document.Node.Name
  ): String =
    Exn.result { switch(snapshot, node_name) } match {
      case Exn.Res(new_snapshot) => new_snapshot.node.source
      case Exn.Exn(_) => read_file_content(node_name)
    }

  def update(edits: List[Document.Edit_Text]): Unit =
    if (edits.nonEmpty) {
      val blobs = edits.collect { case (name, Document.Node.Blob(blob)) => name -> blob }
      session.update(Document.Blobs(blobs.toMap), edits)
    }

  private def node_perspective(node: Document.Node): Document.Node.Perspective_Text.T =
    Document.Node.Perspective(
      node.perspective.required, node.text_perspective, node.perspective.overlays)

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
          update(List(node_name ->
            node_perspective(node).copy(overlays = change(node.perspective.overlays))))
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

  private def replace_edits(old_text: String, new_text: String): List[Text.Edit] = {
    val prefix = old_text.iterator.zip(new_text.iterator).takeWhile(_ == _).length
    val (old_rest, new_rest) = (old_text.drop(prefix), new_text.drop(prefix))
    val suffix = old_rest.reverseIterator.zip(new_rest.reverseIterator).takeWhile(_ == _).length
    Text.Edit.replace(prefix, old_rest.dropRight(suffix), new_rest.dropRight(suffix))
  }

  private case class Node_Model(
    node_name: Document.Node.Name,
    node: Document.Node,
    text: String,
    text_perspective: Text.Perspective
  ) extends Document.Model {
    def session: Document.Session = PIDE_MCP_Session.this.session
    def node_required: Boolean = text.nonEmpty
    def untyped_data: AnyRef = File_Format.registry.parse_data(node_name, text)

    lazy val pending_edits: List[Text.Edit] = replace_edits(node.source, text)
    def is_stable: Boolean = pending_edits.isEmpty

    def get_text(range: Text.Range): Option[String] = range.try_substring(text)

    def get_blob: Option[Document.Blobs.Item] =
      if (is_theory) None
      else Some(Document.Blobs.Item(
        Bytes(Symbol.encode(text)), text, Symbol.Text_Chunk(text), changed = !is_stable))

    def get_thy(): Resources.Thy = {
      val thy0 =
        resources.special_thy(node_name).getOrElse(
          resources.check_thy(node_name, Scan.char_reader(text)))
      thy0.eval_conditions(session.conditions)
    }

    def node_perspective: Document.Node.Perspective_Text.T =
      if (is_theory)
        PIDE_MCP_Session.this.node_perspective(node).copy(
          required = node_required, visible = text_perspective)
      else Document.Node.Perspective_Text.empty

    def edits: List[Document.Edit_Text] =
      if (is_stable && node.edit_perspective == node_perspective) Nil
      else node_edits(get_thy(), pending_edits, node_perspective)
  }

  private def hide_edits(
    nodes: Document.Nodes,
    keep: Set[Document.Node.Name]
  ): Iterator[Document.Edit_Text] =
    nodes.iterator.collect {
      case (name, node) if !keep(name) && !node.text_perspective.is_empty =>
        name -> node_perspective(node).copy(visible = Text.Perspective.empty)
    }

  def read_update(
    nodes: List[(Document.Node.Name, List[(Int, Option[Int])])],
    hide_others: Boolean,
    range_context: Int = range_context,
    progress: Progress
  ): Map[Document.Node.Name, String] = {
    val models = with_lock(progress) {
      val version = tip_version(progress)
      val models1 = nodes.map { case (name, visible_lines) =>
        val text = read_file_content(name)
        val doc = Line.Document(text)
        val line_ranges = visible_lines.map { case (start_line, opt_end_line) =>
          (start_line, opt_end_line.getOrElse(doc.lines.length)) }
        Node_Model(name, version.nodes(name), text,
          PIDE_MCP_Util.text_perspective(doc, line_ranges, range_context))
      }
      val other_edits =
        if (hide_others) hide_edits(version.nodes, nodes.map { case (name, _) => name }.toSet)
        else Iterator.empty
      update((other_edits ++ models1.iterator.flatMap(_.edits)).toList)
      models1
    }
    models.map(model => model.node_name -> model.text).toMap
  }

  private def required_nodes(
    version: Document.Version,
    seen: Set[Document.Node.Name],
    loaded_origins: List[Document.Node.Name],
    progress: Progress
  ): Set[Document.Node.Name] = {
    def is_required(name: Document.Node.Name): Boolean =
      !seen(name) && !is_base_session_theory(name) &&
      !PIDE_MCP_Util.is_loaded_dynamic(version.nodes(name)) &&
      (name.path.is_file || resources.make_theory_content(name).isDefined)
    val thy_files =
      version.nodes.iterator.flatMap(_._2.thy.imports_no_pos).distinct.filter(is_required)
    val deps = resources.dependencies(session.conditions,
      thy_files.map((_, Position.none)).toList, progress = progress)
    val dep_files = try deps.loaded_files catch { case ERROR(_) => Nil }
    val loaded_origin_files = for {
        name <- loaded_origins.iterator
        cmd <- version.nodes(name).load_commands.iterator
        file <- cmd.blobs_names.iterator
      } yield file
    val file_theories = for {
        (name, node) <- version.nodes.iterator if PIDE_MCP_Util.is_loaded_dynamic(node)
        thy_name <- resources.make_theory_name(name)
      } yield thy_name
    val aux_files = resources.undefined_blobs(version)
    (deps.theories ++ dep_files ++ loaded_origin_files ++ file_theories ++ aux_files)
      .toSet.filter(is_required)
  }

  def resolve_dependencies(
    loaded_origins: List[Document.Node.Name],
    progress: Progress
  ): Unit = {
    @tailrec def loop(
      seen: Set[Document.Node.Name],
      loaded_origins: List[Document.Node.Name]
    ): Unit = {
      val names = required_nodes(tip_version(progress), seen, loaded_origins, progress)
      if (names.nonEmpty) {
        read_update(names.toList.map(_ -> Nil), hide_others = false, progress = progress)
        loop(seen ++ names, Nil)
      }
    }
    loop(Set.empty, loaded_origins)
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
      val text = read_update(List(node_name -> visible_lines), hide_others,
        range_context, progress)(node_name)
      if (await_stable_before_resolve) await_stable_snapshot(progress)
      resolve_dependencies(List(node_name), progress)
      text
    }
  }

  private def unload_edits(
    node_name: Document.Node.Name,
    node: Document.Node
  ): List[Document.Edit_Text] = {
    val model = Node_Model(node_name, node, "", Text.Perspective.empty)
    model.node_edits(
      Resources.Thy.empty, model.pending_edits, Document.Node.Perspective_Text.empty)
  }

  def unload(
    node_names: List[Document.Node.Name],
    progress: Progress
  ): List[Document.Node.Name] = {
    for (name <- node_names if is_base_session_theory(name))
      error(s"Cannot unload base session theory ${quote(origin(name))}")
    with_lock(progress) {
      val nodes = tip_version(progress).nodes
      def is_loaded(name: Document.Node.Name) = PIDE_MCP_Util.is_loaded_dynamic(nodes(name))
      def blobs_names(name: Document.Node.Name) = nodes(name).load_commands.flatMap(_.blobs_names)
      val descendants = nodes.descendants(node_names).filter(is_loaded)
      val blobs = descendants.flatMap(blobs_names).distinct.filter(is_loaded).map(blob_name =>
        blob_name -> resources.make_theory_name(blob_name).filter(is_loaded))
      val removed = descendants.toSet ++ blobs.flatMap(_._2)
      lazy val retained_blobs = nodes.names_iterator.filterNot(removed).flatMap(blobs_names).toSet
      val unloaded = descendants ::: (for {
          (blob_name, opt_thy) <- blobs if !retained_blobs(blob_name)
          name <- blob_name :: opt_thy.toList
        } yield name)
      update(unloaded.flatMap(name => unload_edits(name, nodes(name))))
      unloaded
    }
  }
}
