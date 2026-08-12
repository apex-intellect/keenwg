package ru.anisimov.keenwg.data.companion

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

data class CompanionHttpResponse(val status: Int, val body: String)

class CompanionHttpTransport {
    private val clients = ConcurrentHashMap<ClientKey, OkHttpClient>()

    fun execute(
        endpoint: CompanionEndpoint,
        path: String,
        method: String = "GET",
        body: String? = null,
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
        expectBody: Boolean = true,
    ): CompanionHttpResponse = execute(
        target = endpoint.target,
        path = path,
        method = method,
        token = endpoint.deviceToken,
        body = body,
        maxResponseBytes = maxResponseBytes,
        expectBody = expectBody,
    )

    fun execute(
        target: CompanionTarget,
        path: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null,
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
        expectBody: Boolean = true,
    ): CompanionHttpResponse {
        require(path.startsWith('/') && !path.startsWith("//") && '?' !in path && '#' !in path)
        require(maxResponseBytes in 0..MAX_RESPONSE_BYTES)
        if (token != null) require(token.isNotBlank() && token.length <= 512 && token.none(Char::isISOControl))
        val url = target.baseUrl.resolve(path) ?: throw CompanionTransportException()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeRequest(target, request, maxResponseBytes, expectBody)
    }

    fun execute(
        endpoint: CompanionEndpoint,
        path: String,
        method: String,
        body: RequestBody,
        maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
        expectBody: Boolean = true,
    ): CompanionHttpResponse {
        require(path.startsWith('/') && !path.startsWith("//") && '?' !in path && '#' !in path)
        require(method == "POST" || method == "PUT")
        require(maxResponseBytes in 0..MAX_RESPONSE_BYTES)
        val url = endpoint.target.baseUrl.resolve(path) ?: throw CompanionTransportException()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer ${endpoint.deviceToken}")
            .method(method, body)
            .build()
        return executeRequest(endpoint.target, request, maxResponseBytes, expectBody)
    }

    private fun executeRequest(
        target: CompanionTarget,
        request: Request,
        maxResponseBytes: Long,
        expectBody: Boolean,
    ): CompanionHttpResponse {
        val response = try {
            client(target).newCall(request).execute()
        } catch (failure: IOException) {
            throw CompanionTransportException(failure)
        }
        response.use {
            if (!expectBody) return CompanionHttpResponse(it.code, "")
            val responseBody = it.body ?: throw CompanionTransportException()
            if (responseBody.contentLength() > maxResponseBytes) throw CompanionResponseTooLargeException()
            val input = responseBody.byteStream()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxResponseBytes) throw CompanionResponseTooLargeException()
                output.write(buffer, 0, count)
            }
            return CompanionHttpResponse(it.code, output.toString(Charsets.UTF_8.name()))
        }
    }

    private fun client(target: CompanionTarget): OkHttpClient {
        val key = ClientKey(target.baseUrl.scheme, target.baseUrl.host, target.baseUrl.port, target.certificatePin)
        return clients.getOrPut(key) {
            val trustManager = try {
                ExactPinTrustManager(target.certificatePin)
            } catch (failure: IllegalArgumentException) {
                throw CompanionTransportException(failure)
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf(trustManager), SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(context.socketFactory, trustManager)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .build()
        }
    }

    private data class ClientKey(val scheme: String, val host: String, val port: Int, val pin: String)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DEFAULT_MAX_RESPONSE_BYTES = 1_048_576L
        const val MAX_RESPONSE_BYTES = 2_097_152L
    }
}

open class CompanionTransportException(cause: Throwable? = null) : IOException("Companion transport unavailable", cause)
class CompanionResponseTooLargeException : CompanionTransportException()
