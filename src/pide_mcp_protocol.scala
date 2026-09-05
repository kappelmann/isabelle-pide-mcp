/*  Title:      PIDE_MCP/pide_mcp_protocol.scala
    Author:     Kevin Kappelmann

JSON-RPC and MCP.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Protocol {
  object Config {
    val name = "isabelle_pide_mcp"
    val version = "0.1.0"
    val jsonrpc_version = "2.0"
    val protocol_version = "2025-11-25"
    val instructions: String =
      "Interactive proof development using one or more Isabelle sessions. " +
      "Some sessions might have already been started. " +
      "Add material incrementally: large edits make errors and nontermination hard to isolate."
  }

  object JSON_RPC {
    type Id = String | Long
    object Id {
      def unapply(value: JSON.T): Option[Id] =
        value match {
          case string: String => Some(string)
          case JSON.Value.Long(long) => Some(long)
          case _ => None
        }
    }

    object Error_Code {
      // JSON-RPC 2.0 reserved error codes (https://www.jsonrpc.org/specification#error_object)
      val PARSE_ERROR: Int = -32700
      val INVALID_REQUEST: Int = -32600
      val METHOD_NOT_FOUND: Int = -32601
      val INVALID_PARAMS: Int = -32602
      val INTERNAL_ERROR: Int = -32603
    }

    def id(request: JSON.Object.T): Option[Id] = JSON.value(request, "id", Id.unapply)

    def result(id: Id, result: JSON.Object.T): JSON.Object.T =
      JSON_Object("jsonrpc" -> Config.jsonrpc_version, "id" -> id, "result" -> result)

    def error(id: Id | Null, code: Int, message: String): JSON.Object.T =
      JSON_Object("jsonrpc" -> Config.jsonrpc_version, "id" -> id,
        "error" -> JSON_Object("code" -> code, "message" -> message))

    def notification(method: String, params: JSON.Object.T): JSON.Object.T =
      JSON_Object("jsonrpc" -> Config.jsonrpc_version, "method" -> method,
        "params" -> params)
  }

  // MCP negotiation: answer with client version if supported; otherwise return the server's supported version
  // TODO: do we support other versions?
  def decide_protocol_version(client_version: String): String =
    Config.protocol_version

  type Progress_Token = JSON_RPC.Id

  private def parse_progress_token(params: JSON.Object.T): Option[Progress_Token] = {
    val meta = PIDE_MCP_JSON.obj_default(
      params, "_meta", JSON.Object.empty, Some("params"))
    PIDE_MCP_JSON.opt_value(
      meta, "progressToken", JSON_RPC.Id.unapply, Some("params._meta"))
  }

  def progress_notification(
    token: Progress_Token,
    serial: Long,
    message: String
  ): JSON.Object.T =
    JSON_RPC.notification("notifications/progress", JSON_Object(
      "progressToken" -> token, "progress" -> serial, "message" -> message))

  def parse_initialize_request(request: JSON.Object.T): String = {
    val params = PIDE_MCP_JSON.obj(request, "params")
    PIDE_MCP_JSON.obj(params, "capabilities", Some("params"))
    val client_info = PIDE_MCP_JSON.obj(params, "clientInfo", Some("params"))
    PIDE_MCP_JSON.string(client_info, "name", Some("clientInfo"))
    PIDE_MCP_JSON.string(client_info, "version", Some("clientInfo"))
    PIDE_MCP_JSON.string(params, "protocolVersion", Some("params"))
  }

  def text_content(text: String): JSON.Object.T =
    JSON_Object("type" -> "text", "text" -> text)

  def tool_result(result: JSON.T, is_error: Boolean = false): JSON.Object.T = {
    val structured = result match {
      case JSON.Object(obj) => obj
      case _ => JSON_Object("result" -> result)
    }
    // MCP spec: needed for errors + should include for non-errors for backwards compatibility
    val content = List(text_content(result match {
      case text: String => text
      case _ => JSON.Format(structured)
    }))
    JSON_Object("content" -> content, "structuredContent" -> structured) ++
      JSON.optional("isError" -> Option.when(is_error)(true))
  }

  sealed case class Cancellation(id: JSON_RPC.Id, reason: Option[String])

  def parse_cancellation_notification(request: JSON.Object.T): Cancellation = {
    val params = PIDE_MCP_JSON.obj(request, "params")
    Cancellation(
      PIDE_MCP_JSON.value(params, "requestId", JSON_RPC.Id.unapply, Some("params")),
      PIDE_MCP_JSON.opt_string(params, "reason", Some("params")))
  }

  sealed case class Tool_Call(
    name: String,
    args: JSON.Object.T,
    progress_token: Option[Progress_Token]
  )

  def parse_tool_call(request: JSON.Object.T): Tool_Call = {
    val params = PIDE_MCP_JSON.obj(request, "params")
    Tool_Call(
      PIDE_MCP_JSON.string(params, "name", Some("params")),
      PIDE_MCP_JSON.obj_default(params, "arguments", JSON.Object.empty, Some("params")),
      parse_progress_token(params))
  }
}
