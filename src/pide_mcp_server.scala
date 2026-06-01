/*  Title:      PIDE_MCP/pide_mcp_server.scala
    Author:     Kevin Kappelmann

JSON-RPC server loop for the Model Context Protocol.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls
import java.io.{BufferedReader, InputStreamReader, PrintWriter}


object Config {
  val name = "isabelle_pide_mcp"
  val version = "0.1.0"
  val protocol_version = "2025-11-25"
}

object RPC_Error {
  /* JSON-RPC 2.0 reserved error codes (https://www.jsonrpc.org/specification#error_object) */
  val PARSE_ERROR: Int = -32700
  val METHOD_NOT_FOUND: Int = -32601
  val INVALID_PARAMS: Int = -32602
  val INTERNAL_ERROR: Int = -32603
  /* implementation-defined server errors: range -32000 to -32099 */
  val SERVER_ERROR: Int = -32000
}

class PIDE_MCP_Server(session: PIDE_MCP_Session, log: Logger, verbose: Boolean = false) {
  private val handlers = new PIDE_MCP_Tool_Handlers(session)

  private def respond(out: PrintWriter, body: JSON.Object.T): Unit =
    out.synchronized {
      if (verbose) log(JSON.Format(body))
      out.println(JSON.Format(body))
      out.flush()
    }

  private def rpc_result(id: Option[Any], result: JSON.Object.T): JSON.Object.T =
    JSON.Object("jsonrpc" -> "2.0", "id" -> id.getOrElse(null), "result" -> result)

  private def rpc_error(id: Option[Any], code: Int, msg: String): JSON.Object.T =
    JSON.Object("jsonrpc" -> "2.0", "id" -> id.getOrElse(null),
      "error" -> JSON.Object("code" -> code, "message" -> msg))

  private def negotiate_protocol_version(client_version: String): String =
    if (client_version < Config.protocol_version) client_version
    else Config.protocol_version

  def run(): Unit = {
    val in = new BufferedReader(new InputStreamReader(System.in))
    val out = new PrintWriter(System.out, true)

    Iterator.continually(in.readLine()).takeWhile(_ != null).foreach { line =>
      try {
        if (verbose) log("<<< " + line)
        val request = JSON.Object.parse(line)
        val method = JSON.string(request, "method")
        val id = request.get("id")

        method match {
          case Some("initialize") =>
            val client_version = request.get("params") match {
              case Some(JSON.Object(params)) =>
                JSON.string(params, "protocolVersion").getOrElse(Config.protocol_version)
              case _ => Config.protocol_version
            }
            respond(out, rpc_result(id, JSON.Object(
              "protocolVersion" -> negotiate_protocol_version(client_version),
              "capabilities" -> JSON.Object("tools" -> JSON.Object()),
              "serverInfo" -> JSON.Object("name" -> Config.name, "version" -> Config.version),
              "instructions" -> ("Interactive proof development with Isabelle PIDE MCP.\n\n" +
                "The server automatically and asynchronously checks all commands after edits. Use `get_state` frequently to verify nothing is stuck or failed.\n" +
                "Use `create_scratch` to test proof strategies, automation, and searches before editing your main theory.\n" +
                "Add material incrementally - large edits make errors and nontermination hard to isolate.\n" +
                "If a command takes longer than a few seconds, be suspicious and restructure rather than wait."))))

          case Some("tools/list") =>
            val tools = PIDE_MCP_Tool_Schemas.all.map { case (name, schema) =>
              val entry = JSON.Object("name" -> name, "description" -> schema.description,
                "inputSchema" -> schema.input_schema)
              schema.annotations match {
                case Some(a) => entry + ("annotations" -> a)
                case None => entry
              }
            }
            respond(out, rpc_result(id, JSON.Object("tools" -> tools)))

          case Some("tools/call") =>
            Isabelle_Thread.pool.submit(new Runnable {
              def run(): Unit = handle_tool_call(id, request, out)
            })

          case Some(m) if m.startsWith("notifications/") => ()

          case _ => respond(out, rpc_error(id, RPC_Error.METHOD_NOT_FOUND, s"Method not found: $method"))
        }
      } catch {
        case ex: Exception =>
          log.error_message("Server loop error: " + Exn.print(ex))
          respond(out, rpc_error(None, RPC_Error.PARSE_ERROR, s"Server loop error: ${Exn.message(ex)}"))
      }
    }
  }

  private def parse_tool_call(request: JSON.Object.T): Option[(String, JSON.Object.T)] =
    request.get("params") match {
      case Some(JSON.Object(params)) =>
        JSON.string(params, "name").map(name =>
          (name, params.get("arguments") match {
            case Some(JSON.Object(a)) => a
            case _ => JSON.Object.empty
          }))
      case _ => None
    }

  private def handle_tool_call(
    id: Option[Any],
    request: JSON.Object.T,
    out: PrintWriter
  ): Unit = {
    try {
      parse_tool_call(request) match {
        case Some((name, args)) =>
          handlers.handle(name, args) match {
            case Exn.Res(result) =>
              val content_text = JSON.Format(result)
              respond(out, rpc_result(id, JSON.Object(
                "content" -> List(JSON.Object(
                  "type" -> "text",
                  "text" -> content_text
                ))
              )))
            case Exn.Exn(e) =>
              log.error_message("Tool call error: " + Exn.message(e))
              respond(out, rpc_error(id, RPC_Error.SERVER_ERROR, Exn.message(e)))
          }
        case None => respond(out, rpc_error(id, RPC_Error.INVALID_PARAMS, "Missing params or tool name"))
      }
    } catch {
      case ex: Exception =>
        log.error_message("Internal error in tool call: " + Exn.print(ex))
        respond(out, rpc_error(id, RPC_Error.INTERNAL_ERROR, s"Internal error: ${Exn.message(ex)}"))
    }
  }
}
