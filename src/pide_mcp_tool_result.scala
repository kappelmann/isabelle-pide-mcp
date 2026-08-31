/*  Title:      PIDE_MCP/pide_mcp_tool_result.scala
    Author:     Kevin Kappelmann

Results of MCP tool calls.
*/

package isabelle.pide.mcp

import isabelle._

type PIDE_MCP_Tool_Result = Result[JSON.T, JSON.T]

object PIDE_MCP_Tool_Result {
  export Result.{Res, Error}

  def exn_error(exn: Throwable): PIDE_MCP_Tool_Result = Result.Error(Exn.message(exn))

  def result(body: => JSON.T): PIDE_MCP_Tool_Result =
    Exn.result(body) match {
      case Exn.Res(value) => Result.Res(value)
      case Exn.Exn(exn) => exn_error(exn)
    }
}
