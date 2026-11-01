package com.uaftv.multiai.net

import okhttp3.Request
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiTest {

    private fun bodyOf(request: Request): JSONObject {
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        return JSONObject(buffer.readUtf8())
    }

    @Test
    fun openAiCompatibleRequest() {
        val r = ProviderApi.chatRequest(ApiFormat.OPENAI_COMPATIBLE, "https://api.x.ai/v1/", "k1", "grok-x", "Hi")
        assertEquals("https://api.x.ai/v1/chat/completions", r.url.toString())
        assertEquals("Bearer k1", r.header("Authorization"))
        assertEquals("POST", r.method)
        val body = bodyOf(r)
        assertEquals("grok-x", body.getString("model"))
        val msg = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        assertEquals("Hi", msg.getString("content"))
    }

    @Test
    fun providerBaseUrlsBuildTheDocumentedEndpoints() {
        fun url(p: Provider) = ProviderApi.chatRequest(p.format, p.baseUrl, "k", "m", "q").url.toString()
        assertEquals("https://api.deepseek.com/chat/completions", url(Provider.DEEPSEEK))
        assertEquals("https://api.venice.ai/api/v1/chat/completions", url(Provider.VENICE))
        assertEquals("https://api.openai.com/v1/chat/completions", url(Provider.CHATGPT))
        assertEquals("https://api.x.ai/v1/chat/completions", url(Provider.GROK))
        assertEquals("https://api.anthropic.com/v1/messages", url(Provider.CLAUDE))
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/m:generateContent",
            url(Provider.GEMINI),
        )
    }

    @Test
    fun anthropicRequest() {
        val r = ProviderApi.chatRequest(ApiFormat.ANTHROPIC, "https://api.anthropic.com/v1", "ak", "claude-x", "Hello")
        assertEquals("https://api.anthropic.com/v1/messages", r.url.toString())
        assertEquals("ak", r.header("x-api-key"))
        assertEquals("2023-06-01", r.header("anthropic-version"))
        assertNull(r.header("Authorization"))
        val body = bodyOf(r)
        assertEquals("claude-x", body.getString("model"))
        assertEquals(ProviderApi.ANTHROPIC_MAX_TOKENS, body.getInt("max_tokens"))
        assertEquals("Hello", body.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun geminiRequestStripsModelsPrefix() {
        val r = ProviderApi.chatRequest(
            ApiFormat.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gk", "models/gemini-x", "Yo",
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-x:generateContent",
            r.url.toString(),
        )
        assertEquals("gk", r.header("x-goog-api-key"))
        val part = bodyOf(r).getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals("Yo", part.getString("text"))
    }

    @Test
    fun parsesOpenAiStringArrayAndRefusal() {
        assertEquals(
            "Paris",
            ProviderApi.parseChat(ApiFormat.OPENAI_COMPATIBLE, """{"choices":[{"message":{"content":"Paris"}}]}"""),
        )
        assertEquals(
            "AB",
            ProviderApi.parseChat(
                ApiFormat.OPENAI_COMPATIBLE,
                """{"choices":[{"message":{"content":[{"type":"text","text":"A"},{"type":"text","text":"B"}]}}]}""",
            ),
        )
        assertEquals(
            "I can't help with that.",
            ProviderApi.parseChat(
                ApiFormat.OPENAI_COMPATIBLE,
                """{"choices":[{"message":{"content":null,"refusal":"I can't help with that."}}]}""",
            ),
        )
    }

    @Test
    fun anthropicJoinsTextBlocksAndSkipsThinking() {
        val body = """{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":"One "},{"type":"text","text":"two"}]}"""
        assertEquals("One two", ProviderApi.parseChat(ApiFormat.ANTHROPIC, body))
    }

    @Test
    fun geminiSkipsThoughtPartsAndReportsBlocks() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"thinking","thought":true},{"text":"Answer"}]},"finishReason":"STOP"}]}"""
        assertEquals("Answer", ProviderApi.parseChat(ApiFormat.GEMINI, body))
        val blocked = assertThrows(ApiException::class.java) {
            ProviderApi.parseChat(ApiFormat.GEMINI, """{"promptFeedback":{"blockReason":"SAFETY"}}""")
        }
        assertTrue(blocked.message!!.contains("SAFETY"))
        val noText = assertThrows(ApiException::class.java) {
            ProviderApi.parseChat(ApiFormat.GEMINI, """{"candidates":[{"content":{"parts":[]},"finishReason":"MAX_TOKENS"}]}""")
        }
        assertTrue(noText.message!!.contains("MAX_TOKENS"))
    }

    @Test
    fun emptyOrBrokenAnswersThrow() {
        assertThrows(ApiException::class.java) {
            ProviderApi.parseChat(ApiFormat.OPENAI_COMPATIBLE, """{"choices":[]}""")
        }
        assertThrows(ApiException::class.java) {
            ProviderApi.parseChat(ApiFormat.ANTHROPIC, """{"content":[]}""")
        }
        assertThrows(ApiException::class.java) {
            ProviderApi.parseChat(ApiFormat.OPENAI_COMPATIBLE, "<html>Bad gateway</html>")
        }
    }

    @Test
    fun errorMessagesAreReadable() {
        assertEquals(
            "HTTP 401: Incorrect API key provided. Check the API key in Settings.",
            ProviderApi.errorMessage(401, """{"error":{"message":"Incorrect API key provided."}}"""),
        )
        assertEquals(
            "HTTP 400: Model not found",
            ProviderApi.errorMessage(400, """{"code":"x","error":"Model not found"}""").removeSuffix("."),
        )
        assertEquals("HTTP 500.", ProviderApi.errorMessage(500, "<html>oops</html>"))
        assertTrue(ProviderApi.errorMessage(429, "{}").contains("rate limit"))
    }

    @Test
    fun openAiModelListKeepsChatModelsNewestFirst() {
        val body = """{"data":[
            {"id":"gpt-old","created":1},
            {"id":"text-embedding-3-large","created":9},
            {"id":"dall-e-3","created":8},
            {"id":"gpt-new","created":5},
            {"id":"venice-image-model","type":"image","created":7},
            {"id":"tts-1","created":6}
        ]}"""
        assertEquals(listOf("gpt-new", "gpt-old"), ProviderApi.parseModels(ApiFormat.OPENAI_COMPATIBLE, body))
    }

    @Test
    fun geminiModelListNeedsGenerateContent() {
        val body = """{"models":[
            {"name":"models/gemini-pro-x","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]},
            {"name":"models/gemini-flash-x","supportedGenerationMethods":["generateContent"]}
        ]}"""
        assertEquals(listOf("gemini-pro-x", "gemini-flash-x"), ProviderApi.parseModels(ApiFormat.GEMINI, body))
    }

    @Test
    fun anthropicModelListKeepsOrder() {
        val body = """{"data":[{"type":"model","id":"claude-b"},{"type":"model","id":"claude-a"}],"has_more":false}"""
        assertEquals(listOf("claude-b", "claude-a"), ProviderApi.parseModels(ApiFormat.ANTHROPIC, body))
    }

    @Test
    fun modelRequestsUseEachProvidersAuth() {
        val o = ProviderApi.modelsRequest(ApiFormat.OPENAI_COMPATIBLE, "https://api.deepseek.com", "dk")
        assertEquals("https://api.deepseek.com/models", o.url.toString())
        assertEquals("Bearer dk", o.header("Authorization"))
        val a = ProviderApi.modelsRequest(ApiFormat.ANTHROPIC, "https://api.anthropic.com/v1", "ak")
        assertEquals("ak", a.header("x-api-key"))
        assertEquals("2023-06-01", a.header("anthropic-version"))
        val g = ProviderApi.modelsRequest(ApiFormat.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gk")
        assertEquals("gk", g.header("x-goog-api-key"))
        assertEquals("GET", g.method)
    }
}
