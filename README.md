## Isabelle PIDE MCP Server

A Model Context Protocol (MCP) server that provides interactive tools for working with Isabelle theories via a headless PIDE session.

### Installation

1. Install the component:
```bash
isabelle components -u /path/to/pide_mcp
```

### Running

To start the MCP server manually (e.g., for testing over stdin/stdout):

```bash
isabelle pide_mcp -l HOL
```

Or configure it in your MCP client (e.g., OpenCode, Claude Desktop, Zed, Cursor) by providing:
- command: `/path/to/isabelle`
- args: `["pide_mcp", "-l", "HOL"]` (or whichever session you require)

Options:
- `-l NAME` — logic session name (required, e.g., HOL, HOL-Analysis, ZF)
- `-d DIR` — include session directory
- `-o OPTION` — override Isabelle system option (via NAME=VAL or NAME)
- `-v` — verbose output on stderr

### Config
See the example config and skill file in `.opencode`
