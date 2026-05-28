---
name: example
description: Demonstration skill that proves the engine can load, list, and read bundled skill content. Use only when the user explicitly asks to test the skills engine.
metadata:
  author: kermes-bootstrap
  version: "0.1"
---

# Example skill

This skill exists to validate that:

1. The frontmatter parser handles the agentskills.io format correctly
2. The skill registry finds and indexes the skill
3. The `load_skill` tool returns this content
4. The vector store can embed the description and surface this skill via top-K retrieval

## When to use this skill

The agent should call `load_skill("example")` only when the user explicitly asks to test the skills engine. Real work should use real skills, not this one.

## Bundled resources

- `scripts/hello.sh` — prints a greeting; use to verify `run_skill_script` works.
- `references/notes.md` — placeholder reference file for `read_skill_file` testing.

## Expected agent behavior

When invoked, the agent should:
- Acknowledge it has loaded the skill
- Run `scripts/hello.sh` if the user wants to confirm script execution works
- Read `references/notes.md` if the user wants to confirm reference loading works
