package com.uaftv.multiai.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** End-to-end over HTTP against a local mock server (no real AI service is contacted). */
class AiClientTest {
    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun base(path: String) = server.url(path).toString()

    @Test
    fun openAiCompatibleAnswerAndKeyHeader() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"Paris"}}]}"""))
        val answer = runBlocking {
            AiClient().ask(ApiFormat.OPENAI_COMPATIBLE, base("/v1"), "secret", "m1", "Capital of France?")
        }
        assertEquals("Paris", answer)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer secret", recorded.getHeader("Authorization"))
        assertTrue(recorded.body.readUtf8().contains("Capital of France?"))
    }

    @Test
    fun anthropicHttpErrorBecomesReadableMessage() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""),
        )
        val e = assertThrows(ApiException::class.java) {
            runBlocking { AiClient().ask(ApiFormat.ANTHROPIC, base("/v1"), "bad", "claude-x", "hi") }
        }
        assertTrue(e.message!!.contains("401"))
        assertTrue(e.message!!.contains("invalid x-api-key"))
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("bad", recorded.getHeader("x-api-key"))
    }

    @Test
    fun geminiAnswerOverHttp() {
        server.enqueue(MockResponse().setBody("""{"candidates":[{"content":{"parts":[{"text":"Hola"}]}}]}"""))
        val answer = runBlocking { AiClient().ask(ApiFormat.GEMINI, base("/v1beta"), "gk", "gemini-x", "Say hi") }
        assertEquals("Hola", answer)
        assertEquals("/v1beta/models/gemini-x:generateContent", server.takeRequest().path)
    }

    @Test
    fun listsModelsOverHttp() {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"deepseek-a","created":2},{"id":"deepseek-b","created":1}]}"""))
        val models = runBlocking { AiClient().listModels(ApiFormat.OPENAI_COMPATIBLE, base("/"), "dk") }
        assertEquals(listOf("deepseek-a", "deepseek-b"), models)
        assertEquals("/models", server.takeRequest().path)
    }

    @Test
    fun unreachableServerGivesNetworkMessage() {
        // Port 1 on loopback has nothing listening, so the connection is refused.
        val e = assertThrows(ApiException::class.java) {
            runBlocking { AiClient().ask(ApiFormat.OPENAI_COMPATIBLE, "http://127.0.0.1:1/v1", "k", "m", "q") }
        }
        assertTrue(e.message!!.isNotBlank())
    }
}
