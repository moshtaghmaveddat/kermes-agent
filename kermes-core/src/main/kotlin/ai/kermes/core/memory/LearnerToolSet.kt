package ai.kermes.core.memory

import ai.kermes.core.vector.DocTag
import ai.kermes.core.vector.VectorStore
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.runBlocking

/**
 * Six perspective-specific tools (no single `learn` verb). Plus `recall` and
 * `correct_memory`. Tool descriptions are deliberately verbose so the model
 * picks the right perspective without further prompting.
 */
@LLMDescription("Memory and learning — six perspectives for capturing what you learn about the user, the world, and yourself.")
class LearnerToolSet(private val store: MemoryStore, private val vectors: VectorStore) : ToolSet {

    @Tool
    @LLMDescription(
        "USER IDENTITY perspective. Persist a single fact about who the user is — name, role, " +
        "stack, location, relationships. Upserts by `trait` (last write wins). " +
        "Use when: the user states a personal fact. " +
        "Do NOT use for: preferences ('I prefer X') — use set_preference. Don't use for transient session info."
    )
    fun remember_user(
        @LLMDescription("Short trait key, e.g. 'name', 'role', 'primary_language', 'employer'.") trait: String,
        @LLMDescription("Value of the trait, ideally one line.") value: String,
    ): String = runBlocking {
        val r = store.rememberUser(trait, value)
        "OK: ${r.name.lowercase()} user.$trait"
    }

    @Tool
    @LLMDescription(
        "PREFERENCE perspective. Persist a behavior rule for how the user wants things done — " +
        "communication style, tooling defaults, approval rules. Upserts by `key`. " +
        "Use when: the user says 'from now on', 'always', 'prefer', or corrects style. " +
        "Do NOT use for: identity facts — use remember_user."
    )
    fun set_preference(
        @LLMDescription("Short key, e.g. 'response_style', 'commit_signoff', 'default_branch'.") key: String,
        @LLMDescription("The preferred behavior, one line if possible.") value: String,
    ): String = runBlocking {
        val r = store.setPreference(key, value)
        "OK: ${r.name.lowercase()} preference.$key"
    }

    @Tool
    @LLMDescription(
        "DOMAIN CONTEXT perspective. Persist a fact about the user's environment — codebases, " +
        "services, jargon, system topology. Appends within a topic and deduplicates. " +
        "Use when: the user explains how their setup works (e.g. 'we deploy via Argo on staging'). " +
        "Do NOT use for: user identity or preferences."
    )
    fun remember_context(
        @LLMDescription("Topic the fact belongs to, e.g. 'deploy', 'auth', 'naming-conventions'.") topic: String,
        @LLMDescription("The fact itself, one short sentence.") fact: String,
    ): String = runBlocking {
        val r = store.rememberContext(topic, fact)
        "OK: ${r.name.lowercase()} context.$topic"
    }

    @Tool
    @LLMDescription(
        "Replace a context topic wholesale. Use when an entire topic has evolved and old facts " +
        "should be replaced rather than amended."
    )
    fun update_context(
        @LLMDescription("Topic to replace.") topic: String,
        @LLMDescription("New full content as a list of short facts.") facts: List<String>,
    ): String = runBlocking {
        val r = store.updateContext(topic, facts)
        "OK: ${r.name.lowercase()} context.$topic with ${facts.size} fact(s)"
    }

    @Tool
    @LLMDescription(
        "EPISODE perspective. Record what happened in this session for future cross-session recall. " +
        "Typically called by the session-end summarizer, not directly by the model mid-conversation. " +
        "Append-only and time-stamped."
    )
    fun record_episode(
        @LLMDescription("One-paragraph summary of the session.") summary: String,
        @LLMDescription("Optional tags to aid retrieval, e.g. ['deploy', 'incident'].") tags: List<String> = emptyList(),
    ): String = runBlocking {
        store.recordEpisode(summary, tags)
        "OK: recorded episode"
    }

    @Tool
    @LLMDescription(
        "SELF-FEEDBACK perspective. Record a correction to the agent's own behavior. " +
        "Use when: the user says 'don't do X', 'next time do Y', or corrects approach. " +
        "Optionally formulates the correction as a rule for the agent to follow."
    )
    fun note_feedback(
        @LLMDescription("What the user said or what was wrong.") observation: String,
        @LLMDescription("Optional rule to apply going forward, e.g. 'never run rm -rf without prompting'.") rule: String? = null,
    ): String = runBlocking {
        store.noteFeedback(observation, rule)
        "OK: noted feedback"
    }

    @Tool
    @LLMDescription(
        "Recall past episodes, context, or feedback by semantic similarity. " +
        "Use sparingly — eager memory (user/preferences) is already in the system prompt. " +
        "Call this for cross-session lookups, e.g. 'what did we decide last week?'."
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
