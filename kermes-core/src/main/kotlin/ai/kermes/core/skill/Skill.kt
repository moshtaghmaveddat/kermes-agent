package ai.kermes.core.skill

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * agentskills.io frontmatter schema. Required fields: name, description.
 * https://agentskills.io/specification
 */
@Serializable
data class SkillFrontmatter(
    val name: String,
    val description: String,
    val license: String? = null,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    @SerialName("allowed-tools") val allowedTools: String? = null,
)

/** A parsed skill: frontmatter + markdown body + on-disk location. */
data class Skill(
    val frontmatter: SkillFrontmatter,
    val body: String,
    val root: Path,
)

/** Origin of a skill — controls where agent-written skills can land. */
enum class SkillScope { Project, User, Bundled, AgentCreated }

class SkillValidationException(message: String) : RuntimeException(message)

object SkillParser {

    private val NAME_RE = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val FRONTMATTER_RE = Regex("^---\\s*\\R(.*?)\\R---\\s*\\R(.*)", RegexOption.DOT_MATCHES_ALL)

    fun parse(skillDir: Path): Skill {
        val md = skillDir.resolve("SKILL.md")
        val raw = md.readText(Charsets.UTF_8)

        val match = FRONTMATTER_RE.find(raw)
            ?: throw SkillValidationException("${md}: missing YAML frontmatter")

        val frontmatterYaml = match.groupValues[1]
        val body = match.groupValues[2]

        val fm = try {
            Yaml.default.decodeFromString(SkillFrontmatter.serializer(), frontmatterYaml)
        } catch (e: Exception) {
            throw SkillValidationException("${md}: frontmatter parse failed — ${e.message}")
        }

        validate(fm, skillDir)
        return Skill(fm, body, skillDir)
    }

    private fun validate(fm: SkillFrontmatter, dir: Path) {
        if (fm.name.length !in 1..64)
            throw SkillValidationException("name must be 1-64 chars: '${fm.name}'")
        if (!NAME_RE.matches(fm.name))
            throw SkillValidationException("name must match [a-z0-9]+(-[a-z0-9]+)*: '${fm.name}'")
        if (fm.name != dir.name)
            throw SkillValidationException("name '${fm.name}' must match parent dir '${dir.name}'")
        if (fm.description.isBlank() || fm.description.length > 1024)
            throw SkillValidationException("description must be 1-1024 chars (got ${fm.description.length})")
        if (fm.compatibility != null && fm.compatibility.length > 500)
            throw SkillValidationException("compatibility must be ≤500 chars")
    }
}
