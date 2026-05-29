package ai.kermes.app

import ai.kermes.core.memory.LearnerToolSet
import ai.kermes.core.skill.SkillsToolSet
import ai.kermes.tools.BashToolSet
import ai.kermes.tools.FileToolSet
import ai.kermes.tools.WebSearchToolSet
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.features.eventHandler.feature.EventHandler
import ai.koog.agents.snapshot.feature.Persistence
import ai.koog.agents.snapshot.providers.file.JVMFilePersistenceStorageProvider
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.appendText
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

/**
 * Builds the Koog [GraphAIAgent] with Kermes tool sets and a stack of
 * cross-cutting Features. All Koog AIAgent + Feature install calls live in
 * this single file so the rest of the app stays Koog-agnostic.
 *
 * Wired Features: EventHandler (audit), ChatMemory (conversation buffer),
 * Persistence (checkpoint/resume). Permission enforcement lives at the tool
 * boundary (PermissionGuard injected into BashToolSet/SkillsToolSet) rather
 * than as a pipeline interceptor — Koog 1.0's tool-call interceptor is
 * observe-only and cannot block, and gating in the tool lets us return a
 * "denied" result to the model instead of aborting the run.
 *
 * The skill manifest + eager memory are injected into the system prompt at
 * build time (static). For large skill sets, swap to per-turn top-K retrieval
 * once Koog exposes a prompt-transform hook.
 */
object KoogAgentFactory {

    private val log = LoggerFactory.getLogger(KoogAgentFactory::class.java)

    fun build(
        promptExecutor: PromptExecutor,
        model: LLModel,
        config: KermesConfig,
        skillTools: SkillsToolSet,
        learnerTools: LearnerToolSet,
        bashTools: BashToolSet,
        webTools: WebSearchToolSet,
        fileTools: FileToolSet,
        skillManifest: String,
        eagerMemory: String,
    ): GraphAIAgent<String, String> {

        val toolRegistry = ToolRegistry {
            tools(skillTools)
            tools(learnerTools)
            tools(bashTools)
            tools(webTools)
            tools(fileTools)
        }

        val auditWriter = AuditWriter(config.auditLog)

        return AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            toolRegistry = toolRegistry,
            systemPrompt = systemPrompt(skillManifest, eagerMemory),
            temperature = 0.7,
            maxIterations = 50,
        ) {
            // Audit log on every tool call + crash logging.
            install(EventHandler) {
                onToolCallStarting { ctx ->
                    auditWriter.append("STARTING tool=${ctx.toolName} args=${ctx.toolArgs}")
                }
                onToolCallCompleted { ctx ->
                    auditWriter.append("OK tool=${ctx.toolName}")
                }
                onToolCallFailed { ctx ->
                    auditWriter.append("FAILED tool=${ctx.toolName} error=${ctx.error?.message ?: "(no message)"}")
                }
                onAgentExecutionFailed { ctx ->
                    log.error("agent run failed", ctx.error ?: RuntimeException("agent error (no detail)"))
                }
            }

            // Per-session conversation buffer (keyed by the session id passed to run()).
            install(ChatMemory) {
                windowSize(50)
            }

            // Checkpoint/resume — survives process restart for scheduled runs.
            install(Persistence) {
                storage = JVMFilePersistenceStorageProvider(config.checkpointsRoot)
            }
        }
    }

    /**
     * A tool-less, single-shot agent used for session-end fact extraction.
     * Reuses the proven `AIAgent.run` path — no graph DSL, no tool loop.
     */
    fun buildExtractor(
        promptExecutor: PromptExecutor,
        model: LLModel,
    ): GraphAIAgent<String, String> =
        AIAgent(
            promptExecutor = promptExecutor,
            llmModel = model,
            systemPrompt = SessionLearner.SYSTEM_PROMPT,
            temperature = 0.0,
            // Tool-less, so a single LLM turn is enough — but Koog's single-run
            // strategy spends >1 iteration unit to emit + finalize the message,
            // so maxIterations=1 throws AIAgentMaxNumberOfIterationsReached. Give
            // it a small budget; with no tools it still finishes in one turn.
            maxIterations = 5,
        )

    private fun systemPrompt(skillManifest: String, eagerMemory: String): String = buildString {
        appendLine(
            """
            You are Kermes, a Kotlin/JVM AI agent inspired by the Hermes Agent
            pattern. You have access to:

            - The skills engine — list_skills, load_skill, read_skill_file,
              run_skill_script. Skills follow the agentskills.io format.
            - Memory tools — remember_user, set_preference, remember_context,
              record_episode, note_feedback, recall, update_context.
            - bash for shell commands and web_search for lookups.

            When a user task matches a skill, call load_skill(name) first to load
            its instructions before acting. Some tools require user approval; if a
            call is denied, explain and adapt rather than retrying blindly.

            Memory discipline — be proactive and precise:
            - The moment the user states or corrects a durable fact about
              themselves, call the matching memory tool BEFORE you reply, then
              confirm it in your reply:
                * name / its spelling / role / stack / location → remember_user
                * a rule or style ("always", "prefer", "from now on") → set_preference
                * their environment, projects, tooling → remember_context
                * a correction to your own behavior → note_feedback
            - Corrections and alternate spellings ARE durable facts. If the user
              says "actually it's X", gives a translation, or a different spelling
              of something you already know, persist the new value (remember_user
              overwrites a trait — use the same trait key, e.g. "name").
            - Never call a memory tool just to ANSWER a question. The user's
              identity and preferences are already given below — read them. Use
              `recall` only for past episodes/context, and don't re-save a value
              that hasn't changed.
            """.trimIndent()
        )
        if (skillManifest.isNotBlank()) {
            appendLine()
            appendLine(skillManifest)
        }
        if (eagerMemory.isNotBlank()) {
            appendLine()
            appendLine(eagerMemory)
        }
    }
}

/** Append-only audit log file. */
private class AuditWriter(private val path: Path) {
    init {
        path.createParentDirectories()
        if (!path.exists()) path.writeText("")
    }

    fun append(line: String) {
        path.appendText("${Instant.now()} $line\n")
    }
}
