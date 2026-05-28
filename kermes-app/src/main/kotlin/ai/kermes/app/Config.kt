package ai.kermes.app

import ai.kermes.core.skill.SkillRegistry.ScopedRoot
import ai.kermes.core.skill.SkillScope
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists

/**
 * MVP configuration loaded from env vars. Phase 2 will add a YAML config file
 * at ~/.kermes/config.yaml that overlays these defaults.
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
            val home = Paths.get(System.getProperty("user.home"))
            val kermes = home.resolve(".kermes")
            val cwd = Paths.get("").toAbsolutePath()

            // Resolution order = list order (first-match-wins): project → user → bundled.
            val skillsRoots = buildList {
                val projectSkills = cwd.resolve(".kermes/skills")
                if (projectSkills.exists()) add(ScopedRoot(SkillScope.Project, projectSkills))

                add(ScopedRoot(SkillScope.User, kermes.resolve("skills")))

                // Bundled skills shipped with the app. In dev this is the repo's
                // ./skills dir; override with KERMES_BUNDLED_SKILLS when packaged.
                val bundled = System.getenv("KERMES_BUNDLED_SKILLS")?.let { Paths.get(it) }
                    ?: cwd.resolve("skills")
                if (bundled.exists()) add(ScopedRoot(SkillScope.Bundled, bundled))
            }

            return KermesConfig(
                apiKey = System.getenv("KERMES_API_KEY")
                    ?: System.getenv("OPENROUTER_API_KEY")
                    ?: System.getenv("OPENAI_API_KEY")
                    ?: error("Set KERMES_API_KEY (or OPENROUTER_API_KEY / OPENAI_API_KEY)"),
                baseUrl = System.getenv("KERMES_BASE_URL") ?: "https://openrouter.ai/api/v1",
                modelId = System.getenv("KERMES_MODEL") ?: "openai/gpt-4o",
                embeddingsModelId = System.getenv("KERMES_EMBEDDINGS_MODEL") ?: "openai/text-embedding-3-small",
                skillsRoots = skillsRoots,
                agentCreatedSkillsRoot = kermes.resolve("skills/agent-created"),
                memoryRoot = kermes.resolve("memory"),
                vectorsRoot = kermes.resolve("vectors"),
                checkpointsRoot = kermes.resolve("checkpoints"),
                inboxRoot = kermes.resolve("inbox"),
                schedulesFile = kermes.resolve("schedules.yaml"),
                auditLog = kermes.resolve("audit.log"),
                dangerouslySkipPermissions =
                    System.getenv("KERMES_DANGEROUSLY_SKIP_PERMISSIONS")?.lowercase() == "true",
            )
        }
    }
}
