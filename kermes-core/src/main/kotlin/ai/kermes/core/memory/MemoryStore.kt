package ai.kermes.core.memory

import ai.kermes.core.vector.DocTag
import ai.kermes.core.vector.KermesDoc
import ai.kermes.core.vector.VectorStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Filesystem layout for the four file-based perspectives, plus episodes/.
 * Each `set*` / `remember*` operation writes the markdown file AND upserts
 * an embedding so the vector index stays consistent.
 */
class MemoryStore(private val root: Path, private val vectors: VectorStore) {

    private val lock = Mutex()

    val userPath = root.resolve("user.md")
    val prefsPath = root.resolve("preferences.md")
    val contextPath = root.resolve("context.md")
    val feedbackPath = root.resolve("feedback.md")
    val episodesDir = root.resolve("episodes")

    init {
        root.createDirectories()
        episodesDir.createDirectories()
    }

    // ---------- User identity (upsert by trait) ----------

    suspend fun rememberUser(trait: String, value: String): Result {
        lock.withLock {
            val map = parseKv(userPath)
            val before = map[trait]
            map[trait] = value
            writeKv(userPath, map)
            enforceSize(userPath, USER_MAX_BYTES)
            vectors.upsert(
                KermesDoc(
                    id = "user:$trait",
                    text = "$trait: $value",
                    tag = DocTag.UserFact,
                    attrs = mapOf("trait" to trait, "ts" to Instant.now().toString()),
                )
            )
            return if (before == null) Result.Created else Result.Updated
        }
    }

    suspend fun userFacts(): Map<String, String> = lock.withLock { parseKv(userPath).toMap() }

    // ---------- Preferences (upsert by key) ----------

    suspend fun setPreference(key: String, value: String): Result {
        lock.withLock {
            val map = parseKv(prefsPath)
            val before = map[key]
            map[key] = value
            writeKv(prefsPath, map)
            enforceSize(prefsPath, PREFS_MAX_BYTES)
            vectors.upsert(
                KermesDoc(
                    id = "pref:$key",
                    text = "$key: $value",
                    tag = DocTag.Preference,
                    attrs = mapOf("key" to key, "ts" to Instant.now().toString()),
                )
            )
            return if (before == null) Result.Created else Result.Updated
        }
    }

    suspend fun preferences(): Map<String, String> = lock.withLock { parseKv(prefsPath).toMap() }

    // ---------- Domain context (append within topic) ----------

    suspend fun rememberContext(topic: String, fact: String): Result {
        lock.withLock {
            val sections = parseSections(contextPath)
            val existing = sections.getOrPut(topic) { mutableListOf() }
            if (existing.any { it.trim() == fact.trim() }) return Result.Skipped
            existing.add(fact)
            writeSections(contextPath, sections)
            vectors.upsert(
                KermesDoc(
                    id = "ctx:${topic}:${existing.size}",
                    text = "[$topic] $fact",
                    tag = DocTag.Context,
                    attrs = mapOf("topic" to topic, "ts" to Instant.now().toString()),
                )
            )
            return Result.Created
        }
    }

    suspend fun updateContext(topic: String, newContent: List<String>): Result {
        lock.withLock {
            val sections = parseSections(contextPath)
            sections[topic] = newContent.toMutableList()
            writeSections(contextPath, sections)
            // Re-embed: delete the topic's old vectors, then re-add.
            // Simpler approach for MVP: just write new entries with a fresh suffix.
            newContent.forEachIndexed { idx, fact ->
                vectors.upsert(
                    KermesDoc(
                        id = "ctx:${topic}:rev${Instant.now().epochSecond}:$idx",
                        text = "[$topic] $fact",
                        tag = DocTag.Context,
                        attrs = mapOf("topic" to topic, "ts" to Instant.now().toString(), "rev" to "true"),
                    )
                )
            }
            return Result.Updated
        }
    }

    // ---------- Feedback (append) ----------

    suspend fun noteFeedback(observation: String, rule: String? = null): Result {
        lock.withLock {
            val now = Instant.now()
            val line = buildString {
                append("- [").append(now).append("] ")
                if (rule != null) append("**rule:** $rule — ")
                append(observation)
            }
            appendLine(feedbackPath, line)
            vectors.upsert(
                KermesDoc(
                    id = "feedback:${now.epochSecond}",
                    text = (rule?.let { "Rule: $it. " } ?: "") + "Observation: $observation",
                    tag = DocTag.Feedback,
                    attrs = mapOf("ts" to now.toString(), "rule" to (rule ?: "")),
                )
            )
            return Result.Created
        }
    }

