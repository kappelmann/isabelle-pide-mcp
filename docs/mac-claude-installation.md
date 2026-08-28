# Installing isabelle-pide-mcp on a mac and setting it up for use with Claude

## Preamble

While a sequence of instructions will often suffice for an installation, an introduction to what's happening may help you figure out how to fix things when they go wrong. 

The `isabelle-pide-mcp` project (which I'll call `pide-mcp` from now on) combines instructions you give to some AI agent (I'll use Claude as a running example) and Isabelle itself to construct proof documents. There are apparently many ways to use it, but I'll illustrate with what I think of as a "beginner mode": you have the standard `isabelle/jEdit` interface (which I'll call just `jEdit` from now on) running, and in a terminal alongside it, you have Claude running. By setting things up correctly, the two of them are both working on a single file (I'll use `Scratch.thy` , which we'll create after the installation, as an example). When Claude changes this file, `jEdit` will attempt to reload it with the changes (assuming that the default auto-reload-on-disk-changes option is selected within `jEdit`). 

Unfortunately, if you've *also* made changes via `jEdit`, you'll have to do some merging work, so you'll typically avoid this. 

When you change the file in `jEdit`, you must *save* the changes for Claude to know something's happened. Thus a typical workflow is typing some things in `jEdit` and saving, then asking Claude to, for example, "fill in a proof for that first 'sorry' in the document". Then Claude works for a moment and announces that it's done, and either `jEdit` automatically re-loads (the usual sequence), or, if something's a little bit out of sync, you may need to press F5 (reload) in `jEdit` to see the changes. 

A possibly more sophisticated use is to say to Claude "read this preprint, formalize all the theorems and proofs in it, placing them in a document `preprint.thy`"; when Claude finishes, you start up `jEdit` to look at the results, and possibly polish them a bit. 

You can see from the description above that (1) Claude needs to know what it can do to an Isabelle theory file, and (2) `jEdit` needs to keep track of changes in the file for auto-reloading, and (3) For a particular instance of Claude and `jEdit`, and a particular file, some awareness of the connection of the processes is needed. Things are actually a little more complicated: to do its work, the Claude side of the project needs to be able to try out the Isabelle stuff it's writing, and to do this, it runs its own (invisible) copy of `isabelle/pide` (the Isabelle "Prover IDE", which is roughly the "non-UI part" of what you get when you use `isabelle/jEdit`, and is documented in the isabelle/jEdit manual). This copy and the user's copy of the current theory document need to be synchronized for everything to work right. 

Just for completeness, the `mcp` part of `pide-mcp` refers to a Model Context Protocol, which is a tool used to give Claude Code access to tools, databases, and APIs. Very briefly, if you've ever found yourself copying output from some tool and pasting it into Claude, and then copying Claude's response back into the tool…that's what `mcp` helps to avoid. For an `mcp` to work, it needs to know both the Claude instance from which it's called and the target tool (in our case Isabelle/PIDE) that's being used. 

This repository, which you'll download to start working with Claude and Isabelle, contains an MCP server, i.e., the tool that lets Claude (or some other AI Agent) "interactively work with Isabelle theories and ML files via an Isabelle/PIDE session."

## Step-by-step instructions, with additional commentary

Broadly speaking, you're going to make certain that you have the right version of Isabelle installed, then clone this github repository, and then edit a few of the cloned files to "glue things together." You'll also very likely want to build a "HOL session" so that you can use Isabelle/HOL in your theory documents rather than raw Isabelle. 

"The right version of Isabelle" is a tricky thing. There are periodic Isabelle releases with names like `Isabelle2024-1` consisting of a year and a release number. There are also development versions, and if you're the sort of person who uses those, you are probably not someone who needs these additional notes, and the main Installation notes in the README will suffice for you. I'm therefore going to continue with instructions for folks who work with named releases. As of August 2026, the release-version with which `pide-mcp` is compatible is `Isabelle2025-2`. I'll use that in the details below as a proxy for whatever is the current version that *you* are working with. Note: if you already have "the right version" installed, you can skip part 1 (Download Isabelle) and go directly to part 2 (Build HOL), which will probably run very quickly because you're likely to have already done it, in which case it takes only a few seconds.

