/*  Title:      MCP/src/pide_mcp_protocol.scala
    Author:     Kevin Kappelmann

MCP messages.
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Protocol {
  object Config {
    val name = "isabelle_pide_mcp"
    val version = "0.1.0"
    val protocol_version = "2025-11-25"
    val instructions = "Interactive proof development using one or more Isabelle sessions. " +
      "Some sessions might have already been started."
  }

  private object Fields {
    private val obj = JSON_Schema.Format.obj
    private val string = JSON_Schema.Format.string

    val params = JSON_Schema.Arg.required("params", "", obj)
    val meta = JSON_Schema.Arg.default("_meta", "", obj, JSON.Object.empty)
    val progress_token = JSON_Schema.Arg.optional("progressToken", "", JSON_RPC.Id.format)
    val capabilities = JSON_Schema.Arg.required("capabilities", "", obj)
    val client_info = JSON_Schema.Arg.required("clientInfo", "", obj)
    val name = JSON_Schema.Arg.required("name", "", string)
    val version = JSON_Schema.Arg.required("version", "", string)
    val protocol_version = JSON_Schema.Arg.required("protocolVersion", "", string)
    val request_id = JSON_Schema.Arg.required("requestId", "", JSON_RPC.Id.format)
    val reason = JSON_Schema.Arg.optional("reason", "", string)
    val arguments = JSON_Schema.Arg.default("arguments", "", obj, JSON.Object.empty)
  }


  /* initialize */

  def read_initialize(request: JSON.Object.T): String =
    Fields.params.within(request) { params =>
      Fields.capabilities.get(params)
      Fields.client_info.within(params) { info => Fields.name.get(info); Fields.version.get(info) }
      Fields.protocol_version.get(params)
    }

  // MCP negotiation: answer with client version if supported;
  // otherwise return the server's supported version
  // TODO: do we support other versions?
  def decide_protocol_version(client_version: String): String =
    Config.protocol_version


  /* tool calls */

  type Progress_Token = JSON_RPC.Id

  sealed case class Tool_Call(
    name: String,
    args: JSON.Object.T,
    progress_token: Option[Progress_Token]
  )

  private def read_progress_token(params: JSON.Object.T): Option[Progress_Token] =
    Fields.meta.within(params)(Fields.progress_token.get)

  def read_tool_call(request: JSON.Object.T): Tool_Call =
    Fields.params.within(request) { params =>
      Tool_Call(Fields.name.get(params), Fields.arguments.get(params), read_progress_token(params))
    }

  def progress_notification(
    token: Progress_Token,
    serial: Long,
    message: String
  ): JSON.Object.T =
    JSON_RPC.notification("notifications/progress", JSON_Object(
      Fields.progress_token.name -> token, "progress" -> serial, "message" -> message))

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


  /* cancellation */

  sealed case class Cancellation(id: JSON_RPC.Id, reason: Option[String])

  def read_cancellation(request: JSON.Object.T): Cancellation =
    Fields.params.within(request) { params =>
      Cancellation(Fields.request_id.get(params), Fields.reason.get(params))
    }
}
