package ai.kermes.core.skill

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Scans configured skill roots in priority order and exposes the merged catalog.
 * Resolution: project → user → bundled. First-match-wins on `name` collisions.
 *
 * Agent-written skills land under `<user-root>/agent-created/`.
 */
class SkillRegistry(
    private val roots: List<ScopedRoot>,
) {
    data class ScopedRoot(val scope: SkillScope, val path: Path)

    private val log = LoggerFactory.getLogger(SkillRegistry::class.java)
    private val skills: MutableMap<String, Skill> = mutableMapOf()
    private val origins: MutableMap<String, SkillScope> = mutableMapOf()

    init { reload() }

    fun reload() {
        skills.clear()
        origins.clear()
        for ((scope, root) in roots) {
            if (!root.exists() || !root.isDirectory()) continue
            scanRoot(root, scope)
        }
        log.info("loaded {} skills across {} roots", skills.size, roots.size)
    }

    private fun scanRoot(root: Path, scope: SkillScope) {
        Files.list(root).use { stream ->
            stream.filter { it.isDirectory() }.forEach { dir ->
                val skillMd = dir.resolve("SKILL.md")
                if (!skillMd.exists()) return@forEach
                try {
                    val skill = SkillParser.parse(dir)
                    if (!skills.containsKey(skill.frontmatter.name)) {
                        skills[skill.frontmatter.name] = skill
                        origins[skill.frontmatter.name] = scope
                    } else {
                        log.debug("skipped duplicate skill '{}' from {} (already loaded from {})",
                            skill.frontmatter.name, scope, origins[skill.frontmatter.name])
                    }
                } catch (e: SkillValidationException) {
                    log.warn("skipping invalid skill at {}: {}", dir, e.message)
                }
            }
        }
    }

    fun all(): Collection<Skill> = skills.values
    fun get(name: String): Skill? = skills[name]
    fun scopeOf(name: String): SkillScope? = origins[name]

    /** Stage-1 manifest: each line is `- <name>: <description>`. ~100 tokens per skill. */
    fun discoveryManifest(filter: Collection<String>? = null): String = buildString {
        val source = filter?.mapNotNull { skills[it] } ?: skills.values
        source.forEach { s ->
            append("- **").append(s.frontmatter.name).append("**: ")
            appendLine(s.frontmatter.description)
        }
    }
}
