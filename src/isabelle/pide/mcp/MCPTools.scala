package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls

class MCPTools(val session: PIDESession) {

  private def str(params: Map[String, Any], key: String): Option[String] =
    params.get(key).map(_.toString)

  private def int(params: Map[String, Any], key: String): Int =
    params.get(key).flatMap {
      case n: Number => Some(n.intValue())
      case s: String => s.toIntOption
      case _ => None
    }.getOrElse(0)

  private def timeout(params: Map[String, Any]): Int = {
    val t = int(params, "timeout_secs")
    if (t > 0) t else PIDESession.default_timeout_secs
  }

  // Load theory into session if not already present
  private def ensureTracked(pathStr: String, timeoutSecs: Int = PIDESession.default_timeout_secs): Either[String, Path] = {
    val path = Path.explode(pathStr).expand
    if (!path.is_file) return Left(s"File not found: $path")
    session.open_theory(path, timeoutSecs) match {
      case Left(e) => Left(e)
      case Right(_) => Right(path)
    }
  }

  private def fileSnapshot(pathStr: String, timeoutSecs: Int = PIDESession.default_timeout_secs): Either[String, (Document.Snapshot, Document.Node.Name, Line.Document)] = {
    ensureTracked(pathStr, timeoutSecs).flatMap { path =>
      val node = session.node_name(path)
      try {
        val snap = session.snapshot(node)
        val text = snap.node.source
        Right((snap, node, Line.Document(text)))
      } catch {
        case _: Exception => Left(s"Could not get snapshot for $path")
      }
    }
  }

  def invoke(name: String, params: Map[String, Any]): Either[String, Map[String, Any]] = {
    name match {
      case "list_tracked_theories" => handleListTrackedTheories()
      case "read_theory" => handleReadTheory(params)
      case "read_ml_file" => handleReadMLFile(params)
      case "edit_theory" => handleEditTheory(params)
      case "edit_ml_file" => handleEditMLFile(params)
      case "get_state" => handleGetState(params)
      case "list_entities" => handleListEntities(params)
      case "find_definition" => handleFindDefinition(params)
      case "scratch" => handleScratch(params)
      case "check_theory" => handleCheckTheory(params)
      case _ => Left(s"Unknown tool: $name")
    }
  }

  private def handleListTrackedTheories(): Either[String, Map[String, Any]] = {
    val files = session.loaded_theories.map { name =>
      Map("path" -> name.node, "theory" -> name.theory)
    }
    Right(Map("files" -> files))
  }

  private def readLines(text: String, params: Map[String, Any]): String = {
    val allLines = text.split("\n", -1).zipWithIndex.map { case (l, i) => s"${i+1}: $l" }
    val start = int(params, "start_line")
    val end = int(params, "end_line")
    val startIdx = if (start > 0) start - 1 else 0
    val endIdx = if (end > 0) Math.min(end, allLines.length) else allLines.length
    allLines.slice(startIdx, endIdx).mkString("\n")
  }

  private def handleReadTheory(params: Map[String, Any]): Either[String, Map[String, Any]] = {
    str(params, "path") match {
      case None => Left("Missing path parameter")
      case Some(p) =>
        fileSnapshot(p, timeout(params)).map { case (_, _, doc) =>
          val text = doc.lines.map(_.text).mkString("\n")
          Map("content" -> readLines(text, params))
        }
    }
  }

  private def handleReadMLFile(params: Map[String, Any]): Either[String, Map[String, Any]] = {
    str(params, "path") match {
      case None => Left("Missing path parameter")
      case Some(p) =>
        val path = Path.explode(p).expand
        if (!path.is_file) Left(s"File not found: $path")
        else Right(Map("content" -> readLines(File.read(path), params)))
    }
  }

  private def replaceLines(currentText: String, content: String, startLine: Int, endLine: Int, oldContent: String): Either[String, String] = {
    if (startLine <= 0) Right(content)
    else {
      val lines = currentText.split("\n", -1)
      val end = if (endLine <= 0) startLine else endLine
      val startIdx = startLine - 1
      if (startIdx < 0 || startIdx > lines.length) Left(s"Invalid start_line: $startLine")
      else {
        val actualOld = lines.slice(startIdx, end).mkString("\n")
        if (actualOld != oldContent)
          Left(s"old_content mismatch at lines $startLine-$end.\nExpected:\n$oldContent\nActual:\n$actualOld")
        else {
          val prefix = lines.take(startIdx)
          val suffix = lines.drop(end)
          val contentLines = content.split("\n", -1)
          Right((prefix ++ contentLines ++ suffix).mkString("\n"))
        }
      }
    }
  }

