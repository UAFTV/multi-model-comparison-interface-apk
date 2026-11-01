package com.uaftv.multiai.net

/** The wire formats the app speaks. Most providers use the OpenAI chat-completions format. */
enum class ApiFormat { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI }

/**
 * AIs answered through their public APIs. Order is the order shown on the toggle bar.
 * Base URLs were taken from each provider's API documentation (checked October 31, 2026).
 */
enum class Provider(
    val displayName: String,
    val company: String,
    val format: ApiFormat,
    val baseUrl: String,
    val keyHelp: String,
) {
    DEEPSEEK(
        "DeepSeek", "DeepSeek", ApiFormat.OPENAI_COMPATIBLE,
        "https://api.deepseek.com", "Create a key on the DeepSeek API platform.",
    ),
    GEMINI(
        "Gemini", "Google", ApiFormat.GEMINI,
        "https://generativelanguage.googleapis.com/v1beta", "Create a key in Google AI Studio.",
    ),
    CLAUDE(
        "Claude", "Anthropic", ApiFormat.ANTHROPIC,
        "https://api.anthropic.com/v1", "Create a key in the Claude Console (Anthropic).",
    ),
    CHATGPT(
        "ChatGPT", "OpenAI", ApiFormat.OPENAI_COMPATIBLE,
        "https://api.openai.com/v1", "Create a key on the OpenAI API platform.",
    ),
    GROK(
        "Grok", "xAI", ApiFormat.OPENAI_COMPATIBLE,
        "https://api.x.ai/v1", "Create a key in the xAI console.",
    ),
    VENICE(
        "Venice", "Venice AI", ApiFormat.OPENAI_COMPATIBLE,
        "https://api.venice.ai/api/v1", "Create a key in Venice's API settings.",
    ),
}
