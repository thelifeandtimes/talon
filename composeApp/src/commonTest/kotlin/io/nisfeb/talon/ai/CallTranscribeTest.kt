package io.nisfeb.talon.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class CallTranscribeTest {

    @Test
    fun parsesVerboseJsonSegments() {
        val body = """
            {"task":"transcribe","language":"english","duration":6.0,
             "text":"Hello there. General Kenobi.",
             "segments":[
               {"id":0,"start":0.0,"end":2.5,"text":" Hello there."},
               {"id":1,"start":2.5,"end":6.0,"text":" General Kenobi."}
             ]}
        """.trimIndent()
        val segs = CallTranscribe.parse(body)
        assertEquals(2, segs.size)
        assertEquals(0L, segs[0].startMs)
        assertEquals("Hello there.", segs[0].text)
        assertEquals(2500L, segs[1].startMs)
        assertEquals("General Kenobi.", segs[1].text)
    }

    @Test
    fun fallsBackToWholeTextWhenNoSegments() {
        val segs = CallTranscribe.parse("""{"text":"just the whole thing"}""")
        assertEquals(1, segs.size)
        assertEquals(0L, segs[0].startMs)
        assertEquals("just the whole thing", segs[0].text)
    }

    @Test
    fun invalidJsonFailsButSilenceSucceeds() {
        assertFails { CallTranscribe.parse("not json") }
        assertFails { CallTranscribe.parse("{}") }
        assertEquals(0, CallTranscribe.parse("""{"text":"   "}""").size)
    }
    @Test
    fun invalidTimingDoesNotBecomeZero() {
        for (start in listOf("-1", "1e309", "null", "\"oops\"")) {
            assertFails { CallTranscribe.parse("""{"text":"hello","segments":[{"text":"hello","start":$start}]}""") }
        }
        assertFails { CallTranscribe.parse("""{"text":"hello","segments":[{"text":"hello","start":2,"end":1}]}""") }
    }
}
