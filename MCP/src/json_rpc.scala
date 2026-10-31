/*  Title:      MCP/src/json_rpc.scala
    Author:     Kevin Kappelmann

JSON-RPC 2.0 messages.
*/

package isabelle.pide.mcp

import isabelle._

object JSON_RPC {
  val version = "2.0"

  type Id = String | Long
  object Id {
    def unapply(value: JSON.T): Option[Id] =
      value match {
        case string: String => Some(string)
        case JSON.Value.Long(long) => Some(long)
        case _ => None
      }
    val format: JSON_Schema.Format[Id] = JSON_Schema.Format(
      JSON_Object("type" -> List("string", "integer")), unapply, identity[Id])
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
    JSON_Object("jsonrpc" -> version, "id" -> id, "result" -> result)

  def error(id: Id | Null, code: Int, message: String): JSON.Object.T =
    JSON_Object("jsonrpc" -> version, "id" -> id,
      "error" -> JSON_Object("code" -> code, "message" -> message))

  def notification(method: String, params: JSON.Object.T): JSON.Object.T =
    JSON_Object("jsonrpc" -> version, "method" -> method, "params" -> params)
}
