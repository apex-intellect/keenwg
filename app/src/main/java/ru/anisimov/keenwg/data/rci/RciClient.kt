package ru.anisimov.keenwg.data.rci

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.anisimov.keenwg.domain.model.ServerSettings
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class RciFailure { REACHABILITY, CREDENTIALS, PROTOCOL, ROUTER }

class RciException(
    message: String,
    val failure: RciFailure = RciFailure.ROUTER,
    cause: Throwable? = null,
) : Exception(message, cause)

open class RciClient {
    private data class SessionKey(val scheme: String, val host: String, val port: Int, val login: String)

    private val sessions = ConcurrentHashMap<SessionKey, ConcurrentHashMap<String, Cookie>>()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .connectTimeout(Duration.ofSeconds(8))
        .readTimeout(Duration.ofSeconds(12))
        .build()

    private val jsonType = "application/json".toMediaType()
    private val authMutexes = ConcurrentHashMap<SessionKey, Mutex>()
    private val authVersions = ConcurrentHashMap<SessionKey, AtomicLong>()

    open suspend fun authenticate(s: ServerSettings): Unit = withContext(Dispatchers.IO) {
        val sessionKey = sessionKey(s)
        sessions.remove(sessionKey)
        val getReq = Request.Builder().url("${s.baseUrl}/auth").get().build()
        val realm: String
        val challenge: String
        call(getReq).use { resp ->
            saveSession(resp.request.url, resp.headers, sessionKey)
            realm = resp.header("X-NDM-Realm") ?: throw protocolError()
            challenge = resp.header("X-NDM-Challenge") ?: throw protocolError()
        }
        val hash = RciAuth.authResponse(s.login, realm, s.password, challenge)
        val body = buildJsonObject {
            put("login", s.login)
            put("password", hash)
        }.toString().toRequestBody(jsonType)
        val postReq = withSession(Request.Builder().url("${s.baseUrl}/auth").post(body).build(), sessionKey)
        call(postReq).use { resp ->
            saveSession(resp.request.url, resp.headers, sessionKey)
            if (!resp.isSuccessful) throw responseError(resp.code, authentication = true)
        }
        authVersions.computeIfAbsent(sessionKey) { AtomicLong() }.incrementAndGet()
    }

    open suspend fun post(s: ServerSettings, bodyJson: String): String = withContext(Dispatchers.IO) {
        execute(s, Request.Builder().url("${s.baseUrl}/rci/").post(bodyJson.toRequestBody(jsonType)).build())
    }

    open suspend fun get(s: ServerSettings, path: String): String = withContext(Dispatchers.IO) {
        execute(s, Request.Builder().url("${s.baseUrl}/rci/$path").get().build())
    }

    private suspend fun execute(s: ServerSettings, req: Request): String {
        val sessionKey = sessionKey(s)
        val version = authVersions.computeIfAbsent(sessionKey) { AtomicLong() }
        val versionBeforeRequest = version.get()
        call(withSession(req, sessionKey)).use { resp ->
            saveSession(resp.request.url, resp.headers, sessionKey)
            if (resp.code == 401) return authMutexes.computeIfAbsent(sessionKey) { Mutex() }.withLock {
                if (version.get() == versionBeforeRequest) authenticate(s)
                call(withSession(req, sessionKey)).use { retry ->
                    saveSession(retry.request.url, retry.headers, sessionKey)
                    if (!retry.isSuccessful) throw responseError(retry.code)
                    readBody(retry)
                }
            }
            if (!resp.isSuccessful) throw responseError(resp.code)
            return readBody(resp)
        }
    }

    private fun sessionKey(settings: ServerSettings) = SessionKey(
        scheme = "http",
        host = settings.host.lowercase(),
        port = settings.port,
        login = settings.login,
    )

    private fun withSession(request: Request, key: SessionKey): Request {
        val now = System.currentTimeMillis()
        val cookies = sessions[key]?.values?.filter { it.expiresAt > now && it.matches(request.url) }.orEmpty()
        if (cookies.isEmpty()) return request.newBuilder().removeHeader("Cookie").build()
        return request.newBuilder()
            .header("Cookie", cookies.joinToString("; ") { "${it.name}=${it.value}" })
            .build()
    }

    private fun saveSession(url: HttpUrl, headers: okhttp3.Headers, key: SessionKey) {
        val cookies = Cookie.parseAll(url, headers)
        if (cookies.isEmpty()) return
        val session = sessions.computeIfAbsent(key) { ConcurrentHashMap() }
        val now = System.currentTimeMillis()
        cookies.forEach { cookie ->
            if (cookie.expiresAt <= now) session.remove(cookie.name) else session[cookie.name] = cookie
        }
    }

    private fun readBody(response: okhttp3.Response): String {
        try {
            val body = response.body ?: return ""
            val source = body.source()
            source.request(MAX_RCI_RESPONSE_BYTES + 1)
            if (source.buffer.size > MAX_RCI_RESPONSE_BYTES) {
                throw RciException("Ответ RCI слишком велик", RciFailure.PROTOCOL)
            }
            return source.readUtf8()
        } catch (error: IOException) {
            throw reachabilityError(error)
        }
    }

    private fun call(request: Request): Response = try {
        http.newCall(request).execute()
    } catch (error: IOException) {
        throw reachabilityError(error)
    }

    private fun responseError(code: Int, authentication: Boolean = false): RciException = when {
        code == 401 || code == 403 -> RciException(
            "Неверный логин или пароль роутера.",
            RciFailure.CREDENTIALS,
        )
        authentication -> RciException("Не удалось войти на роутер (HTTP $code).", RciFailure.ROUTER)
        else -> RciException("Роутер отклонил запрос (HTTP $code).", RciFailure.ROUTER)
    }

    private fun protocolError() = RciException(
        "Роутер ответил без данных авторизации Keenetic. Проверьте адрес и HTTP-порт.",
        RciFailure.PROTOCOL,
    )

    private fun reachabilityError(cause: IOException) = RciException(
        "Не удалось связаться с роутером. Проверьте адрес, HTTP-порт и подключение.",
        RciFailure.REACHABILITY,
        cause,
    )
}

private const val MAX_RCI_RESPONSE_BYTES = 2L * 1024L * 1024L
