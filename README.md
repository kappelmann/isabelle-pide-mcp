## Isabelle PIDE MCP Server

A Model Context Protocol (MCP) server that provides interactive tools for working with Isabelle theories and ML files via a headless PIDE session.

### Installation

1. Install the component:
```bash
isabelle components -u /path/to/pide_mcp
```

### Running

You can register the server to your MCP client (e.g., OpenCode, Claude Code, Codex,...) or start the server manually:

```bash
isabelle pide_mcp -l HOL
```

As usual, all options can be seen using `isabelle pide_mcp -?` (they follow the typical Isabelle conventions, e.g. `-d`, `-v`, `-L`).

### Coding Agents Configuration

- For OpenCode, copy/adapt folder `.opencode` and start OpenCode in the same base directory.
- For Claude Code, copy/adapt `.claude` and `.mcp.json` and start Claude Code in the same base directory. 

# TODOs
- options to warn agent about timeouts? everything is async for now
