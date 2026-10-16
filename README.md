# MeGam

## REA (Reverse Engineer Anything) for Claude Code

This repository registers [REA](https://github.com/morluto/rea) for Claude Code
at project scope:

- `.mcp.json` registers the `rea` MCP server (`npx -y rea-agents@6.5.0 mcp`).
- `.claude/settings.json` pre-approves that server for this project.
- `.claude/skills/reverse-engineer-anything/` is REA's bundled workflow skill
  (copied from `rea-agents@6.5.0`).

Requirements: Node.js 22.x (>=22.19), 24.x (>=24.11) or 26+, plus npm. On
Linux, a procps-compatible `ps` is needed for JavaScript/Electron, Ghidra and
JADX analysis. Native binary decompilation needs Hopper, Ghidra or IDA.

Restart Claude Code in this directory, then check with `claude mcp list`.

To update, change the version in `.mcp.json` and refresh the skill from the
same `rea-agents` release.
