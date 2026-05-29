package ai.kermes.core.memory

import ai.kermes.core.vector.DocTag
import ai.kermes.core.vector.VectorStore
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.runBlocking

/**
 * Read-only memory access for the chat agent. Writes are NOT exposed here on
 * purpose: durable facts are captured by the out-of-band per-turn extractor
 * (see SessionLearner), which is the single writer. The chat model only needs
 * to *recall* — its identity/preferences are already injected into the prompt
 * as eager memory, so this is just for older episodes/context.
 */
@LLMDescription("Recall durable facts from past sessions (episodes, context, feedback) by semantic similarity.")
class RecallToolSet(private val vectors: VectorStore) : ToolSet {

    @Tool
    @LLMDescription(
        "Recall past episodes, context, or feedback by semantic similarity. " +
        "Use sparingly — what you know about the user (identity, preferences) is " +
        "already in the system prompt. Call this only for cross-session lookups, " +
        "e.g. 'what did we decide last week?'."
    )
    fun recall(
        @LLMDescription("Search query in natural language.") query: String,
        @LLMDescription("Number of results to return (default 5).") k: Int = 5,
        @LLMDescription("Perspectives to search. Default = episodes + feedback + context.") perspectives: List<String>? = null,
    ): String = runBlocking {
        val tags = perspectives?.mapNotNull {
            runCatching { DocTag.valueOf(it) }.getOrNull()
        }?.toSet() ?: setOf(DocTag.Episode, DocTag.Feedback, DocTag.Context)

        val hits = vectors.query(query, topK = k, tags = tags)
        if (hits.isEmpty()) return@runBlocking "No matches."
        hits.joinToString("\n---\n") { h ->
            "[${h.tag} score=${"%.3f".format(h.score)}] ${h.text}"
        }
    }
}
