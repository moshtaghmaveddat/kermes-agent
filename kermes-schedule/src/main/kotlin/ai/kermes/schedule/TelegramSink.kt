package ai.kermes.schedule

import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Outbound Telegram delivery for scheduled-task results. Posts to the Bot API
 * `sendMessage` endpoint. Configure with a bot token (via @BotFather) and a
 * chat id. Inbound chat (driving the agent from Telegram) is a separate gateway
 * — not implemented here.
 */
class TelegramSink(
    private val botToken: String,
    private val chatId: String,
) : DeliverySink {

    private val log = LoggerFactory.getLogger(TelegramSink::class.java)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override suspend fun deliver(entry: ScheduleEntry, ranAt: Instant, output: String) {
        val header = "🕒 ${entry.id} (${entry.cron})\n\n"
        val body = (header + output).let { if (it.length > 4000) it.take(4000) + "\n…[truncated]" else it }

        val form = "chat_id=" + enc(chatId) + "&text=" + enc(body) + "&disable_web_page_preview=true"
        val req = HttpRequest.newBuilder()
            .uri(URI.create("https://api.telegram.org/bot$botToken/sendMessage"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) {
            log.warn("telegram delivery for '{}' failed: HTTP {} — {}", entry.id, resp.statusCode(), resp.body().take(200))
        } else {
            log.info("telegram delivery for '{}' ok", entry.id)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
