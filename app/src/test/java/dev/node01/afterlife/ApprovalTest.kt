package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalTest {

    @Test
    fun saysClaudesOwnDescriptionOfACommand() {
        assertEquals(
            "Claude wants to delete the build folder.",
            Approval.describe("Bash", """{"command":"rm -rf build","description":"Delete the build folder."}""", "node01"),
        )
        assertEquals("keeps a leading acronym", "Claude wants to NPM install the packages.",
            Approval.describe("PowerShell", """{"command":"npm i","description":"NPM install the packages"}""", "node01"))
        assertEquals("Claude wants to run a command.", Approval.describe("Bash", """{"command":"rm -rf build"}""", "node01"))
    }

    @Test
    fun namesFilesAndSitesNotWhereTheyAre() {
        assertEquals("Claude wants to edit Rephraser.kt.",
            Approval.describe("Edit", """{"file_path":"C:\\code\\app\\Rephraser.kt","old_string":"a"}""", "app"))
        assertEquals("Claude wants to write notes.md.", Approval.describe("Write", """{"file_path":"/home/me/notes.md"}""", ""))
        assertEquals("Claude wants to open github.com.", Approval.describe("WebFetch", """{"url":"https://www.github.com/a/b"}""", ""))
        assertEquals("Claude wants to search the web for kotlin regex.",
            Approval.describe("WebSearch", """{"query":"kotlin regex"}""", ""))
        assertEquals("Claude wants to use github create issue.", Approval.describe("mcp__github__create_issue", "{}", ""))
        assertEquals("Claude wants to edit a file.", Approval.describe("Edit", null, ""))
    }

    @Test
    fun neverSaysTheProjectsName() {
        assertEquals(
            "Claude wants to push the project to GitHub.",
            Approval.describe("Bash", """{"description":"Push node01 to GitHub"}""", "node01"),
        )
        // "Claude" in the prefix stays, whatever the folder is called.
        assertEquals("Claude wants to run tests.", Approval.describe("Bash", """{"description":"Run tests"}""", "claude"))
        assertNull(Approval.describe(null, "{}", "node01"))
    }
}
