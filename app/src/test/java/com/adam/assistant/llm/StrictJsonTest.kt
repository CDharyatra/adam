package com.adam.assistant.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StrictJsonTest {

    @Test
    fun parsesTypedValues() {
        val v = StrictJson.parse("""{"n":12,"s":"x","b":true,"arr":["a","b"],"o":{"k":"v"}}""")
        assertTrue(v is StrictJson.JsonValue.Obj)
        val m = (v as StrictJson.JsonValue.Obj).members
        assertEquals(StrictJson.JsonValue.Num("12"), m["n"])
        assertEquals(StrictJson.JsonValue.Str("x"), m["s"])
        assertEquals(StrictJson.JsonValue.Bool(true), m["b"])
        assertEquals(
            StrictJson.JsonValue.Arr(
                listOf(StrictJson.JsonValue.Str("a"), StrictJson.JsonValue.Str("b"))
            ),
            m["arr"]
        )
        assertTrue(m["o"] is StrictJson.JsonValue.Obj)
    }

    @Test
    fun rejectsDuplicateKeys() {
        try {
            StrictJson.parse("""{"a":"1","a":"2"}""")
            fail("duplicate key must throw")
        } catch (_: StrictJson.JsonError) {
            // expected
        }
        assertNull(StrictJson.parseOrNull("""{"a":"1","a":"2"}"""))
    }

    @Test
    fun rejectsTrailingContentAndProseAndFences() {
        assertNull(StrictJson.parseOrNull("""{"a":"1"} hello"""))
        assertNull(StrictJson.parseOrNull("Here is the JSON: {}"))
        assertNull(StrictJson.parseOrNull("```json\n{}\n```"))
        assertNull(StrictJson.parseOrNull(""))
        assertNull(StrictJson.parseOrNull("{"))
    }

    @Test
    fun rejectsMultipleObjects() {
        // A single command must be exactly one object (Task 13).
        assertNull(StrictJson.parseOrNull("""{"a":1}{"b":2}"""))
        assertNull(StrictJson.parseOrNull("{}{}"))
    }

    @Test
    fun acceptsSurroundingWhitespaceOnly() {
        assertTrue(StrictJson.parse("\n\t {\"a\":\"1\"} \n") is StrictJson.JsonValue.Obj)
    }

    @Test
    fun decodesEscapes() {
        val v = StrictJson.parse("""{"a":"line\nbreak \"quoted\" \u0041"}""")
        val str = (v as StrictJson.JsonValue.Obj).members["a"] as StrictJson.JsonValue.Str
        assertEquals("line\nbreak \"quoted\" A", str.value)
    }
}
