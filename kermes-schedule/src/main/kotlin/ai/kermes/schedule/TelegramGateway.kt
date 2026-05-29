package ai.kermes.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * **Inbound** Telegram gateway — the other half of [TelegramSink].
 *
 * Long-polls the Bot API `getUpdates` endpoint and, for each text message from
 * an allow-listed chat, calls [onMessage] and posts its return value back via
 * `sendMessage`. This is what lets you *drive the agent from Telegram*; the
 * sink only *pushes* scheduled-task results outward.
 *
 * Runs until its coroutine is cancelled. Network I/O is dispatched to
 * [Dispatchers.IO] (the blocking JDK HttpClient must not sit on a compute
 * thread during the 30s long-poll). Transient poll failures are retried.
 *
 * Security: [allowedChatIds] is an allow-list. If non-empty, messages from any
 * other chat are refused. An empty set means "any chat" — only sensible for a
 * throwaway/private bot.
 */
class TelegramGateway(
    private val botToken: String,
    private val allowedChatIds: Set<Long>,
    private val onMessage: suspend (chatId: Long, text: String) -> String,
    private val pollSeconds: Long = 30,
) {
    private val log = LoggerFactory.getLogger(TelegramGateway::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    // HTTP/1.1: some networks reset the HTTP/2 ALPN handshake to api.telegram.org
    // ("Remote host terminated the handshake"); 1.1 is more robust through proxies.
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(20))
        .build()
    private val api = "https://api.telegram.org/bot$botToken"

    /**
     * The bot's @username (for logging), or null if unreachable after [attempts]
     * tries. Retried because the TLS handshake to api.telegram.org is flaky on
     * some networks — one failure doesn't mean the token is bad.
     */
    suspend fun whoAmI(attempts: Int = 5): String? {
        repeat(attempts) { i ->
            runCatching {
                json.parseToJsonElement(httpGet("$api/getMe", Duration.ofSeconds(15)))
                    .jsonObject["result"]?.jsonObject?.get("username")?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.let { return it }
            if (i < attempts - 1) delay(1500)
        }
        return null
    }

    /** Long-poll loop. Suspends until the surrounding coroutine is cancelled. */
    suspend fun run() {
        var offset = 0L
        log.info(
            "telegram gateway polling (allow-list: {})",
            if (allowedChatIds.isEmpty()) "ANY" else allowedChatIds.joinToString(),
        )
        while (currentCoroutineContext().isActive) {
            try {
                val url = "$api/getUpdates?timeout=$pollSeconds&offset=$offset" +
                    "&allowed_updates=%5B%22message%22%5D" // ["message"]
                val body = httpGet(url, Duration.ofSeconds(pollSeconds + 15))
                val result = json.parseToJsonElement(body).jsonObject["result"]?.jsonArray ?: continue

                for (upd in result) {
                    val o = upd.jsonObject
                    val updateId = o["update_id"]?.jsonPrimitive?.longOrNull ?: continue
                    offset = maxOf(offset, updateId + 1) // ack: never re-fetch this update
                    val msg = o["message"]?.jsonObject ?: continue
                    val chatId = msg["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull ?: continue
                    val text = msg["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (text.isEmpty()) continue

                    if (allowedChatIds.isNotEmpty() && chatId !in allowedChatIds) {
                        log.warn("ignoring message from non-allow-listed chat {}", chatId)
                        runCatching { sendMessage(chatId, "⛔ This Kermes only responds to its owner.") }
                        continue
                    }

                    log.info("telegram in <- chat {}: {}", chatId, text.take(80))
                    runCatching { sendChatAction(chatId, "typing") }
                    val reply = try {
                        onMessage(chatId, text)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("agent run for telegram chat {} failed", chatId, e)
                        "⚠️ ${e.message ?: e.javaClass.simpleName}"
                    }
                    runCatching { sendMessage(chatId, reply.ifBlank { "(no response)" }) }
                        .onFailure { log.warn("telegram reply to {} failed: {}", chatId, it.message) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("telegram poll error: {} — retrying in 3s", e.message)
                delay(3000)
            }
        }
    }

    suspend fun sendMessage(chatId: Long, text: String) {
        val body = if (text.length > 4000) text.take(4000) + "\n…[truncated]" else text
        httpPost("$api/sendMessage", "chat_id=$chatId&text=${enc(body)}&disable_web_page_preview=true")
    }

    private suspend fun sendChatAction(chatId: Long, action: String) {
        httpPost("$api/sendChatAction", "chat_id=$chatId&action=${enc(action)}")
    }

    private suspend fun httpGet(url: String, timeout: Duration): String = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout).GET().build()
        http.send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

    private suspend fun httpPost(url: String, form: String): String = withContext(Dispatchers.IO) {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        http.send(req, HttpResponse.BodyHandlers.ofString()).body()
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
