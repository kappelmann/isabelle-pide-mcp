/*  Title:      MCP/src/pide_mcp_service.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

object PIDE_MCP_Service {
  val kind: String = "service"

  val max_name_length: Int = 128
  def valid_name(name: String): Boolean =
    name.nonEmpty && name.length <= max_name_length &&
    name.forall(c => Symbol.is_ascii_letter(c) || Symbol.is_ascii_digit(c) || "_-.".contains(c))

  val all_option: String = "*"

  private[mcp] def make_table[A <: PIDE_MCP_Service](
    kind: String,
    entries: List[A],
    include_names: String,
    exclude_names: String
  ): Map[String, A] = {
    val table = entries.foldLeft(Map.empty[String, A]) {
      case (table, entry) =>
        if (table.isDefinedAt(entry.name))
          error(s"Duplicate PIDE_MCP $kind: ${Library.quote(entry.name)}")
        else table + (entry.name -> entry)
    }
    def select(names: String): Set[String] =
      if (names == all_option) table.keySet
      else {
        val list = space_explode(',', names)
        val unknown = list.filterNot(table.isDefinedAt)
        if (unknown.nonEmpty)
          error(s"Unknown PIDE_MCP $kind(s): ${commas_quote(unknown)}. " +
            s"Available $kind(s): ${commas_quote(table.keys.toList.sorted)}")
        list.toSet
      }
    val included = select(include_names)
    val excluded = select(exclude_names)
    table.filter { case (name, _) => included(name) && !excluded(name) }
  }

  def make_table(
    include_names: String = all_option,
    exclude_names: String = ""
  ): Map[String, PIDE_MCP_Service] =
    make_table(kind,
      Isabelle_System.make_services(classOf[PIDE_MCP_Services]).flatMap(_.entries),
      include_names, exclude_names)
}

abstract class PIDE_MCP_Service(val name: String) {
  def kind: String = PIDE_MCP_Service.kind

  if (!PIDE_MCP_Service.valid_name(name)) error(s"Bad PIDE_MCP $kind name: ${quote(name)}")

  // called after each session start
  def start(
    sessions: PIDE_MCP_Sessions,
    session: PIDE_MCP_Session,
    progress: Progress
  ): Unit = ()
  // called before each session stop (normal) or after (abrupt stop)
  // note: the service's start might not have been finished or called yet
  def stop(
    sessions: PIDE_MCP_Sessions,
    session: PIDE_MCP_Session,
    progress: Progress
  ): Unit = ()
}

class PIDE_MCP_Services(services: PIDE_MCP_Service*) extends Isabelle_System.Service {
  def entries: List[PIDE_MCP_Service] = services.toList
}
