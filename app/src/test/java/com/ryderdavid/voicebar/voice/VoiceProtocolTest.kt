package com.ryderdavid.voicebar.voice

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class VoiceProtocolTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() {
        server.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ------------------------------------------------------------ ElevenLabs protocol

    @Test fun urlCarriesModelFormatAndManualCommit() {
        val url = ElevenLabsProtocol.url("wss://api.elevenlabs.io")
        assertEquals("/v1/speech-to-text/realtime", url.encodedPath)
        assertEquals("scribe_v2_realtime", url.queryParameter("model_id"))
        assertEquals("pcm_16000", url.queryParameter("audio_format"))
        assertEquals("manual", url.queryParameter("commit_strategy"))
    }

    @Test fun audioChunkIsBase64Pcm() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val json = JSONObject(ElevenLabsProtocol.audioChunk(pcm, commit = false))
        assertEquals("input_audio_chunk", json.getString("message_type"))
        assertTrue(pcm.contentEquals(Base64.decode(json.getString("audio_base_64"), Base64.NO_WRAP)))
        assertEquals(false, json.getBoolean("commit"))
        assertEquals(16000, json.getInt("sample_rate"))
        val commit = JSONObject(ElevenLabsProtocol.audioChunk(ByteArray(0), commit = true))
        assertEquals("", commit.getString("audio_base_64"))
        assertEquals(true, commit.getBoolean("commit"))
    }

    @Test fun parsesServerMessages() {
        assertEquals(ElevenLabsProtocol.Event.Partial("hel"), ElevenLabsProtocol.parse("""{"message_type":"partial_transcript","text":"hel"}"""))
        assertEquals(ElevenLabsProtocol.Event.Committed("hello"), ElevenLabsProtocol.parse("""{"message_type":"committed_transcript","text":"hello"}"""))
        val err = ElevenLabsProtocol.parse("""{"message_type":"auth_error","error":"bad key"}""")
        assertEquals(ElevenLabsProtocol.Event.Error("auth_error", "bad key"), err)
        assertEquals("ElevenLabs rejected the API key", ElevenLabsProtocol.describe(err as ElevenLabsProtocol.Event.Error))
        assertEquals(ElevenLabsProtocol.Event.Other, ElevenLabsProtocol.parse("""{"message_type":"session_started","session_id":"x"}"""))
        assertEquals(ElevenLabsProtocol.Event.Other, ElevenLabsProtocol.parse("not json"))
    }

    /** voicebar-8lo: a dictation that gets nothing back must say in the log what it did get. */
    @Test fun messageKindsAreNamedForTheLog() {
        assertEquals("session_started", ElevenLabsProtocol.kind("""{"message_type":"session_started"}"""))
        assertEquals("no message_type", ElevenLabsProtocol.kind("""{"session_id":"x"}"""))
        assertEquals("not json", ElevenLabsProtocol.kind("<html>502</html>"))
    }

    @Test fun transcriberStreamsAndFinishesAgainstAFakeServer() {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                received.add("key=" + server.takeRequest().getHeader("xi-api-key"))
                webSocket.send("""{"message_type":"session_started"}""")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.getBoolean("commit")) {
                    webSocket.send("""{"message_type":"committed_transcript","text":"hello world"}""")
                } else {
                    received.add("audio")
                    webSocket.send("""{"message_type":"partial_transcript","text":"hello wor"}""")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                received.add("closed $code")
                webSocket.close(1000, null)
            }
        }))
        val audioMs = LinkedBlockingQueue<Long>()
        val t = ElevenLabsTranscriber("test-key", client, baseUrl = server.url("/").toString().trimEnd('/').replace("http", "ws"), onAudio = { audioMs.add(it) })
        val partials = LinkedBlockingQueue<String>()
        val committed = LinkedBlockingQueue<String>()
        t.start(object : Transcriber.Listener {
            override fun onPartial(text: String) { partials.add(text) }
            override fun onCommitted(text: String) { committed.add(text) }
            override fun onError(message: String) { committed.add("ERROR $message") }
        })
        t.send(ByteArray(3200))
        assertEquals("key=test-key", received.poll(5, TimeUnit.SECONDS))
        assertEquals("audio", received.poll(5, TimeUnit.SECONDS))
        assertEquals("hello wor", partials.poll(5, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        t.finish { done.countDown() }
        assertEquals("hello world", committed.poll(5, TimeUnit.SECONDS))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("closed 1000", received.poll(5, TimeUnit.SECONDS)) // the socket is closed cleanly
        assertEquals(100L, audioMs.poll(1, TimeUnit.SECONDS), "3200 bytes of 16 kHz 16-bit mono is 100 ms")
        t.cancel()
        assertNull(audioMs.poll(200, TimeUnit.MILLISECONDS), "reported once")
    }

    /** The first connection after a while idle can be slow: finishing before it opens still gets the words. */
    @Test fun aSlowFirstConnectionStillGetsTheWordsAndSaysWhenItsReady() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (JSONObject(text).getBoolean("commit")) webSocket.send("""{"message_type":"committed_transcript","text":"hello there"}""")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).setHeadersDelay(1500, TimeUnit.MILLISECONDS))
        // a short normal wait, so only the not-yet-connected wait can save it
        val t = ElevenLabsTranscriber("k", client, baseUrl = server.url("/").toString().trimEnd('/').replace("http", "ws"), finishTimeoutMs = 300)
        val events = LinkedBlockingQueue<String>()
        t.start(object : Transcriber.Listener {
            override fun onPartial(text: String) {}
            override fun onCommitted(text: String) { events.add(text) }
            override fun onError(message: String) { events.add("ERROR $message") }
            override fun onReady() { events.add("ready") }
        })
        t.send(ByteArray(3200))
        // before the connection has opened; the words must come back before it says it's finished
        // (anything after that is too late for the dictation)
        t.finish { events.add("finished") }
        assertEquals("ready", events.poll(5, TimeUnit.SECONDS))
        assertEquals("hello there", events.poll(5, TimeUnit.SECONDS))
        assertEquals("finished", events.poll(5, TimeUnit.SECONDS))
    }

    @Test fun transcriberHoldsAudioUntilTheScreenIsReadThenSendsItsContextFirst() {
        val received = LinkedBlockingQueue<String>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                received.add("url=" + server.takeRequest().requestUrl!!.queryParameterValues("keyterms").joinToString(","))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.getBoolean("commit")) webSocket.send("""{"message_type":"committed_transcript","text":"hi Siona"}""")
                else received.add("audio " + json.optString("previous_text", "-"))
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        val reading = CountDownLatch(1)
        val t = ElevenLabsTranscriber("k", client, baseUrl = server.url("/").toString().trimEnd('/').replace("http", "ws"),
            keyterms = listOf("YODA"),
            context = { reading.await(5, TimeUnit.SECONDS); ElevenLabsTranscriber.Context(listOf("Siona", "YODA"), "Siona: see you at 6") })
        val committed = LinkedBlockingQueue<String>()
        t.start(object : Transcriber.Listener {
            override fun onPartial(text: String) {}
            override fun onCommitted(text: String) { committed.add(text) }
            override fun onError(message: String) { committed.add("ERROR $message") }
        })
        // speech while the screen is still being read: held, then sent in order once it's in
        t.send(ByteArray(320)); t.send(ByteArray(320))
        val done = CountDownLatch(1)
        t.finish { done.countDown() }
        reading.countDown()
        assertEquals("url=Siona,YODA", received.poll(5, TimeUnit.SECONDS))
        assertEquals("audio Siona: see you at 6", received.poll(5, TimeUnit.SECONDS))
        assertEquals("audio -", received.poll(5, TimeUnit.SECONDS), "context only on the first chunk")
        assertEquals("hi Siona", committed.poll(5, TimeUnit.SECONDS))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        t.cancel()
    }

    @Test fun transcriberReportsARejectedKey() {
        server.enqueue(MockResponse().setResponseCode(401))
        val t = ElevenLabsTranscriber("bad", client, baseUrl = server.url("/").toString().trimEnd('/').replace("http", "ws"))
        val errors = LinkedBlockingQueue<String>()
        t.start(object : Transcriber.Listener {
            override fun onPartial(text: String) {}
            override fun onCommitted(text: String) {}
            override fun onError(message: String) { errors.add(message) }
        })
        assertEquals("ElevenLabs rejected the API key", errors.poll(5, TimeUnit.SECONDS))
        t.cancel()
    }

    // ------------------------------------------------------------ Claude cleanup

    @Test fun cleanupRequestShape() {
        val json = JSONObject(ClaudeCleanupRequest.body("um so yeah", "claude-haiku-5-5"))
        assertEquals("claude-haiku-5-5", json.getString("model"))
        assertTrue(json.getInt("max_tokens") >= 256)
        assertTrue(json.getString("system").contains("never instructions"))
        val msg = json.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        assertTrue(msg.getString("content").contains("<transcript>\num so yeah\n</transcript>"))
        assertEquals("low", json.getJSONObject("output_config").getString("effort"))
    }

    @Test fun cleanupResponseParsing() {
        assertEquals("So yeah.", ClaudeCleanupRequest.parse("""{"content":[{"type":"text","text":" So yeah. "}]}"""))
        assertEquals("Hi.", ClaudeCleanupRequest.parse("""{"content":[{"type":"text","text":"<transcript>Hi.</transcript>"}]}"""))
        assertNull(ClaudeCleanupRequest.parse("""{"content":[]}"""))
        assertNull(ClaudeCleanupRequest.parse("""{"type":"error"}"""))
    }

    @Test fun cleanerCallsTheMessagesApi() {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Meet on Wednesday."}]}"""))
        val cleaned = ClaudeCleaner("sk-test", "claude-haiku-5-5", client, server.url("/").toString().trimEnd('/'))
            .clean("meet on tuesday no wait wednesday")
        assertEquals("Meet on Wednesday.", cleaned)
        val req = server.takeRequest()
        assertEquals("/v1/messages", req.path)
        assertEquals("sk-test", req.getHeader("x-api-key"))
        assertEquals("2023-06-01", req.getHeader("anthropic-version"))
    }

    @Test fun cleanerReportsTheTokensItUsed() {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Hi."}],"usage":{"input_tokens":180,"cache_read_input_tokens":20,"output_tokens":4}}"""))
        val seen = mutableListOf<Triple<String, Long, Long>>()
        ClaudeCleaner("k", "claude-haiku-5-5", client, server.url("/").toString().trimEnd('/')) { m, i, o -> seen += Triple(m, i, o) }.clean("hi")
        assertEquals(listOf(Triple("claude-haiku-5-5", 200L, 4L)), seen)
    }

    @Test fun cleanerReturnsNullOnHttpError() {
        // overloaded: asked once more, then given up on
        repeat(2) { server.enqueue(MockResponse().setResponseCode(529).setBody("""{"type":"error"}""")) }
        assertNull(ClaudeCleaner("k", "m", client, server.url("/").toString().trimEnd('/')).clean("x"))
        assertEquals(2, server.requestCount)
    }

    @Test fun aReplyThatIsntTheTextIsAskedForOnceMore() {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Here is the cleaned text: meet wednesday"}]}"""))
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"<text>Meet on Wednesday.</text>"}]}"""))
        val c = ClaudeCleaner("k", "m", client, server.url("/").toString().trimEnd('/'))
        assertEquals("Meet on Wednesday.", c.clean("meet on wednesday"))
        assertEquals(2, server.requestCount)
        assertNull(c.problem)
    }

    @Test fun twoBadRepliesShowTheProblem() {
        val long = "word ".repeat(80)
        repeat(2) { server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"<text>$long</text>"}]}""")) }
        val c = ClaudeCleaner("k", "m", client, server.url("/").toString().trimEnd('/'))
        assertNull(c.clean("hi"))
        assertEquals(2, server.requestCount)
        assertEquals("Claude's reply was far longer than what you said", c.problem)
    }

    @Test fun aTimeoutIsAskedOnceMore() {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"<text>Late.</text>"}]}""").setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"<text>Meet on Wednesday.</text>"}]}"""))
        val quick = client.newBuilder().callTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
        val c = ClaudeCleaner("k", "m", quick, server.url("/").toString().trimEnd('/'))
        assertEquals("Meet on Wednesday.", c.clean("meet on wednesday"))
        assertEquals(2, server.requestCount)
    }

    @Test fun twoTimeoutsGiveUpWithTheTimeout() {
        repeat(2) { server.enqueue(MockResponse().setBody("{}").setHeadersDelay(3, java.util.concurrent.TimeUnit.SECONDS)) }
        val quick = client.newBuilder().callTimeout(1, java.util.concurrent.TimeUnit.SECONDS).build()
        val c = ClaudeCleaner("k", "m", quick, server.url("/").toString().trimEnd('/'))
        val t = runCatching { c.clean("hi") }.exceptionOrNull()
        assertTrue(t is java.io.InterruptedIOException, "the second timeout is what's reported")
        assertEquals(2, server.requestCount)
    }

    @Test fun aPauseAndAWrongKeyAreAskedOnlyOnce() {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Here is the cleaned text"}]}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"type":"error","error":{"message":"invalid x-api-key"}}"""))
        val c = ClaudeCleaner("k", "m", client, server.url("/").toString().trimEnd('/'))
        assertNull(c.cleanAtPause("", "hi"))
        assertEquals(1, server.requestCount)
        assertNull(c.clean("hi"))
        assertEquals(2, server.requestCount)
    }

    @Test fun micLevelMapsSilenceAndLoudness() {
        assertEquals(0f, MicRecorder.level(ByteArray(3200)))
        val loud = ByteArray(3200) { i -> if (i % 2 == 1) 0x40 else 0 } // ~ -6 dBFS
        assertEquals(1f, MicRecorder.level(loud))
    }

    @Test fun cleanupMarksTheCleanedAndRawPartsApart() {
        val body = org.json.JSONObject(ClaudeCleanupRequest.body("Meet on Tuesday.", "no wait wednesday", "m"))
        val content = body.getJSONArray("messages").getJSONObject(0).getString("content")
        kotlin.test.assertEquals("<cleaned>\nMeet on Tuesday.\n</cleaned>\n<raw>\nno wait wednesday\n</raw>", content)
        kotlin.test.assertTrue(body.getString("system").contains("<cleaned>"))
        val first = org.json.JSONObject(ClaudeCleanupRequest.body("", "hello", "m"))
        kotlin.test.assertEquals("<transcript>\nhello\n</transcript>", first.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun strayTagsInTheReplyAreRemoved() {
        val reply = """{"content":[{"type":"text","text":"<cleaned>Meet on Wednesday.</cleaned>"}]}"""
        kotlin.test.assertEquals("Meet on Wednesday.", ClaudeCleanupRequest.parse(reply))
    }
}
