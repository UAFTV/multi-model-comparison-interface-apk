package com.uaftv.multiai.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class ApiException(message: String) : Exception(message)

/** Pure request building and response parsing for each wire format (unit-tested). */
object ProviderApi {
    const val ANTHROPIC_VERSION = "2023-06-01"
    const val ANTHROPIC_MAX_TOKENS = 4096

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** Model ids containing these are not chat models (images, speech, embeddings, ...). */
    private val NON_CHAT_WORDS = listOf(
        "embedding", "embed", "tts", "whisper", "dall-e", "image", "moderation", "transcribe",
        "realtime", "audio", "davinci", "babbage", "sora", "upscale", "inpaint", "video", "speech",
    )

    /** Some OpenAI-compatible model lists (e.g. Venice) label each model with a type. */
    private val NON_TEXT_TYPES = setOf(
        "image", "embedding", "tts", "asr", "upscale", "inpaint", "video", "music", "audio",
    )

    fun chatRequest(format: ApiFormat, baseUrl: String, apiKey: String, model: String, prompt: String): Request {
        val base = baseUrl.trimEnd('/')
        val userMessage = JSONObject().put("role", "user").put("content", prompt)
        return when (format) {
            ApiFormat.OPENAI_COMPATIBLE -> {
                val body = JSONObject()
                    .put("model", model)
                    .put("messages", JSONArray().put(userMessage))
                Request.Builder()
                    .url("$base/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .build()
            }
            ApiFormat.ANTHROPIC -> {
                val body = JSONObject()
                    .put("model", model)
                    .put("max_tokens", ANTHROPIC_MAX_TOKENS)
                    .put("messages", JSONArray().put(userMessage))
                Request.Builder()
                    .url("$base/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .build()
            }
            ApiFormat.GEMINI -> {
                val content = JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                val body = JSONObject().put("contents", JSONArray().put(content))
                Request.Builder()
                    .url("$base/models/${model.removePrefix("models/")}:generateContent")
                    .header("x-goog-api-key", apiKey)
                    .post(body.toString().toRequestBody(JSON_TYPE))
                    .build()
            }
        }
    }

    fun parseChat(format: ApiFormat, body: String): String {
        val json = parseJson(body, "The service sent back something that isn't JSON.")
        val text = when (format) {
            ApiFormat.OPENAI_COMPATIBLE -> {
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) throw ApiException("No answer in the response.")
                val message = choices.optJSONObject(0)?.optJSONObject("message")
                    ?: throw ApiException("No answer in the response.")
                val content = when (val c = message.opt("content")) {
                    is String -> c
                    is JSONArray -> joinText(c)
                    else -> ""
                }
                content.ifEmpty { stringOrEmpty(message, "refusal") }
            }
            ApiFormat.ANTHROPIC -> {
                val blocks = json.optJSONArray("content") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    if (stringOrEmpty(block, "type") == "text") sb.append(stringOrEmpty(block, "text"))
                }
                sb.toString()
            }
            ApiFormat.GEMINI -> {
                val candidates = json.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    val reason = json.optJSONObject("promptFeedback")?.let { stringOrEmpty(it, "blockReason") }.orEmpty()
                    throw ApiException(
                        if (reason.isNotEmpty()) "Gemini blocked the prompt ($reason)." else "No answer in the response.",
                    )
                }
                val first = candidates.optJSONObject(0) ?: JSONObject()
                val parts = first.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (!part.optBoolean("thought", false)) sb.append(stringOrEmpty(part, "text"))
                }
                if (sb.isEmpty()) {
                    val finish = stringOrEmpty(first, "finishReason")
                    if (finish.isNotEmpty()) throw ApiException("No text came back (finish reason: $finish).")
                }
                sb.toString()
            }
        }
        if (text.isBlank()) throw ApiException("The answer came back empty.")
        return text
    }

    /** A short, readable message for a failed HTTP call, with a hint for the common cases. */
    fun errorMessage(code: Int, body: String): String {
        val raw: String = try {
            val json = JSONObject(body)
            when (val err = json.opt("error")) {
                is JSONObject -> stringOrEmpty(err, "message")
                is String -> err
                else -> stringOrEmpty(json, "message")
            }
        } catch (e: Exception) {
            ""
        }
        val detail = raw.trim().removeSuffix(".")
        val hint = when (code) {
            401, 403 -> " Check the API key in Settings."
            404 -> " Check the model name in Settings."
            429 -> " Your API account hit a rate limit or ran out of credit."
            else -> ""
        }
        val core = if (detail.isEmpty()) "HTTP $code" else "HTTP $code: $detail"
        return "$core.$hint"
    }

    fun modelsRequest(format: ApiFormat, baseUrl: String, apiKey: String): Request {
        val base = baseUrl.trimEnd('/')
        return when (format) {
            ApiFormat.OPENAI_COMPATIBLE -> Request.Builder()
                .url("$base/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            ApiFormat.ANTHROPIC -> Request.Builder()
                .url("$base/models?limit=1000")
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .get()
                .build()
            ApiFormat.GEMINI -> Request.Builder()
                .url("$base/models?pageSize=1000")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
        }
    }

    /** Chat-capable model ids, newest first where the provider says when a model was created. */
    fun parseModels(format: ApiFormat, body: String): List<String> {
        val json = parseJson(body, "The model list isn't JSON.")
        val ids = when (format) {
            ApiFormat.OPENAI_COMPATIBLE -> {
                val data = json.optJSONArray("data") ?: JSONArray()
                objects(data)
                    .filter { stringOrEmpty(it, "type").lowercase() !in NON_TEXT_TYPES }
                    .sortedByDescending { it.optLong("created", 0L) }
                    .map { stringOrEmpty(it, "id") }
                    .filter { isChatModel(it) }
            }
            ApiFormat.ANTHROPIC -> {
                val data = json.optJSONArray("data") ?: JSONArray()
                objects(data).map { stringOrEmpty(it, "id") }.filter { it.isNotEmpty() }
            }
            ApiFormat.GEMINI -> {
                val models = json.optJSONArray("models") ?: JSONArray()
                objects(models)
                    .filter { m ->
                        val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                        (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                    }
                    .map { stringOrEmpty(it, "name").removePrefix("models/") }
                    .filter { isChatModel(it) }
            }
        }
        return ids.distinct()
    }

    private fun isChatModel(id: String): Boolean {
        val lower = id.lowercase()
        return lower.isNotEmpty() && NON_CHAT_WORDS.none { lower.contains(it) }
    }

    private fun objects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    private fun joinText(parts: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            sb.append(stringOrEmpty(part, "text"))
        }
        return sb.toString()
    }

    /** Reads a string field; JSON null and non-strings become "" (Android's optString would give "null"). */
    private fun stringOrEmpty(obj: JSONObject, key: String): String {
        val value = obj.opt(key)
        return if (value is String) value else ""
    }

    private fun parseJson(body: String, failure: String): JSONObject =
        try {
            JSONObject(body)
        } catch (e: Exception) {
            throw ApiException(failure)
        }
}
