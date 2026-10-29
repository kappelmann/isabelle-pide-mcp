/*  Title:      MCP/src/result.scala
    Author:     Kevin Kappelmann

Results or expected errors
*/

package isabelle.pide.mcp

import isabelle._

enum Result[+A, +E] {
  case Res(value: A) extends Result[A, Nothing]
  case Error(error: E) extends Result[Nothing, E]

  def map_res[B](f: A => B): Result[B, E] =
    this match {
      case Res(value) => Res(f(value))
      case Error(error) => Error(error)
    }
}

object Result {
  def result[A](body: => A): Result[A, Throwable] =
    Exn.result(body) match {
      case Exn.Res(value) => Res(value)
      case Exn.Exn(exn) => Error(exn)
    }

  def release[A](result: Result[A, Throwable]): A =
    result match {
      case Res(value) => value
      case Error(exn) => throw exn
    }
}
