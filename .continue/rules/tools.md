---
name: Available Tools Reference
alwaysApply: true
description: Complete reference for all Continue.dev agent tools - read_file, edit, terminal, search, and their arguments.
---

# Available Tools Reference

Full tool definitions for the Continue.dev agent in VS Code.

---

## read_file

**Automatic** — No confirmation needed.

Read contents of an existing file.

**Arguments:**

- `filepath` (string) — Path to read. Can be relative (from workspace root), absolute, tilde (~/...), or file:// URI.

---

## create_new_file

**Ask First** — Confirmation required.

Create a new file. Only use when file doesn't exist.

**Arguments:**

- `filepath` (string) — Path for the new file. Can be relative, absolute, tilde, or file:// URI.
- `contents` (string) — Contents to write.

---

## run_terminal_command

**Automatic** — No confirmation needed.

Run a terminal command in the current directory. Shell is **non-interactive and stateless** — `cd` does NOT persist between commands.

**Important:**

- Use PowerShell syntax for Windows (.bat files, double quotes, `$env:VAR`).
- For long-running commands (Gradle builds), use `waitForCompletion=false` for background execution.
- Never use Ctrl+C to stop — suggest shell commands instead.
- Do not require admin/special privileges.
- Edit files with Edit/MultiEdit tools, not sed/awk.

**Arguments:**

- `command` (string) — The command to run.
- `waitForCompletion` (boolean) — Default `true`. Set `false` to run in background; `true` to wait for output.

---

## edit_existing_file

**Ask First** — Confirmation required.

Edit an existing file. **Cannot run in parallel** with any other tool (including itself).

**Rules:**

1. Read the file first before editing.
2. Show only the changed sections with `// ... existing code ...` placeholders.
3. Restate the enclosing function/class in the snippet.
4. Do NOT wrap changes in markdown codeblocks — plain text only.
5. Include a brief explanation of changes.

**Arguments:**

- `filepath` (string) — Path relative to workspace root.
- `changes` (string) — Modifications only, with placeholders for unmodified sections.

---

## single_find_and_replace

**Automatic** — No confirmation needed.

Performs exact string replacement in a file. **Cannot run in parallel** with any other tool (including itself).

**Rules:**

- Always `read_file` just before using.
- Preserve exact whitespace/indentation from the file.
- `old_string` MUST be unique in the file (or use `replace_all`).
- No emojis unless explicitly requested.

**Arguments:**

- `filepath` (string) — Path relative to workspace root.
- `old_string` (string) — Exact text to replace.
- `new_string` (string) — Replacement text (must be different).
- `replace_all` (boolean, optional) — Replace all occurrences (default false).

---

## grep_search

**Automatic** — No confirmation needed.

Regex search across the repository using ripgrep. Skips build/cache/secrets directories. Output may be truncated — use targeted queries.

**Arguments:**

- `query` (string) — Regex pattern. Use alternation (`word1|word2`) or character classes for multi-word searches.

---

## file_glob_search

**Automatic** — No confirmation needed.

Search for files recursively using glob patterns. Supports `**` for recursive search. Skips build/cache/secrets directories. Output may be truncated.

**Arguments:**

- `pattern` (string) — Glob pattern for file path matching.

---

## ls

**Automatic** — No confirmation needed.

List files and folders in a directory.

**Arguments:**

- `dirPath` (string) — Directory path. Relative, absolute, tilde, or file:// URI. Use forward slashes.
- `recursive` (boolean, optional) — Recursive listing. Use sparingly.

---

## view_diff

**Automatic** — No confirmation needed.

View current diff of working changes.

**Arguments:** None.

---

## read_currently_open_file

**Automatic** — No confirmation needed.

Read the file currently open in the IDE. Use when user references content not yet read.

**Arguments:** None.

---

## fetch_url_content

**Automatic** — No confirmation needed.

View a website's content via URL. Do NOT use for files.

**Arguments:**

- `url` (string) — The URL to read.

---

## search_web

**Automatic** — No confirmation needed.

Web search returning top results. Use sparingly — only for specialized/external/up-to-date knowledge.

**Note:** Currently broken (401 Invalid API key).

**Arguments:**

- `query` (string) — Natural language search query.

---

## read_skill

**Automatic** — No confirmation needed.

Read a skill by name. Skills contain detailed task instructions.

**Arguments:**

- `skillName` (string) — Name of the skill to read.

---

## create_rule_block

**Excluded** — Not available for use.

Create a rule for future conversations. Rule types:

| Type | How it activates |
| --- | --- |
| **Always** | Always included in model context |
| **Auto Attached** | Included when file patterns match |
| **Agent Requested** | AI decides when to apply based on description |
| **Manual** | Only when explicitly referenced (@ruleName) |

**Arguments:**

- `name` (string) — Short descriptive name.
- `rule` (string) — Clear imperative instruction.
- `description` (string) — When to apply (required for Agent Requested).
- `globs` (string, optional) — File patterns (e.g. `['**/*.kt']`).
- `regex` (string, optional) — Content match regex (e.g. `'fun .*{'`).
- `alwaysApply` (boolean, optional) — True for Always/Auto Attached.

---

## request_rule

**Excluded** — Not available for use.

Retrieve additional rules by name.

**Arguments:**

- `name` (string) — Name of the rule.
