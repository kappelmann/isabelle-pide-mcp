/*  Title:      PIDE_MCP/mcp_tools.scala
    Author:     Kevin Kappelmann

Implementation of all MCP tool handlers.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls


class MCP_Tools(val session: PIDE_Session)
{
  /* parameter extraction */

  private def str(params: Map[String, Any], key: String): Option[String] =
    params.get(key).map(_.toString)

  private def int(params: Map[String, Any], key: String): Option[Int] =
    params.get(key).flatMap {
      case n: Number => Some(n.intValue())
      case s: String => s.toIntOption
      case _ => None
    }

  private def bool(params: Map[String, Any], key: String, default: Boolean = false): Boolean =
    params.get(key) match {
      case Some(b: Boolean) => b
      case _ => default
    }

  private def timeout(params: Map[String, Any]): Int =
    int(params, "timeout_secs").filter(_ > 0).getOrElse(PIDE_Session.default_timeout_secs)

  /* theory loading */

  private def load_theory_path(path_str: String, timeout_secs: Int = PIDE_Session.default_timeout_secs): Either[String, Path] =
  {
    val path = Path.explode(path_str).expand
    session.load_theory(path, timeout_secs).map(_ => path)
  }

  private def file_snapshot(path_str: String, timeout_secs: Int = PIDE_Session.default_timeout_secs): Either[String, (Document.Snapshot, Document.Node.Name, Line.Document)] =
  {
    load_theory_path(path_str, timeout_secs).flatMap { path =>
      val node = session.node_name(path)
      try {
        val snap = session.snapshot(node)
        Right((snap, node, Line.Document(snap.node.source)))
      } catch {
        case _: Exception => Left(s"Could not get snapshot for $path")
      }
    }
  }

  /* tool dispatch */

  def invoke(name: String, params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    name match {
      case "list_loaded_theories" => handle_list_loaded_theories(params)
      case "read_theory" => handle_read_theory(params)
      case "read_ml_file" => handle_read_ml_file(params)
      case "edit_theory" => handle_edit_theory(params)
      case "edit_ml_file" => handle_edit_ml_file(params)
      case "get_state" => handle_get_state(params)
      case "list_entities" => handle_list_entities(params)
      case "find_definition" => handle_find_definition(params)
      case "create_scratch" => handle_create_scratch(params)
      case "check_theory" => handle_check_theory(params)
      case _ => Left(s"Unknown tool: $name")
    }
  }

  /* tool implementations */

  private def handle_list_loaded_theories(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    val include_scratch = bool(params, "include_scratch")
    val (static, dynamic, scratch) = session.loaded_theories
    def to_entry(name: Document.Node.Name) = Map("path" -> name.node, "theory" -> name.theory)
    val result = Map(
      "static" -> static.map(to_entry),
      "dynamic" -> dynamic.map(to_entry)
    )
    if (include_scratch) Right(result + ("scratch" -> scratch.map(to_entry)))
    else Right(result)
  }

  private def read_lines(text: String, params: Map[String, Any]): String =
  {
    val all_lines = text.split("\n", -1).zipWithIndex.map { case (l, i) => s"${i+1}: $l" }
    val start = int(params, "start_line")
    val end = int(params, "end_line")
    val start_idx = start.filter(_ > 0).map(_ - 1).getOrElse(0)
    val end_idx = end.filter(_ > 0).map(n => Math.min(n, all_lines.length)).getOrElse(all_lines.length)
    all_lines.slice(start_idx, end_idx).mkString("\n")
  }

  private def handle_read_theory(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    str(params, "path") match {
      case None => Left("Missing path parameter")
      case Some(p) =>
        file_snapshot(p, timeout(params)).map { case (_, _, doc) =>
          val text = doc.lines.map(_.text).mkString("\n")
          Map("content" -> read_lines(text, params))
        }
    }
  }

  private def handle_read_ml_file(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    str(params, "path") match {
      case None => Left("Missing path parameter")
      case Some(p) =>
        val path = Path.explode(p).expand
        Right(Map("content" -> read_lines(File.read(path), params)))
    }
  }

  private def replace_lines(current_text: String, content: String, start_line: Int, end_line: Int, old_content: String): Either[String, String] =
  {
    if (start_line <= 0) Right(content)
    else {
      val lines = current_text.split("\n", -1)
      val end = if (end_line <= 0) start_line else end_line
      val start_idx = start_line - 1
      if (start_idx < 0 || start_idx > lines.length) Left(s"Invalid start_line: $start_line")
      else {
        val actual_old = lines.slice(start_idx, end).mkString("\n")
        if (actual_old != old_content)
          Left(s"old_content mismatch at lines $start_line-$end.\nExpected:\n$old_content\nActual:\n$actual_old")
        else {
          val prefix = lines.take(start_idx)
          val suffix = lines.drop(end)
          val content_lines = content.split("\n", -1)
          Right((prefix ++ content_lines ++ suffix).mkString("\n"))
        }
      }
    }
  }

  private def handle_edit_theory(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    str(params, "path") match {
      case None => Left("Missing path")
      case Some(path_str) =>
        str(params, "content") match {
          case None => Left("Missing content")
          case Some(content) =>
            val start_line = int(params, "start_line")
            val end_line = int(params, "end_line")
            if (start_line.exists(_ > 0) && str(params, "old_content").isEmpty)
              return Left("Missing old_content: must specify expected content at target lines")
            val old_content = str(params, "old_content").getOrElse("")
            val timeout_secs = timeout(params)
            val path = Path.explode(path_str).expand
            val current_text = File.read(path)
            replace_lines(current_text, content, start_line.getOrElse(0), end_line.getOrElse(0), old_content).flatMap { new_text =>
              File.write(path, new_text)
              session.check_theory(session.node_name(path).theory, path.dir.implode, timeout_secs)
              Right(Map("status" -> "written", "length" -> new_text.length))
            }
        }
    }
  }

  private def handle_edit_ml_file(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    str(params, "path") match {
      case None => Left("Missing path")
      case Some(path_str) =>
        str(params, "content") match {
          case None => Left("Missing content")
          case Some(content) =>
            str(params, "parent_theory") match {
              case None => Left("Missing parent_theory")
              case Some(parent_str) =>
                val start_line = int(params, "start_line")
                val end_line = int(params, "end_line")
                if (start_line.exists(_ > 0) && str(params, "old_content").isEmpty)
                  return Left("Missing old_content: must specify expected content at target lines")
                val old_content = str(params, "old_content").getOrElse("")
                val timeout_secs = timeout(params)
                val path = Path.explode(path_str).expand
                val current_text = File.read(path)

                replace_lines(current_text, content, start_line.getOrElse(0), end_line.getOrElse(0), old_content).flatMap { new_text =>
                  File.write(path, new_text)
                  val parent_path = Path.explode(parent_str).expand
                  session.check_theory(session.node_name(parent_path).theory, parent_path.dir.implode, timeout_secs)
                  Right(Map("status" -> "written", "length" -> new_text.length))
                }
            }
        }
    }
  }

  private def extract_markup_text(results: Command.Results, tags: Set[String]): List[String] =
    results.iterator.flatMap { case (_, elem) =>
      elem match {
        case XML.Elem(Markup(tag, _), body) if tags.contains(tag) => Some(XML.content(body))
        case _ => None
      }
    }.toList

  private def handle_get_state(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    str(params, "path") match {
      case None => Left("Missing path")
      case Some(path_str) =>
        val line_num = int(params, "line")
        val timeout_secs = timeout(params)

        file_snapshot(path_str, timeout_secs).flatMap { case (snap, _, doc) =>
          if (line_num.isEmpty || line_num.exists(_ <= 0)) {
            /* file-level diagnostics */
            val diags = session.command_iterator(snap).flatMap { case (cmd, offset) =>
              val status = session.command_status(snap, cmd)
              val results = snap.command_results(cmd)
              val line = doc.position(offset).line + 1
              val errors = if (status.is_failed)
                extract_markup_text(results, Set(Markup.ERROR, Markup.ERROR_MESSAGE))
                  .map(m => Map[String, Any]("severity" -> "error", "line" -> line, "message" -> m.trim))
              else Nil
              val warnings = extract_markup_text(results, Set(Markup.WARNING, Markup.WARNING_MESSAGE))
                .map(m => Map[String, Any]("severity" -> "warning", "line" -> line, "message" -> m.trim))
              errors ++ warnings
            }.toList
            Right(Map("type" -> "file_diagnostics", "diagnostics" -> diags))
          } else {
            /* command-level state */
            val target_cmd = snap.node.command_iterator()
              .filter { case (cmd, _) => cmd.source.trim.nonEmpty }
              .toList
              .filter { case (_, offset) => doc.position(offset).line + 1 <= line_num.get }
              .lastOption

            target_cmd match {
              case Some((cmd, offset)) =>
                val results = snap.command_results(cmd)
                val state_text = extract_markup_text(results, Set(Markup.STATE, Markup.STATE_MESSAGE)).mkString("\n").trim
                val writeln_text = extract_markup_text(results, Set(Markup.WRITELN, Markup.WRITELN_MESSAGE)).mkString("\n").trim
                val info_text = extract_markup_text(results, Set(Markup.INFORMATION, Markup.INFORMATION_MESSAGE)).mkString("\n").trim
                val error_text = extract_markup_text(results, Set(Markup.ERROR, Markup.ERROR_MESSAGE)).mkString("\n").trim
                val warning_text = extract_markup_text(results, Set(Markup.WARNING, Markup.WARNING_MESSAGE)).mkString("\n").trim

                val subgoals = scala.collection.mutable.ListBuffer[String]()
                val local_facts = scala.collection.mutable.ListBuffer[String]()
                val sendbacks = scala.collection.mutable.ListBuffer[String]()
                val timings = scala.collection.mutable.ListBuffer[Map[String, String]]()
                val exports = scala.collection.mutable.ListBuffer[Map[String, String]]()

                def traverse(trees: List[XML.Tree]): Unit = {
                  trees.foreach {
                    case XML.Elem(Markup(Markup.SUBGOAL, _), body) =>
                      subgoals += XML.content(body).trim
                      traverse(body)
                    case XML.Elem(Markup(Markup.FACT, props), body) =>
                      props.find(_._1 == Markup.NAME).foreach(p => local_facts += p._2)
                      traverse(body)
                    case XML.Elem(Markup("local_fact", props), body) =>
                      props.find(_._1 == Markup.NAME).foreach(p => local_facts += p._2)
                      traverse(body)
                    case XML.Elem(Markup(Markup.SENDBACK, _), body) =>
                      sendbacks += XML.content(body).trim
                      traverse(body)
                    case XML.Elem(Markup("timing", props), body) =>
                      timings += props.toMap
                      traverse(body)
                    case XML.Elem(Markup(Markup.EXPORT, props), body) =>
                      exports += props.toMap
                      traverse(body)
                    case XML.Elem(Markup(Markup.THEORY_EXPORTS, props), body) =>
                      exports += props.toMap
                      traverse(body)
                    case XML.Elem(_, body) =>
                      traverse(body)
                    case _ =>
                  }
                }

                results.iterator.foreach { case (_, elem) => traverse(List(elem)) }

                /* extract variables, locales, bindings, class parameters from command source markup */
                val text_range = cmd.core_range + offset
                val var_kinds = Set(Markup.FREE, Markup.FIXED, Markup.BOUND, Markup.VAR, Markup.SKOLEM, Markup.CONSTANT)
                val type_kinds = Set(Markup.TYPING, Markup.ML_TYPING)
                val markup = snap.cumulate(text_range, List.empty[XML.Elem],
                  Markup.Elements(Markup.TYPING, Markup.ML_TYPING, Markup.FREE, Markup.FIXED,
                    Markup.BOUND, Markup.VAR, Markup.SKOLEM, Markup.CONSTANT, Markup.ENTITY,
                    Markup.LOCALE, Markup.BINDING, Markup.CLASS_PARAMETER),
                  _ => { case (acc, Text.Info(_, m)) => Some(m :: acc) }
                )

                val vars = scala.collection.mutable.ListBuffer[Map[String, Any]]()
                val locales = scala.collection.mutable.ListBuffer[String]()
                val bindings = scala.collection.mutable.ListBuffer[String]()
                val class_params = scala.collection.mutable.ListBuffer[String]()

                markup.foreach { case Text.Info(r, elems) =>
                  val kind_elem = elems.find(e => var_kinds.contains(e.name))
                  val typing_elem = elems.find(e => type_kinds.contains(e.name))
                  val locale_elem = elems.find(e => e.name == Markup.LOCALE)
                  val binding_elem = elems.find(e => e.name == Markup.BINDING)
                  val class_param_elem = elems.find(e => e.name == Markup.CLASS_PARAMETER)
                  val entity_locale = elems.collectFirst {
                    case XML.Elem(Markup(Markup.ENTITY, props), _)
                      if props.toMap.getOrElse("kind", "") == "locale" =>
                        props.toMap.getOrElse("name", "")
                  }
                  val entity_binding = elems.collectFirst {
                    case XML.Elem(Markup(Markup.ENTITY, props), _)
                      if props.toMap.getOrElse("kind", "") == "binding" =>
                        props.toMap.getOrElse("name", "")
                  }
                  val entity_class_param = elems.collectFirst {
                    case XML.Elem(Markup(Markup.ENTITY, props), _)
                      if props.toMap.getOrElse("kind", "") == "class_parameter" =>
                        props.toMap.getOrElse("name", "")
                  }
                  val entity_constant = elems.exists {
                    case XML.Elem(Markup(Markup.ENTITY, props), _) =>
                      props.toMap.getOrElse("kind", "") == "constant"
                    case _ => false
                  }
                  val text_offset = r.start - offset
                  if (text_offset >= 0 && text_offset + r.length <= cmd.source.length) {
                    val name = cmd.source.substring(text_offset, text_offset + r.length)
                    if (locale_elem.isDefined || entity_locale.isDefined) locales += entity_locale.getOrElse(name)
                    if (binding_elem.isDefined || entity_binding.isDefined) bindings += entity_binding.getOrElse(name)
                    if (class_param_elem.isDefined || entity_class_param.isDefined) class_params += entity_class_param.getOrElse(name)
                    if (kind_elem.isDefined || typing_elem.isDefined || entity_constant) {
                      val kind = kind_elem.map(_.name).getOrElse(
                        if (entity_constant) "constant" else "typed"
                      )
                      val typ = typing_elem.map(e => XML.content(e.body)).getOrElse("unknown")
                      vars += Map("name" -> name, "kind" -> kind, "type" -> typ)
                    }
                  }
                }
                val vars_distinct = vars.distinctBy(v => v("name").toString + v("kind").toString).toList

                val bound_facts = markup.flatMap { case Text.Info(_, elems) =>
                  elems.flatMap {
                    case XML.Elem(Markup(Markup.ENTITY, props), _) =>
                      val p = props.toMap
                      if (p.getOrElse("kind", "") == "fact" && p.contains("name"))
                        Some(p("name").toString)
                      else None
                    case _ => None
                  }
                }.distinct

                val status = session.command_status(snap, cmd)
                val status_str =
                  if (status.is_failed) "error"
                  else if (status.is_finished) "ok"
                  else if (status.is_running) "running"
                  else if (status.is_unprocessed) "unprocessed"
                  else "pending"

                val response = scala.collection.mutable.LinkedHashMap[String, Any](
                  "type" -> "command_state",
                  "source" -> cmd.source.trim,
                  "status" -> status_str,
                  "variables" -> vars_distinct
                )

                if (state_text.nonEmpty) response("state") = state_text
                if (subgoals.nonEmpty) response("subgoals") = subgoals.toList

                val all_local_facts = (local_facts.toList ++ bound_facts).distinct
                if (all_local_facts.nonEmpty) response("local_facts") = all_local_facts

                if (sendbacks.nonEmpty) response("sendback") = sendbacks.toList.distinct
                if (timings.nonEmpty) response("timing") = timings.toList
                if (exports.nonEmpty) response("exports") = exports.toList
                if (locales.nonEmpty) response("locale") = locales.toList.distinct
                if (bindings.nonEmpty) response("binding") = bindings.toList.distinct
                if (class_params.nonEmpty) response("class_parameter") = class_params.toList.distinct

                if (writeln_text.nonEmpty) response("writeln") = writeln_text
                if (info_text.nonEmpty) response("information") = info_text
                if (warning_text.nonEmpty) response("warning") = warning_text
                if (error_text.nonEmpty) response("error") = error_text

                Right(response.toMap)
              case None => Left(s"No command found at line $line_num")
            }
          }
        }
    }
  }

  private def handle_list_entities(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    str(params, "path") match {
      case Some(p) =>
        file_snapshot(p).flatMap { case (snap, _, doc) =>
          val entities = session.command_iterator(snap).flatMap { case (cmd, offset) =>
            val text_range = cmd.core_range + offset
            val markup = snap.cumulate(text_range, List.empty[XML.Elem],
              Markup.Elements(Markup.ENTITY),
              _ => { case (acc, Text.Info(_, m)) => Some(m :: acc) }
            )
            markup.flatMap { case Text.Info(_, elems) =>
              elems.flatMap {
                case XML.Elem(Markup(Markup.ENTITY, props), _) =>
                  val p = props.toMap
                  if (p.contains("def") && p.contains("name")) {
                    val name = p("name")
                    val kind = p.getOrElse("kind", "unknown")
                    if (kind != "theory" && kind != "fixed")
                      Some(Map[String, Any]("name" -> name, "kind" -> kind, "line" -> (doc.position(offset).line + 1)))
                    else None
                  } else None
                case _ => None
              }
            }
          }.toList.distinctBy(e => e("name").toString)
          Right(Map("entities" -> entities))
        }
      case None => Left("Missing path parameter")
    }
  }

  private def handle_find_definition(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    str(params, "path") match {
      case None => Left("Missing path")
      case Some(path_str) =>
        val line_num = int(params, "line")
        val term_name = str(params, "term_name")
        if (line_num.isEmpty || line_num.exists(_ <= 0)) return Left("Missing or invalid line number")

        file_snapshot(path_str).flatMap { case (snap, _, doc) =>
          val target_cmd = snap.node.command_iterator()
              .filter { case (cmd, _) => cmd.source.trim.nonEmpty }
              .toList
              .filter { case (_, offset) => doc.position(offset).line + 1 <= line_num.get }
              .lastOption

          target_cmd match {
            case Some((cmd, offset)) =>
              val text_range = cmd.core_range + offset
              val markup = snap.cumulate(text_range, List.empty[XML.Tree],
                Markup.Elements(Markup.ENTITY),
                _ => { case (acc, Text.Info(_, m)) => Some(m :: acc) }
              )

              val definitions = markup.flatMap { case Text.Info(_, elems) =>
                elems.flatMap {
                  case XML.Elem(Markup(Markup.ENTITY, props), _) =>
                    val p = props.toMap
                    if (p.contains("name") && p.contains("def_file") && p.contains("def_line")) {
                      val name = p("name")
                      if (term_name.isEmpty || term_name.contains(name)) {
                        try {
                          val def_file = Path.explode(p("def_file")).expand.implode
                          val def_line = p("def_line").toInt
                          val def_content = if (new java.io.File(def_file).exists) {
                            val lines = File.read(Path.explode(def_file)).split("\n")
                            val start_idx = Math.max(0, def_line - 1)
                            val end_idx = Math.min(lines.length, start_idx + 15)
                            lines.slice(start_idx, end_idx).mkString("\n")
                          } else "Source file not available on disk."
                          Some(Map[String, Any](
                            "name" -> name,
                            "kind" -> p.getOrElse("kind", "unknown"),
                            "file" -> def_file,
                            "line" -> def_line,
                            "source_snippet" -> def_content
                          ))
                        } catch { case _: Exception => None }
                      } else None
                    } else None
                  case _ => None
                }
              }.distinctBy(d => d("name").toString + d("file").toString)
              Right(Map("definitions" -> definitions))

            case None => Left(s"No command found at line $line_num")
          }
        }
    }
  }

  private def handle_create_scratch(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    val name_suffix = str(params, "name_suffix")
    session.create_scratch_theory(name_suffix = name_suffix) match {
      case Right((theory_name, theory_path)) =>
        Right(Map("theory_name" -> theory_name, "theory_path" -> theory_path))
      case Left(err) => Left(err)
    }
  }

  private def handle_check_theory(params: Map[String, Any]): Either[String, Map[String, Any]] =
  {
    val timeout_secs = timeout(params)
    str(params, "path") match {
      case Some(p) =>
        val path = Path.explode(p).expand
        session.check_theory(session.node_name(path).theory, path.dir.implode, timeout_secs)
        Right(Map("status" -> "checked", "path" -> p))
      case None =>
        val (static, dynamic, _) = session.loaded_theories
        val all = static ++ dynamic
        all.foreach(name => session.check_theory(name.theory, Path.explode(name.node).dir.implode, timeout_secs))
        Right(Map("status" -> "checked_all", "count" -> all.length))
    }
  }
}