1. ### Download Isabelle

   1. Download the appropriate release of Isabelle from [https://isabelle.in.tum.de/](https://isabelle.in.tum.de/) or [https://www.cl.cam.ac.uk/research/hvg/Isabelle/](https://www.cl.cam.ac.uk/research/hvg/Isabelle/) or whatever mirror site is best for you. The result will be a gzipped file with a name like `Isabelle2025-2_macos.tar.gz`.   
   2. Drag this file to your Desktop  
   3. Double-click on the resulting Desktop file to unzip the file and produce a folder with a name like `Isabelle2025-2` (which is really `Isabelle2025-2.app`, but the extension is hidden by MacOS, and the folder is displayed as if it were a single file).  
   4. Move the \`Isabelle2025-2\_macois[.tar.gz](http://.tar.gz) file to the trash  
   5. Drag your new `Isabelle2025-2` to the `Applications` folder. 

We now (in version of Mac after Tahoe 26.5.2, and probably earlier) must let MacOS know that this new application is safe to use. Quoting the Isabelle Installation page section on MacOS:

* **Open** Isabelle2025-2.app and **Cancel** the subsequent security dialog.  
* **Open Security & Privacy** in system preferences: section *"Allow apps ..."* at the bottom should list the blocked application (see [screenshot](https://www.cl.cam.ac.uk/research/hvg/Isabelle/img/macos_security.png)).  
* Click **Open Anyway** and provide further confirmations as required.

Following this, Isabelle should work fine. 

2. ### (Optional, but you almost certainly want it) Build HOL

   1. Assuming that as advised, you placed the new Isabelle in your Applications folder, open a Terminal window and type `/Applications/Isabelle2025-2.app/bin/isabelle build -vb HOL`, which may run for a while (a minute or two, perhaps?) while assembling all the theories that make up HOL.   
   2. This will build a HOL "session" so that you can use HOL within Isabelle without rebuilding every single theory every single time you do anything. 

Now we can move on to install the `isabelle-pide-mcp` project to work with this new Isabelle. 

3. ### Install the `pide-mcp` project from github

You'll want to pick a place to download the project. I used the Desktop as a convenient location, so in a Terminal, I typed:

```
% cd ~/Desktop
% git clone https://github.com/kappelmann/isabelle-pide-mcp.git
% cd isabelle-pide-mcp
```

This produces a directory `/Users/jhughes/Desktop/isabelle-pide-mcp` (where `jhughes` is replaced by *your* MacOS login) containing 7 visible entries:

```
CONTRIBUTING.md		
ISABELLE_VERSION
README.md
docs
LICENSE.md
src
etc
NEWS.md
```

Unfortunately,  the things that most matter for installation are all in "dot files" \-- files or folders with names starting with a dot (".") which are not normally listed. We'll soon edit several of these.

Also unfortunately, the precise location of this `isabelle-pide-mcp` directory matters. In a little while, we're going to tell Isabelle itself where to find this directory, and once we do so, the directory must not be moved someplace else. Personally, I'm happy having it on my Desktop for now, but you may want to make a different choice. 

Now for the detailed steps.

1. The `pide-mcp` project contains several "branches" (variants with different purposes). You need to select the one matching your Isabelle version (`Isabelle2025-2` in our continuing example). In your terminal, type

```
% git checkout Isabelle2025-2
```

which should generate a response similar to this:

```
% git checkout Isabelle2025-2
branch 'Isabelle2025-2' set up to track 'origin/Isabelle2025-2'.
Switched to a new branch 'Isabelle2025-2'
```

If you get some sort of error, make sure that version of Isabelle you selected is the correct one, according to [`https://github.com/kappelmann/isabelle-pide-mcp`](https://github.com/kappelmann/isabelle-pide-mcp), and that you typed it correctly. 

2. Again assuming you're using Claude, you need to edit a file that tells Claude where to find the Isabelle binaries, etc. \[The project README file also contains very terse instructions for how to set things up for OpenCode and Codex, but I have not tried any of those, so the remaining instructions are for Claude only. They may, perhaps, help in a kind of generic way with the other two cases as well, however.\] 

Using whatever text-editing tool you prefer, edit the file `.mcp.json` in the `isabelle-pide-mcp` directory. The initial contents should look something like this:

```
{
  "mcpServers": {
    "isabelle-pide-mcp": {
      "command": "/Users/kevin/Programming/isabelle/isabelle/bin/isabelle",
      "args": ["pide_mcp", "-v", "-l", "HOL"]
    }
  }
}
```

The `"command"` line in this file needs to be changed to point to the `isabelle` binary in the place you installed Isabelle. In my case, that's `/Applications/Isabelle2025-2.app/bin/isabelle`  
This should work for you as well, assuming that you've installed things as described above. If you chose to put Isabelle somewhere other than your Applications folder, you'll need to edit the path above to reflect that, adding `/bin/isabelle` at the end.	  
The resulting file should look like this:

```
{
  "mcpServers": {
    "isabelle-pide-mcp": {
      "command": "/Applications/Isabelle2025-2.app/bin/isabelle",
      "args": ["pide_mcp", "-v", "-l", "HOL"]
    }
  }
}
```

If you leave off the trailing comma on the `"command"` line, it'll generate an error in the next step. If you use smart quotes it'll do the same. Make sure it really looks like what's shown above. 

At this point, `pide-mcp` has the information it needs to find Isabelle. The next step will provide Isabelle with the information it needs to find `pide-mcp`. 

3. Add the directory containing `pide-mcp` to Isabelle's list of 'components' by typing

```
% /Applications/Isabelle2025-2.app/bin/isabelle components -u .
```

which should generate a response similar to this:

```
Added component "/Users/jhughes/Desktop/isabelle-pide-mcp"
```

### 3\. Try it out (very limited test)

To see whether everything got set up right, you can (still in the same directory\!) type the following:

```
% /Applications/Isabelle2025-2.app/bin/isabelle pide_mcp

```

which should produce a response something like this:

```
### Building Isabelle PIDE MCP (/Users/jhughes/Desktop/isabelle-pide-mcp/lib/isabelle_pide_mcp.jar) ...
Starting Isabelle PIDE session...
Session started. Now starting MCP server listening on stdin/stdout.

```

Assuming that worked correctly, you can type ctrl-C to exit and feel gratified that everything seems to be doing what it should. 

### 4\. Try it out (a slightly larger-scale test)

In Terminal, change directory to the Desktop, and create another folder that we'll use for a slightly larger test, one that's a model for how you'll likely use this MCP tool in the future:

```
% cd ~/Desktop
% mkdir pide-mcp-example-project
% cd pide-mcp-example-project
```

To use `pide-mcp` in this directory, you'll need to copy (1) the `.mcp-json` file, and (2) the whole `.claude` subdirectory from the original project to here:

```
% cp ../isabelle-pide-mcp/.mcp.json .  ### use YOUR pide-mcp location
% cp -r ../isabelle-pide-mcp/.claude . ### same here
% /Applications/Isabelle2025-2.app/Isabelle2025-2 &  ### Runs jEdit

```

NB: the dot at the end of each `cp` command is important\! 

Now run Claude:

```
% claude # runs claude
```

Claude may now ask you if you trust this directory (answer "yes"), and then await a prompt. You should then type

```
/mcp
```

and should get a response that looks something like this:

```
 Manage MCP servers
   6 servers

     Project MCPs (/Users/jhughes/Desktop/pide-mcp-example-project/.mcp.json)
   ❯ isabelle-pide-mcp · ✔ connected · 10 tools

     claude.ai
     claude.ai Gmail · △ needs authentication
     claude.ai Google Calendar · △ needs authentication
     claude.ai Google Drive · △ needs authentication

     Built-in MCPs (always available)
     claude-in-chrome · ✔ connected · 22 tools
     computer-use · ◯ disabled

   https://code.claude.com/docs/en/mcp for help

```

The key thing here is that first item just under "Project MCPs": it says you're connected to isabelle. You should (as directed) hit Enter to confirm.

At this point, you should see "View Tools", "Disconnect", and "Disable" as three options. I recommend selecting the first and navigating around a little bit to see what's available to you. Personally, I've never used any of the available tools, and instead work directly with plain language instructions to Claude  
Press ESC several times to get back to a Claude prompt. Then type

```
❯ create a scratch theory file in the pied-mcp-example-project directory, with the name Scratch.thy, and which imports Main.
```

and Claude will take a few moments and create this file.  
Now in `jEdit`, close any theory files that happen to auto-open, and then use the FileBrowser panel at the top left to navigate to the pied-mcp-example-project directory, where you should see Scratch.thy available to open. Double-click to open it. The contents should look like this:

```
theory Scratch
  imports Main
begin

end

```

As an alternative to the Claude prompt that generated your file, you could have (in `jEdit`) navigated to this directory, created a new theory with these contents, and saved the theory document with the name `Scratch.thy`. In either case, you'd be at the same state: a theory file available to both Claude and `jEdit.`

Now in `jEdit`, modify the file so that it looks like this:

```
theory Scratch
 imports Main 
begin

lemma "(1::nat) + 3 = 4"
sorry

end
```

and then save the file. Return to Claude and type

```
> fill in a proof for the first sorry
```

(If you created the file yourself in jEdit, you may need to say `fill in the proof for the first sorry in the file Scratch.thy`). Claude will do a little work and report success. Return to `jEdit` and you will see the new proof (`by simp`) has been edited into your file. (If you don't see this, try reloading the file, typically by pressing F5.)

Congratulations\! You've managed to see how Claude and `jEdit` can collaboratively edit a file, and Claude can write proofs in the file. 