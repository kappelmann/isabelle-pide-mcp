/*  Title:      PIDE_MCP/mcp_server.scala
    Author:     Kevin Kappelmann

JSON-RPC server loop for the Model Context Protocol.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls
import java.io.{BufferedReader, InputStreamReader, PrintWriter}


object PIDE_MCP_Config {
  val name = "isabelle_pide_mcp"
  val version = "0.1.0"
  val protocol_version = "2025-11-25"
}

class PIDE_MCP_Server(session: PIDE_MCP_Session) {
  private val handlers = new PIDE_MCP_Tool_Handlers(session)

  private def respond(out: PrintWriter, body: JSON.Object.T): Unit =
    out.synchronized {
      out.println(JSON.Format(body))
      out.flush()
    }

  private def rpc_result(id: Option[Any], result: JSON.Object.T): JSON.Object.T =
    JSON.Object("jsonrpc" -> "2.0", "id" -> id.getOrElse(null), "result" -> result)

  private def rpc_error(id: Option[Any], code: Int, msg: String): JSON.Object.T =
    JSON.Object("jsonrpc" -> "2.0", "id" -> id.getOrElse(null),
      "error" -> JSON.Object("code" -> code, "message" -> msg))

  private def negotiate_protocol_version(client_version: String): String =
    if (client_version < PIDE_MCP_Config.protocol_version) client_version
    else PIDE_MCP_Config.protocol_version

  def run(): Unit = {
    val in = new BufferedReader(new InputStreamReader(System.in))
    val out = new PrintWriter(System.out, true)

    var line: String = null
    while ({ line = in.readLine(); line != null }) {
      try {
        val request = JSON.Object.parse(line)
        val method = JSON.string(request, "method")
        val id = request.get("id")

        method match {
          case Some("initialize") =>
            val client_version = request.get("params") match {
              case Some(JSON.Object(params)) =>
                JSON.string(params, "protocolVersion").getOrElse(PIDE_MCP_Config.protocol_version)
              case _ => PIDE_MCP_Config.protocol_version
            }
            respond(out, rpc_result(id, JSON.Object(
              "protocolVersion" -> negotiate_protocol_version(client_version),
              "capabilities" -> JSON.Object("tools" -> JSON.Object()),
              "serverInfo" -> JSON.Object("name" -> PIDE_MCP_Config.name, "version" -> PIDE_MCP_Config.version),
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

          case _ => respond(out, rpc_error(id, -32601, s"Method not found: $method"))
        }
      } catch {
        case ex: Exception =>
          Output.error_message(s"${ex.getMessage}")
        ex.printStackTrace(System.err.nn)
        respond(out, rpc_error(None, -32700, s"Parse error: ${ex.getMessage}"))
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
            case Exn.Exn(e) => respond(out, rpc_error(id, -32000, Exn.message(e)))
          }
        case None => respond(out, rpc_error(id, -32602, "Missing params or tool name"))
      }
    } catch {
      case ex: Exception =>
        Output.error_message(s"Internal error in tool call: ${ex.getMessage}")
        ex.printStackTrace(System.err.nn)
        respond(out, rpc_error(id, -32603, s"Internal error: ${ex.getMessage}"))
    }
  }
}