  private def handleEditTheory(params: Map[String, Any]): Either[String, Map[String, Any]] = {
    str(params, "path") match {
      case None => Left("Missing path")
      case Some(pathStr) =>
        str(params, "content") match {
          case None => Left("Missing content")
          case Some(content) =>
            val startLine = int(params, "start_line")
            val endLine = int(params, "end_line")
            // old_content is mandatory when start_line is specified (i.e., partial edit)
            if (startLine > 0 && str(params, "old_content").isEmpty)
              return Left("Missing old_content: must specify expected content at target lines")
            val oldContent = str(params, "old_content").getOrElse("")
            val timeoutSecs = timeout(params)
            ensureTracked(pathStr, timeoutSecs).flatMap { path =>
              val currentText = File.read(path)

              replaceLines(currentText, content, startLine, endLine, oldContent).flatMap { newText =>
                File.write(path, newText)
                session.update_theory(List(session.node_name(path).theory), path.dir.implode, timeoutSecs)
                Right(Map("status" -> "written", "length" -> newText.length))
              }
            }
        }
    }
  }

  private def handleEditMLFile(params: Map[String, Any]): Either[String, Map[String, Any]] = {
    str(params, "path") match {
      case None => Left("Missing path")
      case Some(pathStr) =>
        str(params, "content") match {
          case None => Left("Missing content")
          case Some(content) =>
            str(params, "parent_theory") match {
              case None => Left("Missing parent_theory")
              case Some(parentStr) =>
                val startLine = int(params, "start_line")
                val endLine = int(params, "end_line")
                if (startLine > 0 && str(params, "old_content").isEmpty)
                  return Left("Missing old_content: must specify expected content at target lines")
                val oldContent = str(params, "old_content").getOrElse("")
                val timeoutSecs = timeout(params)
                val path = Path.explode(pathStr).expand
                if (!path.is_file) return Left(s"File not found: $path")
                val currentText = File.read(path)

                replaceLines(currentText, content, startLine, endLine, oldContent).flatMap { newText =>
                  File.write(path, newText)
                  val parentPath = Path.explode(parentStr).expand
                  ensureTracked(parentStr, timeoutSecs).flatMap { _ =>
                    session.update_theory(List(session.node_name(parentPath).theory), parentPath.dir.implode, timeoutSecs)
                    Right(Map("status" -> "written", "length" -> newText.length))
                  }
                }
            }
        }
    }
  }

  private def extractMarkupText(results: Command.Results, tags: Set[String]): List[String] =
    results.iterator.flatMap { case (_, elem) =>
      elem match {
        case XML.Elem(Markup(tag, _), body) if tags.contains(tag) => Some(XML.content(body))
        case _ => None
      }
    }.toList

