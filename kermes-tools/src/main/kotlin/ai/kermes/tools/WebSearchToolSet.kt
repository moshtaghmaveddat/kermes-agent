package ai.kermes.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Minimal web search via DuckDuckGo's HTML endpoint — no API key needed.
 *
 * MVP-grade: extracts result titles and URLs from the HTML. For richer
 * structured search, Phase 2 should swap this for a Firecrawl / Brave /
 * SearXNG provider behind the same `web_search` tool name.
 */
@LLMDescription("Web search — query DuckDuckGo and get back a short list of result titles and URLs.")
class WebSearchToolSet(
    private val maxResults: Int = 5,
    private val timeout: Duration = Duration.ofSeconds(10),
) : ToolSet {

    private val log = LoggerFactory.getLogger(WebSearchToolSet::class.java)
    private val http = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Tool
    @LLMDescription(
        "Search the web with a natural-language query. Returns up to ~5 results as " +
        "`<title> — <url>` lines. Use to find authoritative pages; follow up with a fetcher " +
        "tool if you need the full content of a result."
    )
    fun web_search(
        @LLMDescription("Search query, plain language.") query: String,
    ): String = runBlocking {
        try {
            val url = "https://duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
            val req = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", "Kermes/0.1 (+https://github.com/codaze/kermes)")
                .GET()
                .build()

            val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                return@runBlocking "ERROR: HTTP ${resp.statusCode()}"
            }
            parseResults(resp.body()).take(maxResults).joinToString("\n").ifBlank { "No results." }
        } catch (e: Exception) {
            log.warn("web_search failed: {}", e.message)
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun parseResults(html: String): List<String> {
        // DuckDuckGo HTML uses `<a class="result__a" href="...">title</a>`.
        val re = Regex(
            """<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        return re.findAll(html).map { match ->
            val href = match.groupValues[1]
            val title = match.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
            val cleanHref = if (href.startsWith("//duckduckgo.com/l/?uddg=")) {
                // DDG wraps redirects; extract the real URL
                val q = href.substringAfter("uddg=").substringBefore("&")
                java.net.URLDecoder.decode(q, StandardCharsets.UTF_8)
            } else href
            "$title — $cleanHref"
        }.toList()
    }
}
