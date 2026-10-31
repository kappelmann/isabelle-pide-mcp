/*  Title:      MCP/src/json_schema.scala
    Author:     Kevin Kappelmann

JSON Schema with typed formats.
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.immutable.VectorMap

object JSON_Schema {
  object Format {
    def apply[A](
      schema: JSON.Object.T,
      unapply_body: JSON.T => Option[A],
      apply_body: A => JSON.T
    ): Format[A] =
      new Format[A](schema) {
        def unapply(json: JSON.T): Option[A] = unapply_body(json)
        def apply(value: A): JSON.T = apply_body(value)
      }

    val string: Format[String] = Format(JSON_Object("type" -> "string"),
      JSON.Value.String.unapply, identity[String])
    val bool: Format[Boolean] = Format(JSON_Object("type" -> "boolean"),
      JSON.Value.Boolean.unapply, identity[Boolean])
    val obj: Format[JSON.Object.T] = Format(JSON_Object("type" -> "object"),
      JSON.Object.unapply, identity[JSON.Object.T])

    def enumeration[A](values: Iterable[A], name: A => String = (a: A) => a.toString): Format[A] =
      Format(JSON_Object("type" -> "string", "enum" -> values.map(name).toList),
        json => JSON.Value.String.unapply(json).flatMap(s => values.find(name(_) == s)), name)
    def int(
      minimum: Option[Int] = None,
      maximum: Option[Int] = None
    ): Format[Int] = {
      val unapply = (json: JSON.T) => JSON.Value.Int.unapply(json).filter(value =>
        minimum.forall(_ <= value) && maximum.forall(value <= _))
      Format(JSON_Object("type" -> "integer") ++
          JSON.optional("minimum" -> minimum) ++ JSON.optional("maximum" -> maximum),
        unapply, identity[Int])
    }

    def list[A](format: Format[A]): Format[List[A]] =
      Format(JSON_Object("type" -> "array", "items" -> format.schema),
        JSON.Value.List.unapply(_, format.unapply), _.map(format.apply))

    def dictionary[A](format: Format[A]): Format[VectorMap[String, A]] = {
      val unapply = (json: JSON.T) => JSON.Object.unapply(json).flatMap { obj =>
        val entries = obj.iterator.map { case (name, value) =>
            format.unapply(value).map(result => name -> result)
          }.toList
        Option.when(entries.forall(_.isDefined))(VectorMap.from(entries.flatten))
      }
      val apply = (map: VectorMap[String, A]) =>
        JSON_Object(map.iterator.map { case (name, value) => name -> format(value) })
      Format(JSON_Object("type" -> "object", "additionalProperties" -> format.schema),
        unapply, apply)
    }
  }

  abstract class Format[A] private(val schema: JSON.Object.T) {
    def apply(value: A): JSON.T
    def unapply(json: JSON.T): Option[A]
  }

  object Arg {
    private def schema[A](
      format: Format[A],
      description: String,
      default: Option[A]
    ): JSON.Object.T =
      format.schema ++ JSON.optional("description" -> proper_string(description)) ++
      JSON.optional("default" -> default.map(format.apply))

    private def decode[A](name: String, format: Format[A])(json: JSON.T): A =
      format.unapply(json).getOrElse(
        throw new Arg_Error(List(name), "malformed " + JSON.Format(json)))

    private def missing(name: String): Nothing = throw new Arg_Error(List(name), "missing")

    def required[A](name: String, description: String, format: Format[A]): Arg[A] =
      new Arg(name, schema(format, description, None), required = true,
        obj => obj.get(name).fold(missing(name))(decode(name, format)))

    def optional[A](name: String, description: String, format: Format[A]): Arg[Option[A]] =
      new Arg(name, schema(format, description, None), required = false,
        obj => obj.get(name).map(decode(name, format)))

    def default[A](name: String, description: String, format: Format[A], default: A): Arg[A] =
      new Arg(name, schema(format, description, Some(default)), required = false,
        obj => obj.get(name).fold(default)(decode(name, format)))
  }

  final class Arg[A] private(
    val name: String,
    val schema: JSON.Object.T,
    val required: Boolean,
    read_value: JSON.Object.T => A
  ) {
    def get(obj: JSON.Object.T): A = read_value(obj)

    def within[B](obj: JSON.Object.T)(body: A => B): B = {
      val value = get(obj)
      try body(value) catch { case exn: Arg_Error => throw exn.in(name) }
    }
  }

  final class Arg_Error private[JSON_Schema](path: List[String], reason: String)
    extends Exn.User_Error(quote(path.mkString(".")) + ": " + reason) {
    def in(name: String): Arg_Error = new Arg_Error(name :: path, reason)
  }

  def object_schema(args: List[Arg[?]]): JSON.Object.T =
    JSON_Object("type" -> "object",
      "properties" -> JSON_Object(args.map(arg => arg.name -> arg.schema))) ++
    JSON.optional("required" -> proper_list(args.filter(_.required).map(_.name)))
}
