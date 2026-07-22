---
name: Tool & Environment Quirks
alwaysApply: true
description: Known toolchain quirks - markdown tables, PowerShell syntax, file editing, and project exploration limits.
---

# Tool & Environment Quirks

Known idiosyncrasies of the agent toolchain. Learn these to avoid repeated failures.

## 1. Markdown Table Formatting

The renderer expects **spaces around pipe separators** in tables:

- `| text | text |` — correct (space before and after each pipe)
- `|text|text|` — wrong
- `|---|` — wrong (separator row needs `| --- | --- |`)

Always format tables as:

| Header A | Header B |
| --- | --- |
| Value 1 | Value 2 |

## 2. Terminal Execution

- Commands may hang, return no output, or fail silently.
- Background tasks (`waitForCompletion=false`) may not produce visible output.
- Gradle/Android builds often take >30s — need patience.
- If a command produces no output, ask the human to verify.

## 3. Shell is PowerShell (not bash)

The tool description says `powershell.exe` and testing confirms `ConsoleHost`. Use PowerShell syntax:

| What | PowerShell | Wrong (bash) |
| --- | --- | --- |
| Batch files | `.\gradlew.bat` | `./gradlew.bat` |
| Paths | backslashes or forward slashes | forward slashes only |
| Variables | `$env:VARNAME` | `$VARNAME` |
| Strings | double quotes for interpolation | single quotes |
| Command chaining | `cmd1; cmd2` | `cmd1 && cmd2` |
| String quoting | double quotes | single quotes |

### Git commit example (most common mistake)

```powershell
# Correct — PowerShell uses semicolons
git add -A; git commit -m "my message"

# Wrong — && is not valid in PowerShell
git add -A && git commit -m "my message"
```

**If a command fails**, it's likely a PowerShell syntax issue — try the PowerShell-native equivalent.

## 4. File Editing Reliability

`edit_existing_file` and `single_find_and_replace` often fail if:

- The file hasn't been read recently (always `read_file` before edit).
- The replacement string isn't unique (use surrounding context + `replace_all`).
- `edit_existing_file` cannot run in parallel with other tools (including itself).

### Workflow

1. `read_file` to get current contents
2. Construct the edit with exact whitespace/indentation
3. Apply edit (no parallel calls until complete)
4. Verify with `read_file` if needed

## 5. No Parallel Edit Tools

Only read-only tools (`read_file`, `grep_search`, `ls`, `file_glob_search`) can run in parallel.
All edit tools (`edit_existing_file`, `single_find_and_replace`, `create_new_file`) are serial.

## 6. Project Exploration Limits

- Deep recursive `ls` produces too much output (build/ dirs).
- Use targeted glob patterns (`**/*.kt`, `**/*.gradle.kts`) instead.
- `grep_search` skips build/cache/secrets automatically — use it for content searches.

## 7. No Environment Detection

Cannot detect SDK versions, JDK versions, or Gradle state from tools alone.
Ask the human when environment details are needed.

## 8. Web Search is Broken, URL Fetch Works

- `search_web` returns `401 Invalid API key`. Do not rely on it.
- `fetch_url_content` **does work** — use it to look up docs, APIs, SDK references via URL.

## 9. Absolute Paths Break Edit Tools

`edit_existing_file` and `single_find_and_replace` accept file paths but may fail with
`"file does not exist"` when passed absolute paths like `C:\Users\...`, even though the
file clearly exists on disk.

**Workaround:** Always use **relative paths** from the workspace root for edit tools.

- Correct: `temp/session-handover.md`
- Wrong: `C:\Users\home-\git\android-voice-core-stt\temp\session-handover.md`

`read_file` and `ls` work fine with either path style — this only affects write tools.
