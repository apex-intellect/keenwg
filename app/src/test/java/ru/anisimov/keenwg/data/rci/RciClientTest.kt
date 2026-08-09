package ru.anisimov.keenwg.data.rci

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.anisimov.keenwg.domain.model.ServerSettings
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class RciClientTest {
    @Test fun auth_failure_is_classified_and_localized() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(401)
                .addHeader("X-NDM-Realm", "realm")
                .addHeader("X-NDM-Challenge", "challenge"),
        )
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()

        val error = runCatching {
            RciClient().authenticate(ServerSettings(host = server.hostName, port = server.port, login = "admin", password = "bad"))
        }.exceptionOrNull() as RciException

        assertEquals(RciFailure.CREDENTIALS, error.failure)
        assertEquals("Неверный логин или пароль роутера.", error.message)
        server.shutdown()
    }

    @Test fun wrong_http_service_is_classified_and_localized() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()

        val error = runCatching {
            RciClient().authenticate(ServerSettings(host = server.hostName, port = server.port))
        }.exceptionOrNull() as RciException

        assertEquals(RciFailure.PROTOCOL, error.failure)
        assertTrue(error.message.orEmpty().startsWith("Роутер ответил без данных авторизации Keenetic"))
        server.shutdown()
    }

    @Test fun unreachable_router_is_classified_and_localized() = runTest {
        val server = MockWebServer()
        server.start()
        val settings = ServerSettings(host = server.hostName, port = server.port)
        server.shutdown()

        val error = runCatching { RciClient().get(settings, "show/version") }.exceptionOrNull() as RciException

        assertEquals(RciFailure.REACHABILITY, error.failure)
        assertEquals(
            "Не удалось связаться с роутером. Проверьте адрес, HTTP-порт и подключение.",
            error.message,
        )
    }
    @Test fun auth_flow_sends_correct_hash() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setResponseCode(401)
                .addHeader("X-NDM-Realm", "test-realm")
                .addHeader("X-NDM-Challenge", "CHALLENGE123"),
        )
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()

        val s = ServerSettings(host = server.hostName, port = server.port, login = "admin", password = "secret")
        RciClient().authenticate(s)

        server.takeRequest() // GET /auth
        val post = server.takeRequest() // POST /auth
        assertTrue(
            post.body.readUtf8()
                .contains("26d4546b6381a1300cfee2b219c8f99f30b014a599e766e3aa7315ee1eba67d2"),
        )
        server.shutdown()
    }

    @Test fun auth_login_is_encoded_as_json_data_not_structure() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).addHeader("X-NDM-Realm", "realm").addHeader("X-NDM-Challenge", "challenge"))
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()
        val login = "admin\"},\"injected\":true,\"x\":\""

        RciClient().authenticate(ServerSettings(host = server.hostName, port = server.port, login = login, password = "secret"))
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

        assertEquals(login, body.getValue("login").jsonPrimitive.content)
        assertEquals(setOf("login", "password"), body.keys)
        server.shutdown()
    }

    @Test fun concurrent_401_responses_share_one_authentication_refresh() = runTest {
        val server = MockWebServer()
        val authPosts = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth" && request.method == "GET" -> MockResponse().setResponseCode(401)
                    .addHeader("X-NDM-Realm", "realm").addHeader("X-NDM-Challenge", "challenge")
                request.path == "/auth" && request.method == "POST" -> {
                    authPosts.incrementAndGet()
                    MockResponse().setResponseCode(200).addHeader("Set-Cookie", "session=ok; Path=/")
                }
                request.path?.startsWith("/rci/") == true && request.getHeader("Cookie")?.contains("session=ok") == true ->
                    MockResponse().setResponseCode(200).setBody("{}")
                else -> MockResponse().setResponseCode(401)
            }
        }
        server.start()
        val settings = ServerSettings(host = server.hostName, port = server.port, login = "admin", password = "secret")
        val client = RciClient()

        awaitAll(
            async { client.get(settings, "show/version") },
            async { client.get(settings, "show/version") },
        )

        assertEquals(1, authPosts.get())
        server.shutdown()
    }

    @Test fun sessions_are_partitioned_by_login_without_cookie_leakage() = runTest {
        val server = MockWebServer()
        val leaked = AtomicBoolean(false)
        var authenticatingLogin = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth" && request.method == "GET" -> MockResponse().setResponseCode(401)
                    .addHeader("X-NDM-Realm", "realm").addHeader("X-NDM-Challenge", "challenge")
                request.path == "/auth" && request.method == "POST" -> {
                    authenticatingLogin = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("login").jsonPrimitive.content
                    MockResponse().setResponseCode(200).addHeader("Set-Cookie", "session=$authenticatingLogin; Path=/")
                }
                request.path == "/rci/show/alice" && request.getHeader("Cookie")?.contains("session=alice") == true -> MockResponse().setResponseCode(200).setBody("{}")
                request.path == "/rci/show/bob" && request.getHeader("Cookie")?.contains("session=bob") == true -> MockResponse().setResponseCode(200).setBody("{}")
                request.path == "/rci/show/bob" -> {
                    if (request.getHeader("Cookie")?.contains("session=alice") == true) leaked.set(true)
                    MockResponse().setResponseCode(401)
                }
                else -> MockResponse().setResponseCode(401)
            }
        }
        server.start()
        val client = RciClient()
        val base = ServerSettings(host = server.hostName, port = server.port, password = "secret")

        client.get(base.copy(login = "alice"), "show/alice")
        client.get(base.copy(login = "bob"), "show/bob")

        assertFalse(leaked.get())
        server.shutdown()
    }

    @Test fun fresh_auth_challenge_never_sends_a_stale_session_cookie() = runTest {
        val server = MockWebServer()
        val authGets = AtomicInteger()
        val staleCookieSeen = AtomicBoolean(false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/auth" && request.method == "GET" -> {
                    if (authGets.incrementAndGet() > 1 && request.getHeader("Cookie") != null) {
                        staleCookieSeen.set(true)
                    }
                    MockResponse().setResponseCode(401)
                        .addHeader("X-NDM-Realm", "realm")
                        .addHeader("X-NDM-Challenge", "challenge-${authGets.get()}")
                        .addHeader("Set-Cookie", "challenge=${authGets.get()}; Path=/")
                }
                request.path == "/auth" && request.method == "POST" ->
                    MockResponse().setResponseCode(200).addHeader("Set-Cookie", "session=stale; Path=/")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val settings = ServerSettings(host = server.hostName, port = server.port, login = "admin", password = "secret")
        val client = RciClient()

        client.authenticate(settings)
        client.authenticate(settings)

        assertFalse(staleCookieSeen.get())
        server.shutdown()
    }

    @Test fun oversized_rci_response_is_rejected_before_string_allocation() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(2 * 1024 * 1024 + 1)))
        server.start()

        val error = runCatching {
            RciClient().get(ServerSettings(host = server.hostName, port = server.port), "show/running-config")
        }.exceptionOrNull()

        assertTrue(error is RciException)
        assertTrue(error?.message?.contains("слишком велик") == true)
        server.shutdown()
    }
}
