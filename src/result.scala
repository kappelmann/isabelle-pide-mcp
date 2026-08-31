/*  Title:      PIDE_MCP/result.scala
    Author:     Kevin Kappelmann

Results or expected errors
*/

package isabelle.pide.mcp

enum Result[+A, +E] {
  case Res(value: A) extends Result[A, Nothing]
  case Error(error: E) extends Result[Nothing, E]
}

object Result {
  def release[A](result: Result[A, Throwable]): A =
    result match {
      case Res(value) => value
      case Error(exn) => throw exn
    }
}
