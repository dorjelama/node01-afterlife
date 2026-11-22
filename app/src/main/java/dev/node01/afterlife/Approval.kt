package dev.node01.afterlife

import java.net.URI

/**
 * What Claude Code is asking permission to do, as one sentence to say aloud:
 * "Claude wants to delete the build folder." Made from the tool's name and its
 * input, as the PermissionRequest hook sends them. A command comes with
 * Claude's own short description of it, which is said instead of the command;
 * a file is named without its folders, a web page by its site, and the
 * project's own name is never said. Pure, so it is unit-tested.
 */
object Approval {

    /** Null when the event names no tool. */
    fun describe(tool: String?, input: String?, project: String): String? {
        if (tool.isNullOrBlank()) return null
        val f = JsonFields.extract(input.orEmpty(), INPUT)
        val what = when (tool) {
            "Bash", "PowerShell" -> f["description"]?.let { lowerFirst(it.trim().trimEnd('.')) }?.ifBlank { null }
                ?: "run a command"
            "Edit", "MultiEdit" -> "edit ${file(f["file_path"])}"
            "NotebookEdit" -> "edit ${file(f["notebook_path"])}"
            "Write" -> "write ${file(f["file_path"])}"
            "Read" -> "read ${file(f["file_path"])}"
            "WebFetch" -> "open ${site(f["url"])}"
            "WebSearch" -> f["query"]?.let { "search the web for $it" } ?: "search the web"
            // "mcp__github__create_issue": use github create issue.
            else -> "use " + tool.removePrefix("mcp__").split("__", "_").filter { it.isNotBlank() }.joinToString(" ")
        }
        val unnamed = if (project.length < MIN_PROJECT) what else
            what.replace(Regex("""\b${Regex.escape(project)}\b""", RegexOption.IGNORE_CASE), "the project")
        return Inbox.clean("Claude wants to $unnamed.", MAX_CHARS)
    }

    /** "Delete the build folder" -> "delete the build folder"; "NPM install" keeps its capitals. */
    private fun lowerFirst(s: String) = if (s.getOrNull(1)?.isLowerCase() == true) s.replaceFirstChar { it.lowercase() } else s

    private fun file(path: String?) =
        path?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')?.ifBlank { null } ?: "a file"

    private fun site(url: String?) =
        url?.let { runCatching { URI(it).host?.removePrefix("www.") }.getOrNull() } ?: "a web page"

    private val INPUT = setOf("description", "file_path", "notebook_path", "url", "query")

    /** "claude", what an unknown folder is called, and other short names would cut into ordinary words. */
    private const val MIN_PROJECT = 3
    private const val MAX_CHARS = 160
}
