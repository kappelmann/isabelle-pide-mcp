/*  Title:      MCP/src/pide_mcp_dependencies.scala
    Author:     Kevin Kappelmann
*/

package isabelle.pide.mcp

import isabelle._

import scala.annotation.tailrec

object PIDE_MCP_Dependencies {
  def blobs_names(node: Document.Node): List[Document.Node.Name] =
    node.load_commands.flatMap(_.blobs_names)

  def known_dependencies(
    resources: Resources,
    nodes: Document.Nodes,
    names: List[Document.Node.Name]
  ): List[Document.Node.Name] = {
    @tailrec def collect(
      seen: Set[Document.Node.Name],
      todo: List[Document.Node.Name]
    ): Set[Document.Node.Name] =
      todo match {
        case Nil => seen
        case name :: rest if seen(name) => collect(seen, rest)
        case name :: rest =>
          val node = nodes(name)
          collect(seen + name, node.thy.imports_no_pos ::: blobs_names(node) :::
            resources.make_theory_name(name).toList ::: rest)
      }
    collect(Set.empty, names).toList
  }

  def disk_dependencies(
    session: Headless.Session,
    names: List[Document.Node.Name],
    progress: Progress
  ): List[Document.Node.Name] = {
    val deps = session.resources.dependencies(session.conditions,
      names.filter(_.is_theory).map((_, Position.none)), progress = progress)
    val files = try deps.loaded_files catch { case ERROR(_) => Nil }
    (names ::: deps.theories ::: files).distinct
  }

  def unloaded_dependencies(
    session: Headless.Session,
    nodes: Document.Nodes,
    origins: List[Document.Node.Name],
    exclude: Set[Document.Node.Name],
    progress: Progress
  ): List[Document.Node.Name] = {
    val resources = session.resources
    def is_unloaded(name: Document.Node.Name): Boolean =
      !exclude(name) && !resources.loaded_theory(name) &&
      !PIDE_MCP_Util.is_loaded_dynamic(nodes(name)) &&
      (name.path.is_file || resources.make_theory_content(name).isDefined)
    val unloaded = known_dependencies(resources, nodes, origins).filter(is_unloaded)
    disk_dependencies(session, unloaded, progress).filter(is_unloaded)
  }

  def loaded_dependents(
    nodes: Document.Nodes,
    names: List[Document.Node.Name]
  ): List[Document.Node.Name] =
    nodes.descendants(names).filter(name => PIDE_MCP_Util.is_loaded_dynamic(nodes(name)))

  def unused_files(
    resources: Resources,
    nodes: Document.Nodes,
    removed: List[Document.Node.Name]
  ): List[Document.Node.Name] = {
    def is_loaded(name: Document.Node.Name) = PIDE_MCP_Util.is_loaded_dynamic(nodes(name))
    val blobs = removed.flatMap(name => blobs_names(nodes(name))).distinct.filter(is_loaded)
      .map(blob_name => blob_name -> resources.make_theory_name(blob_name).filter(is_loaded))
    val removed1 = removed.toSet ++ blobs.flatMap(_._2)
    for {
      (blob_name, opt_thy) <- blobs
      if nodes.commands_loading(blob_name).forall(cmd => removed1(cmd.node_name))
      name <- blob_name :: opt_thy.toList
    } yield name
  }

  def removable_names(
    resources: Resources,
    nodes: Document.Nodes,
    names: List[Document.Node.Name]
  ): List[Document.Node.Name] = {
    val dependents = loaded_dependents(nodes, names)
    dependents ::: unused_files(resources, nodes, dependents)
  }
}