    // ---------- Episodes (append-only, time-stamped) ----------

    suspend fun recordEpisode(summary: String, tags: List<String> = emptyList()): Result {
        lock.withLock {
            val now = Instant.now()
            val month = now.toString().substring(0, 7) // YYYY-MM
            val dir = episodesDir.resolve(month).also { it.createDirectories() }
            val id = "${now.epochSecond}"
            val file = dir.resolve("$id.md")
            file.writeText(
                buildString {
                    appendLine("---")
                    appendLine("ts: $now")
                    if (tags.isNotEmpty()) appendLine("tags: [${tags.joinToString(", ")}]")
                    appendLine("---")
                    appendLine()
                    appendLine(summary)
                },
                Charsets.UTF_8,
            )
            vectors.upsert(
                KermesDoc(
                    id = "episode:$id",
                    text = summary,
                    tag = DocTag.Episode,
                    attrs = mapOf("ts" to now.toString(), "tags" to tags.joinToString(",")),
                )
            )
            return Result.Created
        }
    }

    // ---------- Internals ----------

    private fun parseKv(path: Path): MutableMap<String, String> {
        if (!path.exists()) return mutableMapOf()
        val out = linkedMapOf<String, String>()
        var currentKey: String? = null
        val buf = StringBuilder()
        for (raw in path.readText(Charsets.UTF_8).lines()) {
            val line = raw.trimEnd()
            val match = KEY_HEADER.matchEntire(line)
            if (match != null) {
                if (currentKey != null) out[currentKey] = buf.toString().trim()
                currentKey = match.groupValues[1]
                buf.setLength(0)
            } else if (currentKey != null) {
                if (buf.isNotEmpty()) buf.append('\n')
                buf.append(line)
            }
        }
        if (currentKey != null) out[currentKey] = buf.toString().trim()
        return out
    }

    private fun writeKv(path: Path, map: Map<String, String>) {
        val content = buildString {
            map.forEach { (k, v) ->
                append("## ").appendLine(k)
                appendLine(v)
                appendLine()
            }
        }
        path.writeText(content, Charsets.UTF_8)
    }

    private fun parseSections(path: Path): MutableMap<String, MutableList<String>> {
        if (!path.exists()) return linkedMapOf()
        val out = linkedMapOf<String, MutableList<String>>()
        var currentTopic: String? = null
        for (raw in path.readText(Charsets.UTF_8).lines()) {
            val line = raw.trimEnd()
            val match = KEY_HEADER.matchEntire(line)
            if (match != null) {
                currentTopic = match.groupValues[1]
                out.getOrPut(currentTopic) { mutableListOf() }
            } else if (currentTopic != null && line.startsWith("- ")) {
                out[currentTopic]!!.add(line.removePrefix("- "))
            }
        }
        return out
    }

    private fun writeSections(path: Path, sections: Map<String, List<String>>) {
        val content = buildString {
            sections.forEach { (topic, facts) ->
                if (facts.isEmpty()) return@forEach
                append("## ").appendLine(topic)
                facts.forEach { appendLine("- $it") }
                appendLine()
            }
        }
        path.writeText(content, Charsets.UTF_8)
    }

    private fun appendLine(path: Path, line: String) {
        val existing = if (path.exists()) path.readText(Charsets.UTF_8) else ""
        val sep = if (existing.isEmpty() || existing.endsWith("\n")) "" else "\n"
        path.writeText(existing + sep + line + "\n", Charsets.UTF_8)
    }

    private fun enforceSize(path: Path, maxBytes: Long) {
        val size = Files.size(path)
        if (size > maxBytes)
            throw MemoryOverflowException(
                "$path is $size bytes; cap is $maxBytes. Condense via update first."
            )
    }

    companion object {
        private const val USER_MAX_BYTES = 2 * 1024L
        private const val PREFS_MAX_BYTES = 4 * 1024L
        private val KEY_HEADER = Regex("""^##\s+(.+)$""")
    }

    enum class Result { Created, Updated, Skipped }
}

class MemoryOverflowException(msg: String) : RuntimeException(msg)