  private def handleGetState(params: Map[String, Any]): Either[String, Map[String, Any]] = {
    str(params, "path") match {
      case None => Left("Missing path")
      case Some(pathStr) =>
        val lineNum = int(params, "line")
        val timeoutSecs = timeout(params)

        fileSnapshot(pathStr, timeoutSecs).flatMap { case (snap, _, doc) =>
          if (lineNum <= 0) {
            val diags = session.command_iterator(snap).flatMap { case (cmd, offset) =>
              val status = session.command_status(snap, cmd)
              val results = snap.command_results(cmd)
              val line = doc.position(offset).line + 1
              val errors = if (status.is_failed)
                extractMarkupText(results, Set(Markup.ERROR, Markup.ERROR_MESSAGE))
                  .map(m => Map[String, Any]("severity" -> "error", "line" -> line, "message" -> m.trim))
              else Nil
              val warnings = extractMarkupText(results, Set(Markup.WARNING, Markup.WARNING_MESSAGE))
                .map(m => Map[String, Any]("severity" -> "warning", "line" -> line, "message" -> m.trim))
              errors ++ warnings
            }.toList
            Right(Map("type" -> "file_diagnostics", "diagnostics" -> diags))
          } else {
            // Find the last non-whitespace command whose start line is <= lineNum.
            val targetCmd = snap.node.command_iterator()
              .filter { case (cmd, _) => cmd.source.trim.nonEmpty }
              .toList
              .filter { case (_, offset) => doc.position(offset).line + 1 <= lineNum }
              .lastOption
            
            targetCmd match {
              case Some((cmd, offset)) =>
                val results = snap.command_results(cmd)
                val stateText = extractMarkupText(results, Set(Markup.STATE, Markup.STATE_MESSAGE)).mkString("\n").trim
                val writelnText = extractMarkupText(results, Set(Markup.WRITELN, Markup.WRITELN_MESSAGE)).mkString("\n").trim
                val infoText = extractMarkupText(results, Set(Markup.INFORMATION, Markup.INFORMATION_MESSAGE)).mkString("\n").trim
                val errorText = extractMarkupText(results, Set(Markup.ERROR, Markup.ERROR_MESSAGE)).mkString("\n").trim
                val warningText = extractMarkupText(results, Set(Markup.WARNING, Markup.WARNING_MESSAGE)).mkString("\n").trim
                
                // Extract variables from command source markup
                val textRange = cmd.core_range + offset
                val varKinds = Set(Markup.FREE, Markup.FIXED, Markup.BOUND, Markup.VAR, Markup.SKOLEM)
                val markup = snap.cumulate(textRange, List.empty[XML.Elem],
                  Markup.Elements(Markup.TYPING, Markup.FREE, Markup.FIXED, Markup.BOUND, Markup.VAR, Markup.SKOLEM),
                  _ => { case (acc, Text.Info(_, m)) => Some(m :: acc) }
                )
                val vars = markup.flatMap { case Text.Info(r, elems) =>
                  val isVar = elems.exists(e => varKinds.contains(e.name))
                  if (isVar) {
                    val typing = elems.find(_.name == Markup.TYPING).map(e => XML.content(e.body))
                    val textOffset = r.start - offset
                    if (textOffset >= 0 && textOffset + r.length <= cmd.source.length) {
                      val name = cmd.source.substring(textOffset, textOffset + r.length)
                      val kind = elems.find(e => varKinds.contains(e.name)).get.name
                      Some(Map("name" -> name, "kind" -> kind, "type" -> typing.getOrElse("unknown")))
                    } else None
                  } else None
                }.distinctBy(v => v("name").toString + v("kind").toString)

                val status = session.command_status(snap, cmd)
                val statusStr =
                  if (status.is_failed) "error"
                  else if (status.is_finished) "ok"
                  else if (status.is_running) "running"
                  else if (status.is_unprocessed) "unprocessed"
                  else "pending"

                val response = scala.collection.mutable.LinkedHashMap[String, Any](
                  "type" -> "command_state",
                  "source" -> cmd.source.trim,
                  "status" -> statusStr,
                  "state" -> stateText,
                  "variables" -> vars
                )
                if (writelnText.nonEmpty) response("writeln") = writelnText
                if (infoText.nonEmpty) response("information") = infoText
                if (warningText.nonEmpty) response("warning") = warningText
                if (errorText.nonEmpty) response("error") = errorText

                Right(response.toMap)
              case None => Left(s"No command found at line $lineNum")
            }
          }
        }
    }
  }

  private def handleListEntities(params: Map[String, Any]): Either[String, Map[String, Any]] = {
    str(params, "path") match {
      case Some(p) =>
        fileSnapshot(p).flatMap { case (snap, _, doc) =>
          val entities = session.command_iterator(snap).flatMap { case (cmd, offset) =>
            val textRange = cmd.core_range + offset
            val markup = snap.cumulate(textRange, List.empty[XML.Elem],
              Markup.Elements(Markup.ENTITY),
              _ => { case (acc, Text.Info(_, m)) => Some(m :: acc) }
            )
            markup.flatMap { case Text.Info(_, elems) =>
              elems.collect {
                case XML.Elem(Markup(Markup.ENTITY, props), _) =>
                  val propsMap = props.toMap
                  (propsMap.contains("def") && propsMap.contains("name"), propsMap)
              }.collect { case (true, propsMap) =>
                val name = propsMap("name")
                val kind = propsMap.getOrElse("kind", "unknown")
                val line = doc.position(offset).line + 1
                (name, kind, line)
              }.filter { case (name, kind, _) =>
                kind != "theory" && kind != "fixed" && !name.startsWith("local.")
              }.map { case (name, kind, line) =>
                Map[String, Any]("name" -> name, "kind" -> kind, "line" -> line)
              }
            }
          }.toList.distinctBy(e => e("name").toString)
          Right(Map("entities" -> entities))
        }
      case None => Left("Missing path parameter")
    }
  }

