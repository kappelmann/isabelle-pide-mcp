# Isabelle PIDE MCP Server

This repository contains:
1. A Model Context Protocol (MCP) server that provides AI agents tools to interactively work with Isabelle theories and ML files via a headless Isabelle/PIDE session.
2. A set of agent skills on how to effectively use the MCP and general guidance for formalization tasks and Isabelle.

## Usage Notes

To get started (see details below), you install the MCP, register it to a coding agent, and then you can start prompting.
To interactively explore the agent's changes, you may also run an Isabelle/jEdit or Isabelle/VSCode session next to the coding agent that uses the MCP (cf. screenshow below).

**Take note of the following when using the MCP:**
- The MCP manages its own PIDE session. In particular, this means that your editor's session and the MCP's sessions are independent of each other.
  For example, commands will be processed by the MCP's session AND the editor's session,
  and Isabelle options passed to the editor session (e.g. included session directories) also have to be passed to the MCP.
- If you edit the same files as the MCP, it will only see your changes once they are written to disk.
  The MCP automatically synchronizes with disk on every read and write.
  Vice versa, if a file is edited via the MCP, you may need to manually reload the file in your editor in case the editor does not auto-reload on disk changes.
  In Isabelle/jEdit, it is sometimes necessary to reload manually (e.g. by using the F5 key). Isabelle/VSCode supports auto-reload. 
- If you want the agent to see proof states in pre-built base sessions, you have to build them with `-o show_states`.

## Installing the MCP Server

1. Install the supported Isabelle version (or newer). The supported version is stored in [ISABELLE\_VERSION](./ISABELLE_VERSION). Insert the version number in the command below:
```bash
hg clone https://isabelle.in.tum.de/repos/isabelle
isabelle/Admin/init -r <VERSION_NUMBER>
```
2. Install the component. Insert the file path to this directory in the command below:
```bash
isabelle/bin/isabelle components -u <PATH_TO_THIS_DIRECTORY>
```

## Running the MCP Server

You can register the server to your MCP client (e.g., OpenCode, Claude Code, Codex,...) or start the server manually:

```bash
isabelle/bin/isabelle pide_mcp -l HOL
```

As usual, all options are displayed using `pide_mcp -?` (they follow the typical Isabelle conventions, e.g. `-d`, `-v`, `-L`).

### Connecting Coding Agents to the MCP Server

- For **OpenCode**, copy/adjust folder `.opencode` and start OpenCode in the same base directory.
  - **You have to adjust the path to isabelle in `.opencode/opencode.json`** and possibly the options you want to pass to the MCP (e.g. included session directories).
- For **Claude Code**, copy/adjust `.claude` and `.mcp.json` and start Claude Code in the same base directory. 
  - **You have to adjust the path to isabelle in `.mcp.json`** and possibly the options you want to pass to the MCP (e.g. included session directories).

## Agent Skills

The `skills` folders (in `.opencode/` and `.claude/`) contain the following guidances for AI agents:
- `isabelle-formalization`: Formalization guidances and best practices.
- `isabelle-proof-development`: Guidance on proof search, automation, and concept search.
- `pide-mcp`: How to use this MCP effectively.
You may adjust these guidances as you wish.

## Known Limitations/Future Work

- Isabelle 2025-2 is not supported. The first stable release is planned for Isabelle 2026.
- Command timings for pre-built sessions are currently returned as 0.
- It would be desirable to have the option to share a PIDE session among the MCP and editors (Isabelle/jEdit, Isabelle/VSCode).
  This requires changes in the Isabelle distribution sources.
- It would be desirable to explore changes with PIDE without altering the affected document's state, 
  cf. this [email by Hanno Becker](https://isabelle.zulipchat.com/#narrow/channel/247541-Mirror.3A-Isabelle-Users-Mailing-List/topic/.5Bisabelle.5D.20Isabelle.2FREPL/near/581059372).
  This requires changes in the Isabelle distribution sources.
- Support of dynamic extensions ("plugins") such that users can modularly add new MCP tools on their own.

## Related Work

[I/Q](https://github.com/awslabs/AutoCorrode/tree/main/iq) is an alternative Isabelle MCP that integrates with Isablle/jEdit (using a shared document state).
PIDE MCP was designed to be a headless (editor-agnostic) and minimalistic alternative to I/Q.
Experience reports using both systems are very welcome: we hope that the strengths of both MCPs can be combined in the future.

## Feedback, Questions, Discussions

Please use the [Isabelle Zulip](TODO link).
Alternatively, contact Kevin Kappelmann by email.

