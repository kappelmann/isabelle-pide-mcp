/*  Title:      PIDE_MCP/mcp_server.scala
    Author:     Kevin Kappelmann

JSON-RPC server loop for the Model Context Protocol.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.concurrent.{ExecutorService, Executors}


object MCP_Config
{
  val name = "isabelle_pide_mcp"
  val version = "0.1.0"
  val protocol_version = "2025-11-25"
}

class MCP_Server(session: PIDE_Session)
{
  private val mcp_tools = new MCP_Tools(session)
  private val executor: ExecutorService = Executors.newCachedThreadPool()

  private def json_safe(value: Any): Any = value match {
    case Some(v) => json_safe(v)
    case None => null
    case m: Map[_, _] => m.map { case (k, v) => (k.toString, json_safe(v)) }.toMap
    case l: List[_] => l.map(json_safe)
    case _ => value
  }

  private def respond(out: PrintWriter, body: Map[String, Any]): Unit =
    out.synchronized {
      out.println(JSON.Format(json_safe(body).asInstanceOf[JSON.Object.T]))
      out.flush()
    }

  private def rpc_error(id: Option[Any], code: Int, msg: String): Map[String, Any] =
    Map("jsonrpc" -> "2.0", "id" -> id, "error" -> Map("code" -> code, "message" -> msg))

  private def rpc_result(id: Option[Any], result: Any): Map[String, Any] =
    Map("jsonrpc" -> "2.0", "id" -> id, "result" -> result)

  /* MCP protocol version negotiation: return the highest version
     supported by both client and server */
  private def negotiate_protocol_version(client_version: String): String =
    if (client_version < MCP_Config.protocol_version) client_version
    else MCP_Config.protocol_version

  def run(): Unit =
  {
    val in = new BufferedReader(new InputStreamReader(System.in))
    val out = new PrintWriter(System.out, true)
    val err = System.err

    var line: String = null
    while ({ line = in.readLine(); line != null }) {
      try {
        val request = JSON.parse(line).asInstanceOf[Map[String, Any]]
        val method = request.get("method").map(_.toString)
        val id = request.get("id")

        method match {
          case Some("initialize") =>
            val client_version = request.get("params") match {
              case Some(m: Map[_, _]) => m.asInstanceOf[Map[String, Any]]
                .get("protocolVersion").map(_.toString).getOrElse(MCP_Config.protocol_version)
              case _ => MCP_Config.protocol_version
            }
            respond(out, rpc_result(id, Map(
              "protocolVersion" -> negotiate_protocol_version(client_version),
              "capabilities" -> Map("tools" -> JSON.Object()),
              "serverInfo" -> Map("name" -> MCP_Config.name, "version" -> MCP_Config.version),
              "instructions" -> "TODO description")))

          case Some("tools/list") =>
            val tools = Tool_Definitions.all.map { case (name, schema) =>
              schema.annotations match {
                case Some(a) => Map("name" -> name, "description" -> schema.description,
                  "inputSchema" -> schema.input_schema, "annotations" -> a)
                case None => Map("name" -> name, "description" -> schema.description,
                  "inputSchema" -> schema.input_schema)
              }
            }
            respond(out, rpc_result(id, Map("tools" -> tools)))

          case Some("tools/call") =>
            val captured_request = request
            val captured_id = id
            executor.submit(new Runnable {
              def run(): Unit = handle_tool_call(captured_id, captured_request, out)
            })

          case Some(m) if m.startsWith("notifications/") => ()

          case _ => respond(out, rpc_error(id, -32601, s"Method not found: $method"))
        }
      } catch {
        case ex: Exception =>
          err.println(s"Error: ${ex.getMessage}")
          ex.printStackTrace(err)
          respond(out, rpc_error(None, -32700, s"Parse error: ${ex.getMessage}"))
      }
    }
    executor.shutdown()
  }

  private def handle_tool_call(
    id: Option[Any],
    request: Map[String, Any],
    out: PrintWriter
  ): Unit =
  {
    try {
      val params = request.get("params") match {
        case Some(m: Map[_, _]) => m.asInstanceOf[Map[String, Any]]
        case _ => Map.empty[String, Any]
      }
      val name = params.get("name").map(_.toString).getOrElse("")
      val args = params.get("arguments") match {
        case Some(a: Map[_, _]) => a.asInstanceOf[Map[String, Any]]
        case _ => Map.empty[String, Any]
      }

      mcp_tools.invoke(name, args) match {
        case Right(result) =>
          respond(out, rpc_result(id, Map("content" -> List(
            Map("type" -> "text", "text" -> JSON.Format(json_safe(result).asInstanceOf[JSON.Object.T]))))))
        case Left(error) =>
          respond(out, rpc_error(id, -32000, error))
      }
    } catch {
      case ex: Exception =>
        respond(out, rpc_error(id, -32603, s"Internal error: ${ex.getMessage}"))
    }
  }
}
