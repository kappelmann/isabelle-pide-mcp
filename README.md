# Isabelle PIDE MCP Server

A Model Context Protocol (MCP) server that provides interactive tools for working with Isabelle theories and ML files via a headless Isabelle/PIDE session.

**Note:** The MCP manages its own PIDE state. If you edit the same files as the MCP, it will only see your changes once they are written to disk.
The MCP automatically synchronizes with disk on every read and write.
Vice versa, if the agent edits a file, you may need to manually reload the file to see the changes in case your editor does not support auto-reload.

## Installation

1. Install the supported Isabelle version. The supported version is stored in [ISABELLE\_VERSION](./ISABELLE_VERSION):
```bash
hg clone https://isabelle.in.tum.de/repos/isabelle
isabelle/Admin/init -r <VERSION_NUMBER>
```
2. Install the component:
```bash
isabelle/bin/isabelle components -u /path/to/this/repository
```

## Running

You can register the server to your MCP client (e.g., OpenCode, Claude Code, Codex,...) or start the server manually:

```bash
isabelle/bin/isabelle pide_mcp -l HOL
```

As usual, all options can be seen using `pide_mcp -?` (they follow the typical Isabelle conventions, e.g. `-d`, `-v`, `-L`).

### Coding Agents Configuration

- For OpenCode, copy/adapt folder `.opencode` and start OpenCode in the same base directory.
- For Claude Code, copy/adapt `.claude` and `.mcp.json` and start Claude Code in the same base directory. 

## Note

- If you want to the agent to see proof states for base sessions, you have to build them with `-o show_states`

# TODOs
- options to warn agent about timeouts? everything is async for now
- timeouts for base sessions are currently returned as 0 (because they are not stored in the base session's markup)
