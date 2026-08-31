/*  Title:      PIDE_MCP/pide_mcp_json.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.collection.immutable.VectorMap

object JSON_Object {
  // stable order of entries
  def apply(entries: JSON.Object.Entry*): JSON.Object.T = VectorMap.from(entries)
  def apply(entries: IterableOnce[JSON.Object.Entry]): JSON.Object.T = VectorMap.from(entries)

  def flatten(entries: Option[JSON.Object.Entry]*): JSON.Object.T = flatten(entries)
  def flatten(entries: IterableOnce[Option[JSON.Object.Entry]]): JSON.Object.T =
    apply(entries.iterator.flatten)

  def if_proper(b: Boolean, body: => JSON.Object.T): JSON.Object.T =
    if (b) body else JSON.Object.empty
}

object PIDE_MCP_JSON {
  private def field_text(name: String, where: Option[String]): String =
    quote(name) + where.map(context => " in " + quote(context)).getOrElse("")

  def value[A](
    obj: JSON.Object.T,
    name: String,
    unapply: JSON.T => Option[A],
    where: Option[String] = None
  ): A = obj.get(name) match {
    case None => error("Missing " + field_text(name, where))
    case Some(json) => unapply(json).getOrElse(
      error("Malformed " + field_text(name, where) + ": " + JSON.Format(json)))
  }

  def value_default[A](
    obj: JSON.Object.T,
    name: String,
    unapply: JSON.T => Option[A],
    default: => A,
    where: Option[String] = None
  ): A = obj.get(name) match {
    case None => default
    case Some(json) => unapply(json).getOrElse(
      error("Malformed " + field_text(name, where) + ": " + JSON.Format(json)))
  }

  def opt_value[A](
    obj: JSON.Object.T,
    name: String,
    unapply: JSON.T => Option[A],
    where: Option[String] = None
  ): Option[A] =
    value_default(obj, name, json => unapply(json).map(Some(_)), None, where)

  def obj(obj: JSON.Object.T, name: String, where: Option[String] = None): JSON.Object.T =
    value(obj, name, JSON.Object.unapply, where)

  def obj_default(
    obj: JSON.Object.T,
    name: String,
    default: => JSON.Object.T,
    where: Option[String] = None
  ): JSON.Object.T =
    value_default(obj, name, JSON.Object.unapply, default, where)

  def string(obj: JSON.Object.T, name: String, where: Option[String] = None): String =
    value(obj, name, JSON.Value.String.unapply, where)

  def opt_string(
    obj: JSON.Object.T,
    name: String,
    where: Option[String] = None
  ): Option[String] =
    opt_value(obj, name, JSON.Value.String.unapply, where)

  def from_xml(tree: XML.Tree): JSON.Object.T =
    tree match {
      case XML.Elem(Markup(name, props), body) =>
        val props_obj = JSON_Object(props)
        val base = JSON_Object("name" -> name, "body" -> body.map(from_xml))
        if (props_obj.isEmpty) base else base + ("props" -> props_obj)
      case XML.Text(text) => JSON_Object("text" -> text)
    }
}
