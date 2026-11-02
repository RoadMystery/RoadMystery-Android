package dev.voiceagent.llm

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

data class ProxyRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)

/**
 * A tiny HTTP server that plays the LLM proxy in tests. Like the Python dev proxy it answers with
 * HTTP/1.0 and `Connection: close`, so the event stream simply ends when the socket closes.
 * (Android unit tests compile against android.jar, which has no com.sun.net.httpserver.)
 */
class TestProxyServer(private val respond: (ProxyRequest, OutputStream) -> Unit) : Closeable {
    private val socket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    val url = "http://127.0.0.1:${socket.localPort}"
    val requests = LinkedBlockingQueue<ProxyRequest>()

    init {
        thread(isDaemon = true, name = "test-proxy") {
            while (true) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    return@thread // closed
                }
                thread(isDaemon = true) { client.use(::handle) }
            }
        }
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream().buffered()
        val requestLine = readLine(input).split(" ")
        val headers = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(":").trim().lowercase() to it.substringAfter(":").trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
        var read = 0
        while (read < body.size) {
            val count = input.read(body, read, body.size - read)
            if (count < 0) break
            read += count
        }
        val request = ProxyRequest(requestLine[0], requestLine[1], headers, String(body, Charsets.UTF_8))
        requests.add(request)
        try {
            respond(request, client.getOutputStream())
        } catch (e: SocketException) {
            // The client hung up first, which some tests do on purpose.
        }
    }

    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0 || byte == '\n'.code) break
            if (byte != '\r'.code) bytes.write(byte)
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    override fun close() {
        socket.close()
    }
}

fun OutputStream.sseHeaders() {
    write("HTTP/1.0 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\n\r\n".toByteArray())
    flush()
}

fun OutputStream.sse(name: String, data: String) {
    write("event: $name\ndata: $data\n\n".toByteArray(Charsets.UTF_8))
    flush()
}

fun OutputStream.json(status: Int, reason: String, body: String) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    write("HTTP/1.0 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
    write(bytes)
    flush()
}
