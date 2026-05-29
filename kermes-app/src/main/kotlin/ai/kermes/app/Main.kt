package ai.kermes.app

import ai.kermes.core.feature.PermissionGuard
import ai.kermes.core.memory.LearnerToolSet
import ai.kermes.core.memory.MemoryStore
import ai.kermes.core.skill.SkillRegistry
import ai.kermes.core.skill.SkillsToolSet
import ai.kermes.schedule.CompositeSink
import ai.kermes.schedule.InboxFileSink
import ai.kermes.schedule.ScheduleStore
import ai.kermes.schedule.Scheduler
import ai.kermes.tools.BashToolSet
import ai.kermes.tools.FileToolSet
import ai.kermes.tools.WebSearchToolSet
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import kotlin.io.path.createDirectories

/**
 * Kermes entry point. Wires Koog primitives + Kermes tool sets and runs a
 * minimal REPL.
 *
 * Run with:
 *   export KERMES_API_KEY=sk-or-…
 *   ./gradlew :kermes-app:run
 *
 * Koog Features installed: EventHandler (audit), ChatMemory (conversation
 * buffer), Persistence (checkpoint/resume). Permission enforcement is at the
 * tool boundary (PermissionGuard injected into Bash/Skills tool sets). The
 * scheduler runs alongside the REPL when ~/.kermes/schedules.yaml is present.
 *
 * Still pending: session-end FactRetrieval extraction; per-turn top-K manifest
 * (currently the full manifest is injected statically, fine at MVP scale).
 */
fun main(args: Array<String>) = runBlocking {
    try {
        when (val cmd = Cli.parse(args)) {
            Cli.Command.Help -> Cli.printUsage()
            Cli.Command.Version -> Cli.printVersion()
            Cli.Command.Init -> Cli.runInit()
            Cli.Command.Setup -> Cli.runSetup()
            Cli.Command.Status -> Cli.runStatus()
            Cli.Command.Update -> Cli.runUpdate()
            is Cli.Command.Chat -> runChat(cmd.query)
        }
    } catch (e: KermesConfigError) {
        // Not configured yet — show the home screen and offer to set up.
        print(Banner.welcome())
        val interactive = System.console() != null
        if (interactive) {
            print("  ${Banner.RED}✗${Banner.RESET} ${e.message} Run setup now? ${Banner.DIM}[Y/n]${Banner.RESET} ")
            System.out.flush()
            when (readlnOrNull()?.trim()?.lowercase()) {
                null, "", "y", "yes" -> {
                    println()
                    Cli.runSetup()
                    println("\n  Setup done. Run ${Banner.GOLD}kermes${Banner.RESET} to start chatting.")
                }
                else -> println("  No problem — run ${Banner.GOLD}kermes setup${Banner.RESET} whenever you're ready.")
            }
        } else {
            // Non-interactive (piped/scripted): terse + non-zero exit.
            System.err.println("kermes: ${e.message} Run `kermes setup` or set KERMES_API_KEY.")
            kotlin.system.exitProcess(1)
        }
    }
}