  private def handleFindDefinition(params: Map[String, Any]): Either[String, Map[String, Any]] = {
    str(params, "path") match {
      case None => Left("Missing path")
      case Some(pathStr) =>
        val lineNum = int(params, "line")
        val termName = str(params, "term_name")
        if (lineNum <= 0) return Left("Missing or invalid line number")

        fileSnapshot(pathStr).flatMap { case (snap, _, doc) =>
          val targetCmd = snap.node.command_iterator()
              .filter { case (cmd, _) => cmd.source.trim.nonEmpty }
              .toList
              .filter { case (_, offset) => doc.position(offset).line + 1 <= lineNum }
              .lastOption

          targetCmd match {
            case Some((cmd, offset)) =>
              val textRange = cmd.core_range + offset
              val markup = snap.cumulate(textRange, List.empty[XML.Tree],
                Markup.Elements(Markup.ENTITY),
                _ => { case (acc, Text.Info(_, m)) => Some(m :: acc) }
              )

              val definitions = markup.flatMap { case Text.Info(_, elems) =>
                elems.flatMap {
                  case XML.Elem(Markup(Markup.ENTITY, props), _) =>
                    val p = props.toMap
                    if (p.contains("name") && p.contains("def_file") && p.contains("def_line")) {
                      val name = p("name")
                      if (termName.isEmpty || termName.contains(name)) {
                        try {
                          val defFile = Path.explode(p("def_file")).expand.implode
                          val defLine = p("def_line").toInt
                          val defContent = if (new java.io.File(defFile).exists) {
                            val lines = File.read(Path.explode(defFile)).split("\n")
                            val startIdx = Math.max(0, defLine - 1)
                            val endIdx = Math.min(lines.length, startIdx + 15)
                            lines.slice(startIdx, endIdx).mkString("\n")
                          } else "Source file not available on disk."
                          Some(Map[String, Any](
                            "name" -> name,
                            "kind" -> p.getOrElse("kind", "unknown"),
                            "file" -> defFile,
                            "line" -> defLine,
                            "source_snippet" -> defContent
                          ))
                        } catch { case _: Exception => None }
                      } else None
                    } else None
                  case _ => None
                }
              }.distinctBy(d => d("name").toString + d("file").toString)
              Right(Map("definitions" -> definitions))
              
            case None => Left(s"No command found at line $lineNum")
          }
        }
    }
  }

  private def handleScratch(params: Map[String, Any]): Either[String, Map[String, Any]] = {
    str(params, "commands") match {
      case None => Left("Missing commands")
      case Some(commands) =>
        str(params, "imports") match {
          case None => Left("Missing imports")
          case Some(imports) =>
            val timeoutSecs = timeout(params)
            PIDESession.runQuery(session, commands, imports, timeoutSecs) match {
              case Right((output, theoryName, theoryPath)) =>
                Right(Map("output" -> output, "theory_name" -> theoryName, "theory_path" -> theoryPath))
              case Left(err) => Left(err)
            }
        }
    }
  }

  private def handleCheckTheory(params: Map[String, Any]): Either[String, Map[String, Any]] = {
    val timeoutSecs = timeout(params)
    str(params, "path") match {
      case Some(p) =>
        ensureTracked(p, timeoutSecs).flatMap { path =>
          session.update_theory(List(session.node_name(path).theory), path.dir.implode, timeoutSecs)
          Right(Map("status" -> "checked", "path" -> p))
        }
      case None =>
        val all = session.loaded_theories
        all.foreach(name => session.update_theory(List(name.theory), Path.explode(name.node).dir.implode, timeoutSecs))
        Right(Map("status" -> "checked_all", "count" -> all.length))
    }
  }
}
