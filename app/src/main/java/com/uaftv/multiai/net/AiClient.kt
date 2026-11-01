package com.uaftv.multiai.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Sends requests; cancelling the coroutine cancels the HTTP call. */
class AiClient(private val http: OkHttpClient = defaultHttpClient()) {

    suspend fun ask(format: ApiFormat, baseUrl: String, apiKey: String, model: String, prompt: String): String {
        val body = execute(ProviderApi.chatRequest(format, baseUrl, apiKey, model, prompt))
        return ProviderApi.parseChat(format, body)
    }

    suspend fun listModels(format: ApiFormat, baseUrl: String, apiKey: String): List<String> {
        val body = execute(ProviderApi.modelsRequest(format, baseUrl, apiKey))
        return ProviderApi.parseModels(format, body)
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ApiException(networkMessage(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { r -> r.code to (r.body?.string() ?: "") } }
                if (!cont.isActive) return
                result.fold(
                    onSuccess = { (code, text) ->
                        if (code in 200..299) {
                            cont.resume(text)
                        } else {
                            cont.resumeWithException(ApiException(ProviderApi.errorMessage(code, text)))
                        }
                    },
                    onFailure = { e -> cont.resumeWithException(ApiException(networkMessage(e))) },
                )
            }
        })
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS) // reasoning models can think for a while
            .callTimeout(240, TimeUnit.SECONDS)
            .build()

        fun networkMessage(e: Throwable): String = when (e) {
            is UnknownHostException -> "Couldn't reach the server. Check the internet connection."
            is InterruptedIOException -> "The request timed out."
            else -> "Network error: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
