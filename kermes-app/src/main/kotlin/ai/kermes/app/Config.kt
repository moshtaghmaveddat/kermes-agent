package ai.kermes.app

import ai.kermes.core.skill.SkillRegistry.ScopedRoot
import ai.kermes.core.skill.SkillScope
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readLines

/**
 * Configuration source: environment variables take precedence, falling back to
 * a dotenv-style `~/.kermes/config` file (written by `kermes setup`). This is
 * what lets users configure their API key once instead of exporting it every
 * shell.
 */
/** The Kermes home dir. `KERMES_HOME` overrides; defaults to ~/.kermes. */
fun kermesHome(): Path =
    System.getenv("KERMES_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        ?: Paths.get(System.getProperty("user.home")).resolve(".kermes")

object ConfigSource {
    val configFile: Path = kermesHome().resolve("config")

    private val fileValues: Map<String, String> by lazy { readFile() }

    /** Env var wins; otherwise the config file; otherwise null. Blank == absent. */
    fun get(key: String): String? =
        System.getenv(key)?.takeIf { it.isNotBlank() } ?: fileValues[key]?.takeIf { it.isNotBlank() }

    private fun readFile(): Map<String, String> {
        if (!configFile.exists()) return emptyMap()
        return runCatching {
            configFile.readLines().mapNotNull { line ->
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#") || "=" !in t) return@mapNotNull null
                val k = t.substringBefore("=").trim()
                val v = t.substringAfter("=").trim().removeSurrounding("\"")
                if (k.isEmpty()) null else k to v
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}

/**
 * MVP configuration. Resolved from [ConfigSource] (env → ~/.kermes/config).
 */
data class KermesConfig(
    val apiKey: String,
    val baseUrl: String,
    val modelId: String,
    val embeddingsModelId: String,
    /** Skill roots in resolution order: project → user → bundled (first wins). */
    val skillsRoots: List<ScopedRoot>,
    val agentCreatedSkillsRoot: Path,
    val memoryRoot: Path,
    val vectorsRoot: Path,
    val checkpointsRoot: Path,
    val inboxRoot: Path,
    val schedulesFile: Path,
    val auditLog: Path,
    val dangerouslySkipPermissions: Boolean,
) {
    companion object {
        fun load(): KermesConfig {
            val kermes = kermesHome()
            val cwd = Paths.get("").toAbsolutePath()

            // Resolution order = list order (first-match-wins): project → user → bundled.
            val skillsRoots = buildList {
                val projectSkills = cwd.resolve(".kermes/skills")
                if (projectSkills.exists()) add(ScopedRoot(SkillScope.Project, projectSkills))

                add(ScopedRoot(SkillScope.User, kermes.resolve("skills")))

                // Bundled skills shipped with the app. In dev this is the repo's
                // ./skills dir; override with KERMES_BUNDLED_SKILLS when packaged.
                val bundled = ConfigSource.get("KERMES_BUNDLED_SKILLS")?.let { Paths.get(it) }
                    ?: cwd.resolve("skills")
                if (bundled.exists()) add(ScopedRoot(SkillScope.Bundled, bundled))
            }

            return KermesConfig(
                apiKey = ConfigSource.get("KERMES_API_KEY")
                    ?: ConfigSource.get("OPENROUTER_API_KEY")
                    ?: ConfigSource.get("OPENAI_API_KEY")
                    ?: error("No API key. Run `kermes setup`, or set KERMES_API_KEY."),
                baseUrl = ConfigSource.get("KERMES_BASE_URL") ?: "https://openrouter.ai/api/v1",
                modelId = ConfigSource.get("KERMES_MODEL") ?: "openai/gpt-4o",
                embeddingsModelId = ConfigSource.get("KERMES_EMBEDDINGS_MODEL") ?: "openai/text-embedding-3-small",
                skillsRoots = skillsRoots,
                agentCreatedSkillsRoot = kermes.resolve("skills/agent-created"),
                memoryRoot = kermes.resolve("memory"),
                vectorsRoot = kermes.resolve("vectors"),
                checkpointsRoot = kermes.resolve("checkpoints"),
                inboxRoot = kermes.resolve("inbox"),
                schedulesFile = kermes.resolve("schedules.yaml"),
                auditLog = kermes.resolve("audit.log"),
                dangerouslySkipPermissions =
                    ConfigSource.get("KERMES_DANGEROUSLY_SKIP_PERMISSIONS")?.lowercase() == "true",
            )
        }
    }
}
