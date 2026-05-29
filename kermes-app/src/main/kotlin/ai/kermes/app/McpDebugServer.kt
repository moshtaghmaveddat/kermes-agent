package ai.kermes.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A **read-only** MCP server exposing Kermes runtime state for debugging.
 *
 * Implements the MCP "Streamable HTTP" transport (a single POST endpoint that
 * returns a plain JSON-RPC response — no SSE needed since we never push
 * server-initiated messages). Claude Code attaches with:
 *
 *     { "type": "http", "url": "http://127.0.0.1:8765/mcp" }
 *
 * Everything is backed by files under ~/.kermes (log, config, skills, memory,
 * inbox) so this can run as its own process and observe a *separately running*
 * Kermes (e.g. the interactive TUI you're testing) — they share the same home.
 *
 * Bound to loopback only. Read-only: no tool can mutate state, run the agent,
 * or execute shell — so localhost exposure is low-risk. The API key and bot
 * token are redacted in `read_config`.
 */
object McpDebugServer {

    private val log = LoggerFactory.getLogger(McpDebugServer::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** MCP protocol revision we implement; we echo the client's if it sends one. */
    private const val PROTOCOL_VERSION = "2025-06-18"

    /**
     * Start the server **non-blocking** and return the handle (caller stops it).
     * Used by `kermes serve` to host the debug endpoint alongside the gateway.
     */
    fun start(port: Int): HttpServer {
        val home = kermesHome()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        server.createContext("/mcp") { ex -> handle(ex, home) }
        server.createContext("/") { ex -> respond(ex, 200, "text/plain", "Kermes MCP debug server. POST JSON-RPC to /mcp\n") }
        server.executor = Executors.newFixedThreadPool(4)
        server.start()
        log.info("MCP debug server started on http://127.0.0.1:{}/mcp", port)
        return server
    }

    /** Start the server and block until the process is interrupted (Ctrl-C). */
    fun serve(port: Int) {
        val home = kermesHome()
        val server = start(port)

        val url = "http://127.0.0.1:$port/mcp"
        println(Banner.render(KERMES_VERSION, model = "mcp-debug", cwd = home.toString(), skills = emptyList()))
        println("  ${Banner.GOLD}MCP debug server${Banner.RESET} listening on ${Banner.WHITE}$url${Banner.RESET}  ${Banner.DIM}(read-only, loopback)${Banner.RESET}")
        println()
        println("  Add to Claude Code (.mcp.json in your project, or ~/.claude.json):")
        println(
            """
              ${Banner.DIM}{
                "mcpServers": {
                  "kermes-debug": { "type": "http", "url": "$url" }
                }
              }${Banner.RESET}
            """.trimIndent()
        )
        println("\n  Tools: status · tail_log · recent_errors · read_config · list_skills · list_inbox · read_memory")
        println("  ${Banner.DIM}Ctrl-C to stop.${Banner.RESET}\n")

        val latch = CountDownLatch(1)
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { server.stop(0) }
            latch.countDown()
        })
        latch.await()
    }

    // ---- HTTP / JSON-RPC plumbing ---------------------------------------

    private fun handle(ex: HttpExchange, home: Path) {
        try {
            if (ex.requestMethod.equals("GET", ignoreCase = true)) {
                // We don't open a server→client SSE stream; tell the client to POST.
                respond(ex, 405, "text/plain", "Use POST for MCP messages.")
                return
            }
            if (!ex.requestMethod.equals("POST", ignoreCase = true)) {
                respond(ex, 405, "text/plain", "Method not allowed.")
                return
            }
            val body = ex.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (parsed == null) {
                respond(ex, 200, "application/json", json.encodeToString(JsonElement.serializer(), rpcError(JsonNull, -32700, "Parse error")))
                return
            }

            when (parsed) {
                is JsonArray -> {
                    val responses = parsed.mapNotNull { dispatch(it.jsonObject, home) }
                    if (responses.isEmpty()) respond(ex, 202, "application/json", "")
                    else respond(ex, 200, "application/json", json.encodeToString(JsonArray.serializer(), JsonArray(responses)))
                }
                is JsonObject -> {
                    val resp = dispatch(parsed, home)
                    if (resp == null) respond(ex, 202, "application/json", "")
                    else respond(ex, 200, "application/json", json.encodeToString(JsonElement.serializer(), resp))
                }
                else -> respond(ex, 200, "application/json", json.encodeToString(JsonElement.serializer(), rpcError(JsonNull, -32600, "Invalid request")))
            }
        } catch (e: Exception) {
            log.warn("mcp request failed", e)
            runCatching { respond(ex, 500, "text/plain", "internal error: ${e.message}") }
        }
    }

    /** Returns the JSON-RPC response object, or null for a notification (no id). */
    private fun dispatch(msg: JsonObject, home: Path): JsonElement? {
        val id = msg["id"]
        val method = msg["method"]?.jsonPrimitive?.content
        val isNotification = id == null || id is JsonNull

        // Notifications never get a response.
        if (method != null && method.startsWith("notifications/")) return null

        if (method == null) {
            return if (isNotification) null else rpcError(id!!, -32600, "Invalid request: missing method")
        }

        val result: JsonElement = when (method) {
            "initialize" -> {
                val clientProto = msg["params"]?.jsonObject?.get("protocolVersion")?.jsonPrimitive?.content
                buildJsonObject {
                    put("protocolVersion", clientProto ?: PROTOCOL_VERSION)
                    putJsonObject("capabilities") { putJsonObject("tools") {} }
                    putJsonObject("serverInfo") {
                        put("name", "kermes-debug")
                        put("version", KERMES_VERSION)
                    }
                    put("instructions", "Read-only debug view of a running Kermes. Use tail_log/recent_errors/status to diagnose.")
                }
            }
            "ping" -> buildJsonObject {}
            "tools/list" -> buildJsonObject { put("tools", toolsList()) }
            "resources/list" -> buildJsonObject { putJsonArray("resources") {} }
            "prompts/list" -> buildJsonObject { putJsonArray("prompts") {} }
            "tools/call" -> callTool(msg["params"]?.jsonObject, home)
            else -> return if (isNotification) null else rpcError(id!!, -32601, "Method not found: $method")
        }

        if (isNotification) return null
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id!!)
            put("result", result)
        }
    }

    private fun rpcError(id: JsonElement, code: Int, message: String): JsonElement = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        putJsonObject("error") { put("code", code); put("message", message) }
    }

    /** Wrap text as an MCP tool result. */
    private fun textResult(text: String, isError: Boolean = false): JsonElement = buildJsonObject {
        putJsonArray("content") {
            add(buildJsonObject { put("type", "text"); put("text", text) })
        }
        put("isError", isError)
    }

    // ---- Tool registry ---------------------------------------------------

    private fun obj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(build)

    private fun tool(name: String, desc: String, props: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("name", name)
        put("description", desc)
        putJsonObject("inputSchema") {
            put("type", "object")
            put("properties", props)
        }
    }

    private fun toolsList(): JsonArray = buildJsonArray {
        add(tool("status", "Kermes version, configured model/provider/base URL, key presence, skill & schedule counts, and log size. No network calls."))
        add(tool("tail_log", "Tail the last N lines of ~/.kermes/kermes.log, optionally filtered to lines containing a substring.", obj {
            putJsonObject("lines") { put("type", "integer"); put("description", "How many trailing lines (default 120, max 2000).") }
            putJsonObject("contains") { put("type", "string"); put("description", "Only return lines containing this substring (case-insensitive).") }
        }))
        add(tool("recent_errors", "The last N ERROR/WARN log lines (message lines only), newest last.", obj {
            putJsonObject("count") { put("type", "integer"); put("description", "How many error/warn lines (default 15, max 200).") }
        }))
        add(tool("read_config", "Contents of ~/.kermes/config with secrets (API key, bot token) redacted."))
        add(tool("list_skills", "Skill folders discovered under the skill roots (name + whether SKILL.md is present)."))
        add(tool("list_inbox", "Files in ~/.kermes/inbox (scheduled-task outputs), newest last, with sizes."))
        add(tool("read_memory", "Summarize the memory store: list files under ~/.kermes/memory with sizes, and dump small JSON files."))
    }

    private fun callTool(params: JsonObject?, home: Path): JsonElement {
        val name = params?.get("name")?.jsonPrimitive?.content
            ?: return textResult("missing tool name", isError = true)
        val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        return runCatching {
            when (name) {
                "status" -> textResult(statusText(home))
                "tail_log" -> {
                    val n = (args["lines"]?.jsonPrimitive?.intOrNull() ?: 120).coerceIn(1, 2000)
                    val contains = args["contains"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    textResult(tailLogText(home, n, contains))
                }
                "recent_errors" -> {
                    val n = (args["count"]?.jsonPrimitive?.intOrNull() ?: 15).coerceIn(1, 200)
                    textResult(recentErrorsText(home, n))
                }
                "read_config" -> textResult(readConfigRedacted(home))
                "list_skills" -> textResult(listSkillsText(home))
                "list_inbox" -> textResult(listInboxText(home))
                "read_memory" -> textResult(readMemoryText(home))
                else -> textResult("unknown tool: $name", isError = true)
            }
        }.getOrElse { e -> textResult("tool '$name' failed: ${e.message}", isError = true) }
    }

    private fun JsonPrimitive.intOrNull(): Int? = runCatching { this.int }.getOrNull()

    // ---- Tool implementations (pure file reads) --------------------------

    private fun logFile(home: Path): Path = home.resolve("kermes.log")

    private fun statusText(home: Path): String {
        val model = ConfigSource.get("KERMES_MODEL") ?: "(unset)"
        val baseUrl = ConfigSource.get("KERMES_BASE_URL") ?: "(unset)"
        val provider = when {
            baseUrl.contains("api.openai.com") -> "openai"
            baseUrl.contains("openrouter.ai") -> "openrouter"
            baseUrl.contains("11434") || baseUrl.contains("ollama") -> "ollama"
            baseUrl == "(unset)" -> "(unset)"
            else -> "custom (chat-completions)"
        }
        val keySet = sequenceOf("KERMES_API_KEY", "OPENROUTER_API_KEY", "OPENAI_API_KEY")
            .any { !ConfigSource.get(it).isNullOrBlank() }
        val tgSet = !ConfigSource.get("KERMES_TELEGRAM_BOT_TOKEN").isNullOrBlank()
        val skills = countSkills(home)
        val schedules = home.resolve("schedules.yaml").let { if (it.exists()) "present" else "none" }
        val lf = logFile(home)
        val logInfo = if (lf.exists()) "${lf.fileSize() / 1024} KB" else "missing"
        return buildString {
            appendLine("Kermes $KERMES_VERSION — debug status")
            appendLine("  home:         $home ${if (home.exists()) "✓" else "✗"}")
            appendLine("  provider:     $provider")
            appendLine("  model:        $model")
            appendLine("  base URL:     $baseUrl")
            appendLine("  API key:      ${if (keySet) "set" else "MISSING"}")
            appendLine("  telegram:     ${if (tgSet) "configured (outbound delivery only — no inbound gateway yet)" else "not configured"}")
            appendLine("  skills found: $skills")
            appendLine("  schedules:    $schedules")
            append("  log file:     $logInfo")
        }
    }

    private fun tailLogText(home: Path, lines: Int, contains: String?): String {
        val lf = logFile(home)
        if (!lf.exists()) return "(no log file at $lf — Kermes hasn't run yet, or KERMES_HOME differs)"
        // Filter first, then take the last N — so a filtered query returns the
        // most recent *matching* lines, not matches within the last N lines.
        var tail = tail(lf, 1_000_000)
        if (contains != null) tail = tail.filter { it.contains(contains, ignoreCase = true) }
        tail = tail.takeLast(lines)
        return if (tail.isEmpty()) "(no matching log lines)" else tail.joinToString("\n")
    }

    private fun recentErrorsText(home: Path, count: Int): String {
        val lf = logFile(home)
        if (!lf.exists()) return "(no log file at $lf)"
        val hits = tail(lf, 1_000_000)
            .filter { Regex("\\b(ERROR|WARN)\\b").containsMatchIn(it.take(60)) }
            .takeLast(count)
        return if (hits.isEmpty()) "(no recent ERROR/WARN lines)" else hits.joinToString("\n")
    }

    private fun readConfigRedacted(home: Path): String {
        val cfg = home.resolve("config")
        if (!cfg.exists()) return "(no config at $cfg — run `kermes setup`)"
        val secrets = setOf("KERMES_API_KEY", "OPENROUTER_API_KEY", "OPENAI_API_KEY", "KERMES_TELEGRAM_BOT_TOKEN")
        return cfg.readText().lineSequence().joinToString("\n") { line ->
            val k = line.substringBefore("=").trim()
            if (k in secrets && "=" in line) "$k=${redact(line.substringAfter("=").trim())}" else line
        }
    }

    private fun redact(v: String): String =
        if (v.length <= 4) "***" else v.take(2) + "***" + v.takeLast(2) + " (redacted)"

    private fun listSkillsText(home: Path): String {
        val roots = buildList {
            val proj = java.nio.file.Paths.get("").toAbsolutePath().resolve(".kermes/skills")
            if (proj.exists()) add(proj)
            add(home.resolve("skills"))
            System.getenv("KERMES_BUNDLED_SKILLS")?.let { add(java.nio.file.Paths.get(it)) }
        }
        val sb = StringBuilder()
        for (root in roots) {
            if (!root.exists() || !root.isDirectory()) continue
            sb.appendLine("$root:")
            val entries = runCatching {
                java.nio.file.Files.list(root).use { s -> s.filter { it.isDirectory() }.sorted().toList() }
            }.getOrDefault(emptyList())
            if (entries.isEmpty()) sb.appendLine("  (empty)")
            entries.forEach { d ->
                val hasSkill = d.resolve("SKILL.md").exists()
                sb.appendLine("  - ${d.name} ${if (hasSkill) "✓ SKILL.md" else "✗ no SKILL.md"}")
            }
        }
        return sb.toString().ifBlank { "(no skill roots found)" }
    }

    private fun listInboxText(home: Path): String {
        val inbox = home.resolve("inbox")
        if (!inbox.exists()) return "(no inbox at $inbox)"
        val files = runCatching {
            java.nio.file.Files.list(inbox).use { it.filter { p -> p.isRegularFile() }.sorted().toList() }
        }.getOrDefault(emptyList())
        if (files.isEmpty()) return "(inbox empty)"
        return files.joinToString("\n") { "  - ${it.name}  (${it.fileSize()} B)" }
    }

    private fun readMemoryText(home: Path): String {
        val mem = home.resolve("memory")
        if (!mem.exists()) return "(no memory dir at $mem)"
        val files = runCatching {
            java.nio.file.Files.walk(mem).use { s -> s.filter { it.isRegularFile() }.sorted().toList() }
        }.getOrDefault(emptyList())
        if (files.isEmpty()) return "(memory empty)"
        val sb = StringBuilder()
        files.forEach { f ->
            val size = f.fileSize()
            sb.appendLine("── ${mem.relativize(f)}  (${size} B)")
            if (size in 1..4000 && f.name.endsWith(".json")) sb.appendLine(f.readText().trim())
        }
        return sb.toString()
    }

    private fun countSkills(home: Path): Int {
        val roots = buildList {
            val proj = java.nio.file.Paths.get("").toAbsolutePath().resolve(".kermes/skills")
            if (proj.exists()) add(proj)
            add(home.resolve("skills"))
            System.getenv("KERMES_BUNDLED_SKILLS")?.let { add(java.nio.file.Paths.get(it)) }
        }
        return roots.filter { it.exists() && it.isDirectory() }.sumOf { root ->
            runCatching {
                java.nio.file.Files.list(root).use { s ->
                    s.filter { it.isDirectory() && it.resolve("SKILL.md").exists() }.count().toInt()
                }
            }.getOrDefault(0)
        }
    }

    /** Read the last [maxBytes] of a file and return its lines (dropping a leading partial line). */
    private fun tail(path: Path, maxBytes: Long): List<String> {
        RandomAccessFile(path.toFile(), "r").use { raf ->
            val len = raf.length()
            val from = (len - maxBytes).coerceAtLeast(0)
            raf.seek(from)
            val bytes = ByteArray((len - from).toInt())
            raf.readFully(bytes)
            val text = String(bytes, StandardCharsets.UTF_8)
            val lines = text.split('\n')
            // If we started mid-file, the first line is probably partial — drop it.
            return if (from > 0 && lines.size > 1) lines.drop(1) else lines
        }
    }

    private fun respond(ex: HttpExchange, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        // status 202 with empty body must send -1 length (no body)
        if (bytes.isEmpty()) {
            ex.sendResponseHeaders(status, -1)
            ex.close()
            return
        }
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
