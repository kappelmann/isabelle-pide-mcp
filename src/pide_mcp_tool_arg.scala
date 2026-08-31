/*  Title:      PIDE_MCP/pide_mcp_tool_arg.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.immutable.VectorMap

object PIDE_MCP_Tool_Arg {
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

  def make_schema[A](
    format: Format[A],
    description: String,
    default: Option[A]
  ): JSON.Object.T = format.schema + ("description" -> description) ++
    JSON.optional("default" -> default.map(format.apply))

  def required[A](
    name: String,
    description: String,
    format: Format[A]
  ): PIDE_MCP_Tool_Arg[A] = new PIDE_MCP_Tool_Arg(name, 
    make_schema(format, description, None), required = true,
    args => PIDE_MCP_JSON.value(args, name, format.unapply))

  def optional[A](
    name: String,
    description: String,
    format: Format[A],
    schema_default: Option[A] = None
  ): PIDE_MCP_Tool_Arg[Option[A]] = new PIDE_MCP_Tool_Arg(name, 
    make_schema(format, description, schema_default), required = false,
    args => PIDE_MCP_JSON.opt_value(args, name, format.unapply))

  def default[A](
    name: String,
    description: String,
    format: Format[A],
    default: A
  ): PIDE_MCP_Tool_Arg[A] = new PIDE_MCP_Tool_Arg(name, 
    make_schema(format, description, Some(default)), required = false,
    args => PIDE_MCP_JSON.value_default(args, name, format.unapply, default))

  def string(name: String, description: String): PIDE_MCP_Tool_Arg[String] =
    required(name, description, Format.string)

  def int(
    name: String,
    description: String,
    minimum: Option[Int] = None,
    maximum: Option[Int] = None
  ) : PIDE_MCP_Tool_Arg[Int] =
    required(name, description, Format.int(minimum, maximum))

  def strings(name: String, description: String): PIDE_MCP_Tool_Arg[List[String]] =
    required(name, description, Format.list(Format.string))

  def opt_string(name: String, description: String): PIDE_MCP_Tool_Arg[Option[String]] =
    optional(name, description, Format.string)

  def opt_int(
    name: String,
    description: String,
    schema_default: Option[Int] = None,
    minimum: Option[Int] = None,
    maximum: Option[Int] = None
  ): PIDE_MCP_Tool_Arg[Option[Int]] =
    optional(name, description, Format.int(minimum, maximum), schema_default)

  def opt_strings(name: String, description: String): PIDE_MCP_Tool_Arg[Option[List[String]]] =
    optional(name, description, Format.list(Format.string))

  def string_default(name: String, description: String, default: String)
    : PIDE_MCP_Tool_Arg[String] =
    PIDE_MCP_Tool_Arg.default(name, description, Format.string, default)

  def bool_default(name: String, description: String, default: Boolean)
    : PIDE_MCP_Tool_Arg[Boolean] =
    PIDE_MCP_Tool_Arg.default(name, description, Format.bool, default)

  def int_default(
    name: String,
    description: String,
    default: Int,
    minimum: Option[Int] = None,
    maximum: Option[Int] = None
  ): PIDE_MCP_Tool_Arg[Int] =
    PIDE_MCP_Tool_Arg.default(name, description, Format.int(minimum, maximum), default)

  def strings_default(
    name: String,
    description: String,
    default: List[String]
  ): PIDE_MCP_Tool_Arg[List[String]] =
    PIDE_MCP_Tool_Arg.default(name, description, Format.list(Format.string), default)

  def dictionary_default[A](
    name: String,
    description: String,
    format: Format[A],
    default: VectorMap[String, A]
  ): PIDE_MCP_Tool_Arg[VectorMap[String, A]] =
    PIDE_MCP_Tool_Arg.default(name, description, Format.dictionary(format), default)
}

final class PIDE_MCP_Tool_Arg[A] private(
  val name: String,
  val schema: JSON.Object.T,
  val required: Boolean,
  get_value: JSON.Object.T => A
) {
  def get(args: JSON.Object.T): A = get_value(args)
  def entry: JSON.Object.Entry = name -> schema
}