/** Interactive REPL, or a single one-shot run when [oneShot] is non-null. */
private suspend fun runChat(oneShot: String?) {
    val log = LoggerFactory.getLogger("ai.kermes.app.Main")
    val config = KermesConfig.load()
    setupDirs(config)

    log.info("starting Kermes baseUrl={} model={}", config.baseUrl, config.modelId)

    // ---- Koog primitives -------------------------------------------------
    val executor = KoogWiring.buildPromptExecutor(config.apiKey, config.baseUrl)
    val model = KoogWiring.resolveModel(config.apiKey, config.baseUrl, config.modelId)
    val vectors = KoogWiring.buildVectorStore(
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        storageRoot = config.vectorsRoot,
    )

    // ---- Memory layer ----------------------------------------------------
    val memory = MemoryStore(config.memoryRoot, vectors)

    // ---- Skill registry (roots already scoped + ordered by Config) -------
    val skillRegistry = SkillRegistry(config.skillsRoots)

    // ---- Permissions -----------------------------------------------------
    val permissionGuard = PermissionGuard(
        PermissionGuard.Config(
            prompt = { tool, args -> ConsoleApprover.ask(tool, args) },
            dangerouslySkipAll = config.dangerouslySkipPermissions,
        )
    )

    // ---- Tool sets (side-effecting ones get the permission gate) ----------
    val skillTools = SkillsToolSet(skillRegistry, config.agentCreatedSkillsRoot, permissionGuard)
    val learnerTools = LearnerToolSet(memory, vectors)
    val workdir = Paths.get(System.getProperty("user.dir"))
    val bashTools = BashToolSet(
        workdir = workdir,
        permissionGuard = permissionGuard,
    )
    val webTools = WebSearchToolSet()
    val fileTools = FileToolSet(baseDir = workdir, permissionGuard = permissionGuard)

    // ---- System-prompt context: skill manifest + eager memory ------------
    val skillManifest = skillRegistry.discoveryManifest()
    val eagerMemory = buildEagerMemory(memory)

    // ---- Build the agent -------------------------------------------------
    val agent = KoogAgentFactory.build(
        promptExecutor = executor,
        model = model,
        config = config,
        skillTools = skillTools,
        learnerTools = learnerTools,
        bashTools = bashTools,
        webTools = webTools,
        fileTools = fileTools,
        skillManifest = skillManifest,
        eagerMemory = eagerMemory,
    )

    // ---- One-shot mode: run once, print, exit (no scheduler/REPL) --------
    if (oneShot != null) {
        try {
            println(agent.run(oneShot, "oneshot"))
        } catch (e: Exception) {
            log.error("one-shot run failed", e)
            println("ERROR: ${e.message}")
        }
        vectors.flush()
        return
    }

    // ---- Scheduler (optional; runs alongside the REPL) -------------------
    val entries = ScheduleStore(config.schedulesFile).load()
    val scheduler = if (entries.isNotEmpty()) {
        val sinks = buildMap<String, ai.kermes.schedule.DeliverySink> {
            put("inbox", InboxFileSink(config.inboxRoot))
            val tgToken = ConfigSource.get("KERMES_TELEGRAM_BOT_TOKEN")
            val tgChat = ConfigSource.get("KERMES_TELEGRAM_CHAT_ID")
            if (tgToken != null && tgChat != null) {
                put("telegram", ai.kermes.schedule.TelegramSink(tgToken, tgChat))
                log.info("telegram delivery enabled (chat {})", tgChat)
            }
        }
        val sink = CompositeSink(sinks)
        Scheduler(
            agentRunner = { entry -> agent.run(entry.prompt, "schedule-${entry.id}") },
            sink = sink,
        ).also {
            it.start(entries)
            log.info("scheduler started with {} schedule(s)", entries.size)
        }
    } else null

    // ---- Session learner (auto fact-extraction at session end) -----------
    val extractorAgent = KoogAgentFactory.buildExtractor(executor, model)
    val sessionLearner = SessionLearner(memory) { transcript -> extractorAgent.run(transcript, "extract") }
    val transcript = StringBuilder()

    // ---- REPL ------------------------------------------------------------
    val session = Slash.Session("tui-main")
    log.info("ready. {} skills loaded, {} schedule(s).", skillRegistry.all().size, entries.size)
    print(
        Banner.render(
            version = KERMES_VERSION,
            model = config.modelId,
            cwd = System.getProperty("user.dir"),
            skills = skillRegistry.all().map { it.frontmatter.name },
        )
    )

    while (true) {
        print("\n> ")
        val raw = readlnOrNull() ?: break          // EOF (Ctrl-D / piped input ends)
        val line = raw.trim().takeIf { it.isNotEmpty() } ?: continue

        if (line.startsWith("/")) {
            when (Slash.handle(line, skillRegistry, memory, permissionGuard, config.modelId, session)) {
                Slash.Outcome.Exit -> break
                else -> continue
            }
        }

        try {
            val response = agent.run(line, session.id)
            println(response)
            transcript.append("User: ").append(line).append('\n')
                .append("Assistant: ").append(response).append("\n\n")
        } catch (e: Exception) {
            log.error("agent run failed", e)   // full detail → ~/.kermes/kermes.log
            val short = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(200)
                ?: (e::class.simpleName ?: "unknown error")
            println("  ${Banner.RED}✗${Banner.RESET} $short")
            println("  ${Banner.DIM}details in ~/.kermes/kermes.log${Banner.RESET}")
        }
    }

    // Self-learn from the session before shutting down.
    if (transcript.isNotBlank()) {
        print("\nlearning from this session… ")
        val s = sessionLearner.learn(transcript.toString())
        println("saved ${s.written} memory item(s).")
    }

    scheduler?.close()
    vectors.flush()
    println("bye.")
}

/** Eager (Tier-1) memory: identity + preferences injected into every prompt. */
private suspend fun buildEagerMemory(memory: MemoryStore): String {
    val user = memory.userFacts()
    val prefs = memory.preferences()
    if (user.isEmpty() && prefs.isEmpty()) return ""
    return buildString {
        appendLine("# What you know about the user")
        if (user.isNotEmpty()) {
            appendLine("## Identity")
            user.forEach { (k, v) -> appendLine("- $k: $v") }
        }
        if (prefs.isNotEmpty()) {
            appendLine("## Preferences")
            prefs.forEach { (k, v) -> appendLine("- $k: $v") }
        }
    }
}

private fun setupDirs(c: KermesConfig) {
    c.memoryRoot.createDirectories()
    c.vectorsRoot.createDirectories()
    c.checkpointsRoot.createDirectories()
    c.inboxRoot.createDirectories()
    c.agentCreatedSkillsRoot.createDirectories()
    c.auditLog.parent?.createDirectories()
}
