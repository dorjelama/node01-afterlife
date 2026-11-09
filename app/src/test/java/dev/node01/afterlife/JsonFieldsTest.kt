package dev.node01.afterlife

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonFieldsTest {

    private val wanted = ClaudeSessions.FIELDS

    @Test
    fun readsTopLevelStrings() {
        val json = """
            {
              "session_id": "abc-123",
              "transcript_path": "C:\\Users\\me\\.claude\\x.jsonl",
              "cwd": "C:\\Users\\me\\code\\node01",
              "permission_mode": "default",
              "hook_event_name": "PermissionRequest",
              "tool_name": "Bash"
            }
        """.trimIndent()
        assertEquals(
            mapOf(
                "session_id" to "abc-123",
                "cwd" to "C:\\Users\\me\\code\\node01",
                "hook_event_name" to "PermissionRequest",
            ),
            JsonFields.extract(json, wanted),
        )
    }

    @Test
    fun ignoresSameNamedKeysInsideNestedValues() {
        val json = """{"tool_input":{"hook_event_name":"Stop","cwd":"/evil","list":[1,{"session_id":"x"}]},""" +
            """"tool_response":"{\"hook_event_name\":\"Stop\"}","hook_event_name":"PostToolUse","session_id":"s1"}"""
        assertEquals(
            mapOf("hook_event_name" to "PostToolUse", "session_id" to "s1"),
            JsonFields.extract(json, wanted),
        )
    }

    @Test
    fun skipsNumbersLiteralsAndArrays() {
        val json = """{"a":12.5e3,"b":true,"c":null,"d":[ "x", ["y"] ],"session_id":"s","e":-1}"""
        assertEquals(mapOf("session_id" to "s"), JsonFields.extract(json, wanted))
    }

    @Test
    fun decodesEscapes() {
        val json = """{"cwd":"a\"b\\c\/d\u00e9\n"}"""
        assertEquals("a\"b\\c/dé\n", JsonFields.extract(json, wanted)["cwd"])
    }

    @Test
    fun keepsWhatItFoundWhenCutOff() {
        val full = """{"session_id":"s1","hook_event_name":"PostToolUse","tool_response":{"stdout":"lots of output"}}"""
        for (end in 0..full.length) {
            val found = JsonFields.extract(full.take(end), wanted)
            // Never a wrong value: each field is either absent or exactly right.
            found["session_id"]?.let { assertEquals("s1", it) }
            found["hook_event_name"]?.let { assertEquals("PostToolUse", it) }
        }
        assertEquals(2, JsonFields.extract(full.take(full.indexOf("tool_response")), wanted).size)
    }

    @Test
    fun nonsenseGivesNothing() {
        for (s in listOf("", "null", "[1,2]", "{", "{\"session_id\"", "{\"session_id\":", "not json", "\"str\"")) {
            assertEquals(s, emptyMap<String, String>(), JsonFields.extract(s, wanted))
        }
    }
}
