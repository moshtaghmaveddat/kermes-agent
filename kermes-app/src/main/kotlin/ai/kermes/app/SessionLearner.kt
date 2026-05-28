package ai.kermes.app

import ai.kermes.core.memory.MemoryStore
import org.slf4j.LoggerFactory

/**
 * Session-end self-learning. After a session ends, the accumulated transcript
 * is handed to an extractor (a tool-less LLM call) that returns durable facts
 * in a simple line format; those are routed to the six memory perspectives.
 *
 * This deliberately avoids Koog's graph-coupled FactRetrievalHistoryCompression
 * strategy: a plain text-format extraction works on ANY provider (honoring the
 * provider-diversity principle) and keeps the proven basic-agent tool loop
 * untouched. The extractor is just another `AIAgent.run` with no tools.
 *
 * Output contract (one fact per line; the extractor is told this exactly):
 *
 *     USER|<trait>|<value>
 *     PREF|<key>|<value>
 *     CONTEXT|<topic>|<fact>
 *     EPISODE||<summary>
 *     FEEDBACK||<observation>
 *     NONE
 */
class SessionLearner(
    private val memory: MemoryStore,
    /** Runs a tool-less extraction prompt and returns the raw model text. */
    private val extract: suspend (transcript: String) -> String,
) {
    private val log = LoggerFactory.getLogger(SessionLearner::class.java)

    companion object {
        /** Instruction prompt for the extractor agent (no tools). */
        const val SYSTEM_PROMPT: String = """
You extract durable, reusable facts from a conversation transcript so a personal
assistant can remember them across sessions. Output ONLY fact lines, one per
line, using EXACTLY these pipe-delimited formats:

USER|<trait>|<value>           — stable facts about the user's identity (name, role, stack, timezone)
PREF|<key>|<value>             — preferences/rules ("prefers concise answers", "always use Kotlin")
CONTEXT|<topic>|<fact>         — durable facts about their environment/projects/tools
EPISODE||<one-sentence summary of what happened this session>
FEEDBACK||<a correction the user gave about the assistant's behavior>

Rules:
- Only record things worth remembering long-term. Skip ephemeral chit-chat.
- Do NOT include secrets, passwords, or tokens.
- Always emit exactly one EPISODE line summarizing the session.
- If there is nothing else worth saving, emit only the EPISODE line.
- Output nothing but fact lines. No prose, no code fences, no headings.
"""
    }

    data class Summary(val written: Int, val skipped: Int, val errors: Int)

    suspend fun learn(transcript: String): Summary {
        if (transcript.isBlank()) return Summary(0, 0, 0)

        val raw = try {
            extract(transcript)
        } catch (e: Exception) {
            log.warn("session extraction failed: {}", e.message)
            return Summary(0, 0, 1)
        }

        var written = 0
        var skipped = 0
        var errors = 0

        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.equals("NONE", ignoreCase = true)) continue

            val parts = trimmed.split("|", limit = 3)
            if (parts.size < 3) {
                log.debug("skipping malformed learn line: {}", trimmed)
                skipped++
                continue
            }
            val (type, key, value) = parts
            if (value.isBlank()) { skipped++; continue }

            try {
                when (type.trim().uppercase()) {
                    "USER" -> memory.rememberUser(key.trim(), value.trim())
                    "PREF" -> memory.setPreference(key.trim(), value.trim())
                    "CONTEXT" -> memory.rememberContext(key.trim().ifBlank { "general" }, value.trim())
                    "EPISODE" -> memory.recordEpisode(value.trim())
                    "FEEDBACK" -> memory.noteFeedback(value.trim())
                    else -> { skipped++; continue }
                }
                written++
            } catch (e: Exception) {
                log.warn("failed to store learn line '{}': {}", trimmed, e.message)
                errors++
            }
        }

        log.info("session learning: {} written, {} skipped, {} errors", written, skipped, errors)
        return Summary(written, skipped, errors)
    }
}
