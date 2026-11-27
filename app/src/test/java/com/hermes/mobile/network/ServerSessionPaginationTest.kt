package com.hermes.mobile.network

import android.content.Context
import android.content.SharedPreferences
import com.hermes.mobile.data.model.ServerConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Regression: a page failure in the middle of the /api/sessions walk must
 * abort the walk and report incomplete. Continuing to later offsets leaves a
 * hole in the list; if a later page then reports has_more=false the caller
 * would treat the list as COMPLETE and purge live local sessions that fell
 * into the hole.
 */
class ServerSessionPaginationTest {

    private var server: FakeHttpServer? = null

    @After
    fun tearDown() {
        server?.close()
    }

    private fun mockContext(): Context {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        return context
    }

    private fun passThroughAuth(): AuthInterceptor {
        val auth = mockk<AuthInterceptor>()
        every { auth.intercept(any()) } answers {
            val chain = firstArg<Interceptor.Chain>()
            chain.proceed(chain.request())
        }
        return auth
    }

    private fun newService(baseUrl: String): HermesApiService {
        val api = HermesApiService(mockContext(), passThroughAuth())
        val field = HermesApiService::class.java.getDeclaredField("config")
        field.isAccessible = true
        field.set(api, ServerConfig(baseUrl = baseUrl))
        return api
    }

    @Test
    fun `failed first page aborts walk and reports incomplete`() = runBlocking {
        val srv = FakeHttpServer { path ->
            if (path.contains("offset=0")) 500 to "boom"
            // A later page claiming completion must NOT override the hole.
            else 200 to """{"data":[{"id":"s1"}],"has_more":false}"""
        }
        server = srv
        val api = newService("http://127.0.0.1:${srv.port}")
        val (sessions, complete) = api.fetchServerSessions()

        assertFalse("partial list must never be reported complete", complete)
        assertTrue("walk must abort before the skipped page is papered over", sessions.isEmpty())
    }

    @Test
    fun `clean final page reports complete`() = runBlocking {
        val srv = FakeHttpServer {
            // JSON null (not "null") must fall back to preview, then to blank.
            200 to """{"data":[{"id":"s1","title":null,"preview":"One"}],"has_more":false}"""
        }
        server = srv
        val api = newService("http://127.0.0.1:${srv.port}")
        val (sessions, complete) = api.fetchServerSessions()

        assertTrue(complete)
        assertEquals(1, sessions.size)
        assertEquals("s1", sessions.first().id)
        assertEquals("One", sessions.first().title)
    }

    /** Minimal HTTP/1.1 server over ServerSocket: android.jar has no
     * com.sun.net.httpserver on the unit-test classpath. One connection per
     * request (Connection: close), which is all OkHttp needs. */
    private class FakeHttpServer(private val handler: (path: String) -> Pair<Int, String>) {
        private val socket = ServerSocket(0)
        private val running = AtomicBoolean(true)
        val port: Int get() = socket.localPort

        private val thread = Thread {
            while (running.get()) {
                try {
                    val client = socket.accept()
                    serve(client)
                } catch (_: Exception) {
                    if (running.get()) continue else break
                }
            }
        }.apply { isDaemon = true; start() }

        private fun serve(client: Socket) {
            client.use { c ->
                val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1).orEmpty()
                val (code, body) = handler(path)
                val bytes = body.toByteArray()
                val reason = if (code == 200) "OK" else "Error"
                c.getOutputStream().write(
                    ("HTTP/1.1 $code $reason\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                c.getOutputStream().write(bytes)
            }
        }

        fun close() {
            running.set(false)
            try { socket.close() } catch (_: Exception) { }
            thread.interrupt()
        }
    }
}
