package ai.kermes.core.feature

import org.slf4j.LoggerFactory

/**
 * Policy core for tool-call permission decisions. Free of Koog dependencies
 * so it can be unit-tested in isolation. The Koog wiring (a custom Feature
 * with a pipeline interceptor that calls check) lives in kermes-app.
 *
 * Decision flow:
 *   1. deny rules win first — no override, no prompt
 *   2. allow rules win second — auto-allow, no prompt
 *   3. tier default applies — auto-allow or prompt
 *   4. prompt callback returns the final decision
 */
class PermissionGuard(private val config: Config) {

    private val log = LoggerFactory.getLogger(PermissionGuard::class.java)
    private val sessionAllows = mutableSetOf<String>()

    /** Runtime override for the `/yolo` slash command; starts at the config value. */
    var skipAll: Boolean = config.dangerouslySkipAll
        private set

    /** Flip YOLO mode at runtime. Returns the new state. */
    fun toggleYolo(): Boolean { skipAll = !skipAll; return skipAll }

    suspend fun check(toolName: String, args: String): Decision {
        if (skipAll) return Decision.Allow("skip-all flag")

        val deniedBy = config.denyPatterns.firstOrNull { pat -> pat.matches(toolName, args) }
        if (deniedBy != null) {
            log.warn("permission DENY: tool={} matched={}", toolName, deniedBy)
            return Decision.Deny("matches deny rule: $deniedBy")
        }

        val allowedBy = config.allowPatterns.firstOrNull { pat -> pat.matches(toolName, args) }
        if (allowedBy != null) return Decision.Allow("matches allow rule: $allowedBy")

        val key = sessionKey(toolName, args)
        if (key in sessionAllows) return Decision.Allow("allowed earlier this session")

        val tier = config.tierFor(toolName)
        if (tier == Tier.AutoAllow) return Decision.Allow("auto-allowed (read-only tier)")

        return when (config.prompt(toolName, args)) {
            PromptResponse.AllowOnce -> Decision.Allow("user allowed once")
            PromptResponse.AllowSession -> {
                sessionAllows.add(key)
                Decision.Allow("user allowed for session")
            }
            PromptResponse.AlwaysAllow -> {
                sessionAllows.add(key)
                Decision.Allow("user always-allowed (session for MVP)")
            }
            PromptResponse.Deny -> Decision.Deny("user denied")
        }
    }

    private fun sessionKey(tool: String, args: String): String = "$tool::${args.hashCode()}"

    enum class Tier { AutoAllow, Prompt }

    enum class PromptResponse { AllowOnce, AllowSession, AlwaysAllow, Deny }

    sealed class Decision(val reason: String) {
        class Allow(reason: String) : Decision(reason)
        class Deny(reason: String) : Decision(reason)
    }

    data class Config(
        val tiers: Map<String, Tier> = defaultTiers(),
        val allowPatterns: List<RulePattern> = emptyList(),
        val denyPatterns: List<RulePattern> = emptyList(),
        val prompt: suspend (toolName: String, args: String) -> PromptResponse,
        val dangerouslySkipAll: Boolean = false,
    ) {
        fun tierFor(toolName: String): Tier = tiers[toolName] ?: Tier.Prompt
    }

    // Glob-style pattern: tool[:arg-glob].
    // Examples: bash:git*, write_file:~/.kermes/**, rm.
    data class RulePattern(val raw: String) {
        private val toolName: String
        private val argsGlob: String?

        init {
            val pieces = raw.split(":", limit = 2)
            toolName = pieces[0]
            argsGlob = pieces.getOrNull(1)
        }

        fun matches(tool: String, args: String): Boolean {
            if (!globMatch(toolName, tool)) return false
            if (argsGlob == null) return true
            return globMatch(argsGlob, args)
        }

        override fun toString() = raw
    }

    companion object {
        fun defaultTiers(): Map<String, Tier> = mapOf(
            "__read_file__" to Tier.AutoAllow,
            "__list_directory__" to Tier.AutoAllow,
            "list_skills" to Tier.AutoAllow,
            "load_skill" to Tier.AutoAllow,
            "read_skill_file" to Tier.AutoAllow,
            "recall" to Tier.AutoAllow,
            "__write_file__" to Tier.Prompt,
            "__edit_file__" to Tier.Prompt,
            "bash" to Tier.Prompt,
            "run_skill_script" to Tier.Prompt,
            "create_skill" to Tier.Prompt,
            "update_skill" to Tier.Prompt,
        )

        private val regexMeta: Set<Char> = ".()+|^_[]{}_\\".toSet()  // see globMatch

        internal fun globMatch(pattern: String, input: String): Boolean {
            val sb = StringBuilder("^")
            for (c in pattern) {
                when {
                    c == '*' -> sb.append(".*")
                    c == '?' -> sb.append('.')
                    c == '$' -> sb.append("\\$")
                    c in regexMeta -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            return Regex(sb.toString()).matches(input)
        }
    }
}
