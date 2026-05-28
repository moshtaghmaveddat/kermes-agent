package ai.kermes.core.feature

import ai.kermes.core.skill.SkillRegistry
import ai.kermes.core.vector.DocTag
import ai.kermes.core.vector.VectorStore
import org.slf4j.LoggerFactory

/**
 * Stage-1 discovery for the agentskills.io progressive-disclosure pattern.
 *
 * Given the latest user message, computes the top-K skill manifest plus the
 * top-K "relevant context" fragments by running a single vector query. The
 * Koog wiring (a custom Feature hooking `onLLMCallStarting`) lives in
 * kermes-app — this class is pure logic.
 */
class SkillManifestBuilder(
    private val registry: SkillRegistry,
    private val vectors: VectorStore,
    private val config: Config = Config(),
) {
    private val log = LoggerFactory.getLogger(SkillManifestBuilder::class.java)

    data class Config(
        val topKSkills: Int = 8,
        val topKContext: Int = 6,
        /** Half-life in days for time-decay applied to context hits. */
        val contextHalfLifeDays: Double = 30.0,
    )

    suspend fun build(userMessage: String): Manifest {
        val skillsTask = vectors.query(userMessage, topK = config.topKSkills, tags = setOf(DocTag.Skill))
        val contextTask = vectors.query(userMessage, topK = config.topKContext, tags = setOf(DocTag.Context))

        val skills = skillsTask.mapNotNull { hit -> registry.get(hit.id.removePrefix("skill:"))?.frontmatter }
        val contextFragments = contextTask
            .map { it.copy(score = applyTimeDecay(it.score, it.attrs["ts"])) }
            .sortedByDescending { it.score }
            .take(config.topKContext)

        return Manifest(
            skillsManifest = renderSkills(skills),
            relevantContext = renderContext(contextFragments),
        )
    }

    private fun renderSkills(skills: List<ai.kermes.core.skill.SkillFrontmatter>): String {
        if (skills.isEmpty()) return ""
        return buildString {
            appendLine("## Available skills (top matches for current task)")
            appendLine("Call `load_skill(name)` to load full instructions.")
            appendLine()
            skills.forEach { s ->
                append("- **").append(s.name).append("**: ").appendLine(s.description)
            }
        }
    }

    private fun renderContext(hits: List<ai.kermes.core.vector.Hit>): String {
        if (hits.isEmpty()) return ""
        return buildString {
            appendLine("## Relevant context")
            hits.forEach { h ->
                appendLine("- ${h.text}")
            }
        }
    }

    private fun applyTimeDecay(score: Double, isoTs: String?): Double {
        if (isoTs == null) return score
        val ageDays = try {
            val now = java.time.Instant.now()
            val ts = java.time.Instant.parse(isoTs)
            java.time.Duration.between(ts, now).toDays().coerceAtLeast(0L).toDouble()
        } catch (_: Exception) {
            return score
        }
        val lambda = java.lang.Math.log(2.0) / config.contextHalfLifeDays
        return score * java.lang.Math.exp(-lambda * ageDays)
    }

    data class Manifest(val skillsManifest: String, val relevantContext: String) {
        fun asPromptBlock(): String = buildString {
            if (skillsManifest.isNotBlank()) {
                appendLine(skillsManifest)
                appendLine()
            }
            if (relevantContext.isNotBlank()) appendLine(relevantContext)
        }.trim()
    }
}
