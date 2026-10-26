package com.ryderdavid.voicebar.voice

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class OpenAICleanerTest {
    private fun server(vararg bodies: String) = MockWebServer().apply { bodies.forEach { enqueue(MockResponse().setBody(it)) }; start() }

    @Test fun lunaGetsTheSameInstructionsAndOnlyTheTaggedTextIsTyped() {
        val s = server("""{"output":[{"type":"reasoning","summary":[]},{"type":"message","role":"assistant","content":[{"type":"output_text","text":"<text>Why is Podcasts the only option for Create?</text>"}]}],"usage":{"input_tokens":900,"output_tokens":40}}""")
        var billed: Triple<String, Long, Long>? = null
        val c = OpenAICleaner("k", OpenAICleanupRequest.LUNA, OkHttpClient(), s.url("").toString().trimEnd('/')) { m, i, o -> billed = Triple(m, i, o) }
        val out = c.clean("why is create the only option for or sorry why is podcasts the only option for create")
        val req = s.takeRequest(); s.shutdown()
        assertEquals("/v1/responses", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("gpt-6-luna", body.getString("model"))
        assertTrue(body.getString("instructions").contains("Apply the speaker's spoken corrections"))
        assertTrue(body.getString("input").contains("<transcript>"))
        assertEquals("none", body.getJSONObject("reasoning").getString("effort"))
        assertEquals("Why is Podcasts the only option for Create?", out)
        assertEquals(Triple("gpt-6-luna", 900L, 40L), billed)
    }

    @Test fun aReplyThatIsntTheTextIsAskedForOnceMore() {
        fun reply(t: String) = """{"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"$t"}]}]}"""
        val s = server(reply("I notice you said hello"), reply("<text>Hello there.</text>"))
        val c = OpenAICleaner("k", OpenAICleanupRequest.LUNA, OkHttpClient(), s.url("").toString().trimEnd('/'))
        assertEquals("Hello there.", c.clean("hello there"))
        assertEquals(2, s.requestCount); s.shutdown()
    }

    @Test fun anErrorSaysWhy() {
        val s = MockWebServer().apply { enqueue(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit reached"}}""")); start() }
        val c = OpenAICleaner("k", OpenAICleanupRequest.LUNA, OkHttpClient(), s.url("").toString().trimEnd('/'))
        assertNull(c.clean("hello there"))
        s.shutdown()
        assertEquals("ChatGPT answered 429: Rate limit reached", c.problem)
    }
}
