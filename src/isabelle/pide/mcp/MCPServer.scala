/*  Title:      PIDE_MCP/MCPServer.scala
    Author:     Kevin Kappelmann

JSON-RPC server loop for the Model Context Protocol.
*/

package isabelle.pide.mcp

import isabelle._
import scala.language.unsafeNulls

import java.io.{BufferedReader, InputStreamReader, PrintWriter}
import java.util.concurrent.{ExecutorService, Executors}


object MCPConfig
{
  val name = "isabelle_pide_mcp"
  val version = "0.1.0"
  val protocolVersion = "2024-11-05"
}

class MCPServer(session: PIDESession)
{
  private val mcpTools = new MCPTools(session)
  private val executor: ExecutorService = Executors.newCachedThreadPool()

  private def jsonSafe(value: Any): Any = value match {
    case Some(v) => jsonSafe(v)
    case None => null
    case m: Map[_, _] => m.map { case (k, v) => (k.toString, jsonSafe(v)) }.toMap
    case l: List[_] => l.map(jsonSafe)
    case _ => value
  }

  private def respond(out: PrintWriter, body: Map[String, Any]): Unit =
    out.synchronized {
      out.println(JSON.Format(jsonSafe(body).asInstanceOf[JSON.Object.T]))
      out.flush()
    }

  private def rpcError(id: Option[Any], code: Int, msg: String): Map[String, Any] =
    Map("jsonrpc" -> "2.0", "id" -> id, "error" -> Map("code" -> code, "message" -> msg))

  private def rpcResult(id: Option[Any], result: Any): Map[String, Any] =
    Map("jsonrpc" -> "2.0", "id" -> id, "result" -> result)

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
            respond(out, rpcResult(id, Map(
              "protocolVersion" -> MCPConfig.protocolVersion,
              "capabilities" -> Map("tools" -> JSON.Object()),
              "serverInfo" -> Map("name" -> MCPConfig.name, "version" -> MCPConfig.version))))

          case Some("tools/list") =>
            val tools = ToolDefinitions.all.map { case (name, schema) =>
              Map("name" -> name, "description" -> schema.description, "inputSchema" -> schema.inputSchema)
            }
            respond(out, rpcResult(id, Map("tools" -> tools)))

          case Some("tools/call") =>
            val captured_request = request
            val captured_id = id
            executor.submit(new Runnable {
              def run(): Unit = handleToolCall(captured_id, captured_request, out)
            })

          case Some(m) if m.startsWith("notifications/") => ()

          case _ => respond(out, rpcError(id, -32601, s"Method not found: $method"))
        }
      } catch {
        case ex: Exception =>
          err.println(s"Error: ${ex.getMessage}")
          ex.printStackTrace(err)
          respond(out, rpcError(None, -32700, s"Parse error: ${ex.getMessage}"))
      }
    }
    executor.shutdown()
  }

  private def handleToolCall(
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

      mcpTools.invoke(name, args) match {
        case Right(result) =>
          respond(out, rpcResult(id, Map("content" -> List(
            Map("type" -> "text", "text" -> JSON.Format(jsonSafe(result).asInstanceOf[JSON.Object.T]))))))
        case Left(error) =>
          respond(out, rpcError(id, -32000, error))
      }
    } catch {
      case ex: Exception =>
        respond(out, rpcError(id, -32603, s"Internal error: ${ex.getMessage}"))
    }
  }
}
