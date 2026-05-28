package ai.kermes.core.skill

import ai.kermes.core.feature.PermissionGuard
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Agent-facing surface of the skills system. Implements agentskills.io's
 * progressive disclosure: discovery is in the system prompt; this set covers
 * stage-2 activation (`load_skill`) and stage-3 execution (`read_skill_file`,
 * `run_skill_script`). The two `*_skill` write tools enable self-improvement.
 */
@LLMDescription("Skill engine — load, read, and execute agentskills.io skills.")
class SkillsToolSet(
    private val registry: SkillRegistry,
    /** Root for newly created or updated skills. Always user-scope. */
    private val writeRoot: Path,
    /** Optional permission gate for side-effecting tools (script exec, skill writes). */
    private val permissionGuard: PermissionGuard? = null,
) : ToolSet {

    private val log = LoggerFactory.getLogger(SkillsToolSet::class.java)

    /** Returns a denial message if the guard blocks the call, or null to proceed. */
    private fun denied(toolName: String, args: String): String? {
        val guard = permissionGuard ?: return null
        val decision = runBlocking { guard.check(toolName, args) }
        return if (decision is PermissionGuard.Decision.Deny)
            "PERMISSION DENIED ($toolName): ${decision.reason}. The action was not performed."
        else null
    }

    @Tool
    @LLMDescription(
        "List every known skill with its description. Useful when the system-prompt manifest " +
        "doesn't include enough detail. Prefer reading the manifest first."
    )
    fun list_skills(): String = registry.discoveryManifest()

    @Tool
    @LLMDescription(
        "Stage-2 activation. Load the full SKILL.md instructions for a skill by name. " +
        "Call this when a user task matches a skill's description in the manifest."
    )
    fun load_skill(
        @LLMDescription("Kebab-case skill name as it appears in the manifest.")
        name: String,
    ): String {
        val skill = registry.get(name) ?: return "ERROR: no skill named '$name'"
        return skill.body
    }

    @Tool
    @LLMDescription(
        "Stage-3. Read a bundled file from a skill — references/, assets/, or anything else " +
        "in the skill folder. Path is relative to the skill root."
    )
    fun read_skill_file(
        @LLMDescription("Skill name.") skill: String,
        @LLMDescription("Relative path within the skill folder, e.g. 'references/FORMS.md'.") path: String,
    ): String {
        val s = registry.get(skill) ?: return "ERROR: no skill named '$skill'"
        val resolved = s.root.resolve(path).normalize().absolute()
        val root = s.root.normalize().absolute()
        if (!resolved.startsWith(root)) return "ERROR: path escapes skill root"
        if (!resolved.exists()) return "ERROR: not found — $path"
        return resolved.readText(Charsets.UTF_8)
    }

    @Tool
    @LLMDescription(
        "Stage-3. Execute a script bundled with a skill. Only paths under 'scripts/' are allowed. " +
        "Output is returned as exit code + combined stdout/stderr."
    )
    fun run_skill_script(
        @LLMDescription("Skill name.") skill: String,
        @LLMDescription("Script path relative to the skill root. Must start with 'scripts/'.") script: String,
        @LLMDescription("Arguments passed to the script.") args: List<String> = emptyList(),
    ): String {
        denied("run_skill_script", "$skill $script ${args.joinToString(" ")}")?.let { return it }
        val s = registry.get(skill) ?: return "ERROR: no skill named '$skill'"
        if (!script.startsWith("scripts/")) return "ERROR: only scripts/ paths are allowed"

        val resolved = s.root.resolve(script).normalize().absolute()
        val root = s.root.normalize().absolute()
        if (!resolved.startsWith(root)) return "ERROR: path escapes skill root"
        if (!resolved.exists()) return "ERROR: not found — $script"

        val interpreter = interpreterFor(resolved) ?: return "ERROR: unsupported script type — ${resolved.extension}"
        val cmd = listOf(interpreter, resolved.toString()) + args

        log.info("run_skill_script: {}", cmd.joinToString(" "))
        val proc = ProcessBuilder(cmd)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return "exit=$code\n---\n$output"
    }

    @Tool
    @LLMDescription(
        "Self-improvement: create a new skill. The skill is written under the user skills root " +
        "(never bundled or project scope). The frontmatter is validated; an invalid skill is rejected."
    )
    fun create_skill(
        @LLMDescription("Skill name (kebab-case, must match its directory).") name: String,
        @LLMDescription("One-sentence description of what this skill does and when to use it.") description: String,
        @LLMDescription("Body of SKILL.md (markdown, no frontmatter — that's generated for you).") body: String,
    ): String {
        denied("create_skill", "$name — $description")?.let { return it }
        val dir = writeRoot.resolve(name)
        if (dir.exists()) return "ERROR: skill '$name' already exists. Use update_skill instead."

        Files.createDirectories(dir)
        val fm = SkillFrontmatter(name = name, description = description)
        val skillMd = dir.resolve("SKILL.md")
        skillMd.writeText(renderSkillMd(fm, body), Charsets.UTF_8)

        try {
            SkillParser.parse(dir) // validate
        } catch (e: SkillValidationException) {
            // Roll back the partial write so an invalid skill doesn't pollute the registry.
            Files.deleteIfExists(skillMd)
            Files.deleteIfExists(dir)
            return "ERROR: ${e.message}"
        }

        registry.reload()
        return "OK: created skill '$name' at $dir"
    }

    @Tool
    @LLMDescription(
        "Self-improvement: update an existing skill's body or description. The skill must already " +
        "exist and must be in the user scope (you cannot overwrite bundled or project skills)."
    )
    fun update_skill(
        @LLMDescription("Skill name.") name: String,
        @LLMDescription("New markdown body, or null to keep the existing body.") body: String? = null,
        @LLMDescription("New description, or null to keep the existing description.") description: String? = null,
    ): String {
        val skill = registry.get(name) ?: return "ERROR: no skill named '$name'"
        val scope = registry.scopeOf(name)
        if (scope != SkillScope.User && scope != SkillScope.AgentCreated)
            return "ERROR: skill '$name' is $scope-scope and cannot be modified by the agent"

        val newBody = body ?: skill.body
        val newDescription = description ?: skill.frontmatter.description
        val newFm = skill.frontmatter.copy(description = newDescription)

        val skillMd = skill.root.resolve("SKILL.md")
        skillMd.writeText(renderSkillMd(newFm, newBody), Charsets.UTF_8)

        try {
            SkillParser.parse(skill.root)
        } catch (e: SkillValidationException) {
            return "ERROR: ${e.message} (file was written; manual cleanup may be required)"
        }

        registry.reload()
        return "OK: updated skill '$name'"
    }

    private fun interpreterFor(p: Path): String? = when (p.extension.lowercase()) {
        "py" -> "python3"
        "sh", "bash" -> "bash"
        "js" -> "node"
        else -> null
    }

    private fun renderSkillMd(fm: SkillFrontmatter, body: String): String = buildString {
        appendLine("---")
        appendLine("name: ${fm.name}")
        appendLine("description: ${fm.description}")
        fm.license?.let { appendLine("license: $it") }
        fm.compatibility?.let { appendLine("compatibility: $it") }
        if (fm.metadata.isNotEmpty()) {
            appendLine("metadata:")
            fm.metadata.forEach { (k, v) -> appendLine("  $k: $v") }
        }
        fm.allowedTools?.let { appendLine("allowed-tools: $it") }
        appendLine("---")
        appendLine()
        append(body)
    }

    init {
        writeRoot.createDirectories()
    }
}
