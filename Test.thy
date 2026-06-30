theory Test
  imports Main
begin


datatype 'a tree =
  Leaf |
  Node 'a "'a tree" "'a tree"

fun mirror :: "'a tree \<Rightarrow> 'a tree" where
  "mirror Leaf = Leaf" |
  "mirror (Node x l r) = Node x (mirror r) (mirror l)"

lemma mirror_mirror [simp]: "mirror (mirror t) = t"
  by (induct t) auto



end