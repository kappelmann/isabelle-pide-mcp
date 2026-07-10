/*  Title:      PIDE_MCP/pide_mcp_name_space.scala
    Author:     Kevin Kappelmann

Backport of isabelle.Name_Space for Isabelle2025-2.
*/

package isabelle.pide.mcp

import isabelle._

object Name_Space {
  sealed case class Entry(properties: Properties.T) {
    def name: String = Markup.Name.get(properties)
    def kind: String = Markup.Kind.get(properties)
    def def_label: String = Position.Def_Label.get(properties)
  }

  object Entity {
    def unapply(markup: Markup): Option[Entry] =
      markup match {
        case Markup(Markup.ENTITY, props) => Some(Entry(props))
        case _ => None
      }
  }
}
