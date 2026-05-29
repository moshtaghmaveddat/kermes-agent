package ai.kermes.app

import ai.kermes.core.feature.PermissionGuard
import ai.kermes.core.memory.LearnerToolSet
import ai.kermes.core.memory.MemoryStore
import ai.kermes.core.skill.SkillRegistry
import ai.kermes.core.skill.SkillsToolSet
import ai.kermes.core.vector.KoogVectorStore
import ai.kermes.schedule.CompositeSink
import ai.kermes.schedule.InboxFileSink
import ai.kermes.schedule.ScheduleStore
import ai.kermes.schedule.Scheduler
import ai.kermes.schedule.TelegramGateway
import ai.kermes.tools.BashToolSet
import ai.kermes.tools.FileToolSet
import ai.kermes.tools.WebSearchToolSet
import ai.koog.agents.core.agent.GraphAIAgent
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.coroutineScope
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
            is Cli.Command.Mcp -> McpDebugServer.serve(cmd.port)
            is Cli.Command.Serve -> when (cmd.action) {
                Cli.ServeAction.Run -> runServe(cmd.mcpPort)
                Cli.ServeAction.InstallService -> Cli.installService()
                Cli.ServeAction.UninstallService -> Cli.uninstallService()
                Cli.ServeAction.PrintService -> Cli.printServicePlist()
            }
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

    // ---- Permissions: interactive console prompts for the TUI ------------
    val permissionGuard = PermissionGuard(
        PermissionGuard.Config(
            prompt = { tool, args -> ConsoleApprover.ask(tool, args) },
            dangerouslySkipAll = config.dangerouslySkipPermissions,
        )
    )

    // ---- Agent stack (Koog primitives + tools + memory + skills) ---------
    val stack = buildAgentStack(config, permissionGuard)
    val agent = stack.agent
    val memory = stack.memory
    val vectors = stack.vectors
    val skillRegistry = stack.skillRegistry
    val executor = stack.executor
    val model = stack.model

    // ---- One-shot mode: run once, print, exit (no scheduler/REPL) --------
    if (oneShot != null) {
        try {
            println(agent.run(oneShot, "oneshot"))
        } catch (e: Exception) {
            log.error("one-shot run failed", e)
            println("ERROR: ${friendlyError(e, config)}")
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
            println("  ${Banner.RED}✗${Banner.RESET} ${friendlyError(e, config)}")
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

/**
 * The full agent stack shared by every entry point (TUI, one-shot, serve):
 * Koog executor/model, the file-backed vector store, memory, skills, and the
 * tool-equipped [GraphAIAgent]. Only the permission policy differs per channel,
 * so it's passed in.
 */
private class AgentStack(
    val agent: GraphAIAgent<String, String>,
    val memory: MemoryStore,
    val vectors: KoogVectorStore,
    val skillRegistry: SkillRegistry,
    val executor: PromptExecutor,
    val model: LLModel,
)

private suspend fun buildAgentStack(config: KermesConfig, permissionGuard: PermissionGuard): AgentStack {
    val executor = KoogWiring.buildPromptExecutor(config.apiKey, config.baseUrl)
    val model = KoogWiring.resolveModel(config.apiKey, config.baseUrl, config.modelId)
    val vectors = KoogWiring.buildVectorStore(
        apiKey = config.apiKey,
        baseUrl = config.baseUrl,
        storageRoot = config.vectorsRoot,
    )
    val memory = MemoryStore(config.memoryRoot, vectors)
    val skillRegistry = SkillRegistry(config.skillsRoots)

    val skillTools = SkillsToolSet(skillRegistry, config.agentCreatedSkillsRoot, permissionGuard)
    val learnerTools = LearnerToolSet(memory, vectors)
    val workdir = Paths.get(System.getProperty("user.dir"))
    val bashTools = BashToolSet(workdir = workdir, permissionGuard = permissionGuard)
    val webTools = WebSearchToolSet()
    val fileTools = FileToolSet(baseDir = workdir, permissionGuard = permissionGuard)

    val agent = KoogAgentFactory.build(
        promptExecutor = executor,
        model = model,
        config = config,
        skillTools = skillTools,
        learnerTools = learnerTools,
        bashTools = bashTools,
        webTools = webTools,
        fileTools = fileTools,
        skillManifest = skillRegistry.discoveryManifest(),
        eagerMemory = buildEagerMemory(memory),
    )
    return AgentStack(agent, memory, vectors, skillRegistry, executor, model)
}

/**
 * Headless daemon: `kermes serve`. No TUI. Hosts the scheduler and the inbound
 * Telegram gateway in one long-running process — the missing piece that lets
 * you drive the agent from Telegram (the bot was outbound-only before).
 *
 * Permission policy for a remote channel can't pop an interactive [y/n], so by
 * default any tool needing approval is DENIED (the model adapts). Set
 * KERMES_REMOTE_AUTO_APPROVE=true to trust your allow-listed chat with full
 * tool access.
 */
private suspend fun runServe(mcpPort: Int?) = coroutineScope {
    val log = LoggerFactory.getLogger("ai.kermes.app.Serve")
    val config = KermesConfig.load()
    setupDirs(config)

    val tgToken = ConfigSource.get("KERMES_TELEGRAM_BOT_TOKEN")
    val tgChat = ConfigSource.get("KERMES_TELEGRAM_CHAT_ID")
    if (tgToken.isNullOrBlank()) {
        System.err.println(
            "  ${Banner.RED}✗${Banner.RESET} No Telegram bot configured. " +
                "Run ${Banner.GOLD}kermes setup${Banner.RESET} and add a bot token + chat id, then retry."
        )
        return@coroutineScope
    }

    val autoApprove = ConfigSource.get("KERMES_REMOTE_AUTO_APPROVE")?.lowercase() == "true"
    val permissionGuard = PermissionGuard(
        PermissionGuard.Config(
            prompt = { _, _ ->
                if (autoApprove) PermissionGuard.PromptResponse.AllowSession
                else PermissionGuard.PromptResponse.Deny
            },
            dangerouslySkipAll = config.dangerouslySkipPermissions,
        )
    )

    log.info("starting Kermes serve baseUrl={} model={}", config.baseUrl, config.modelId)
    val stack = buildAgentStack(config, permissionGuard)

    // Scheduler — same wiring as the REPL, just hosted here permanently.
    val entries = ScheduleStore(config.schedulesFile).load()
    val scheduler = if (entries.isNotEmpty()) {
        val sinks = buildMap<String, ai.kermes.schedule.DeliverySink> {
            put("inbox", InboxFileSink(config.inboxRoot))
            if (!tgChat.isNullOrBlank()) put("telegram", ai.kermes.schedule.TelegramSink(tgToken, tgChat))
        }
        Scheduler(
            agentRunner = { entry -> stack.agent.run(entry.prompt, "schedule-${entry.id}") },
            sink = CompositeSink(sinks),
        ).also { it.start(entries); log.info("scheduler started with {} schedule(s)", entries.size) }
    } else null

    // Inbound Telegram gateway: only the configured chat id may drive the agent.
    val allowed = tgChat?.toLongOrNull()?.let { setOf(it) } ?: emptySet()
    val gateway = TelegramGateway(
        botToken = tgToken,
        allowedChatIds = allowed,
        onMessage = { chatId, text -> stack.agent.run(text, "telegram-$chatId") },
    )
    val botName = gateway.whoAmI()

    // Debug hook: host the read-only MCP endpoint in-process so a single
    // `kermes serve` is both the gateway AND attachable for live debugging.
    // A port clash (e.g. a separate `kermes mcp` already bound) is non-fatal.
    val mcpServer = mcpPort?.let { p ->
        runCatching { McpDebugServer.start(p) }
            .onFailure { log.warn("MCP debug endpoint not started on {}: {}", p, it.message) }
            .getOrNull()
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        scheduler?.close()
        runCatching { mcpServer?.stop(0) }
        runCatching { runBlocking { stack.vectors.flush() } }
    })

    print(
        Banner.render(
            version = KERMES_VERSION,
            model = config.modelId,
            cwd = kermesHome().toString(),
            skills = stack.skillRegistry.all().map { it.frontmatter.name },
        )
    )
    println("  ${Banner.GOLD}serve${Banner.RESET} — headless gateway running ${Banner.DIM}(Ctrl-C to stop)${Banner.RESET}")
    println("  telegram bot:  ${botName?.let { "@$it" } ?: "${Banner.RED}unreachable right now${Banner.RESET} ${Banner.DIM}(network/TLS flaky — will keep retrying)${Banner.RESET}"}")
    println("  allow-list:    ${if (allowed.isEmpty()) "${Banner.RED}ANY chat (no chat id set)${Banner.RESET}" else allowed.joinToString()}")
    println("  scheduler:     ${entries.size} schedule(s)")
    println("  tool approval: ${if (autoApprove) "auto-approve (KERMES_REMOTE_AUTO_APPROVE)" else "deny-by-default (set KERMES_REMOTE_AUTO_APPROVE=true to allow)"}")
    val mcpLine = when {
        mcpPort == null -> "${Banner.DIM}disabled (--no-mcp)${Banner.RESET}"
        mcpServer != null -> "${Banner.WHITE}http://127.0.0.1:$mcpPort/mcp${Banner.RESET} ${Banner.DIM}(read-only)${Banner.RESET}"
        else -> "${Banner.RED}port $mcpPort unavailable${Banner.RESET}"
    }
    println("  mcp debug:     $mcpLine")
    println("  message your bot on Telegram and Kermes will reply.\n")
    log.info("telegram gateway starting (bot @{}, allow-list {})", botName ?: "?", allowed)

    gateway.run() // blocks until the process is interrupted
}

/**
 * Map a raw LLM/client exception to a short, actionable line for the REPL.
 * The underlying Koog message ("Error from client: OpenRouterLLMClient") is
 * useless to an end user — translate the common provider failures into a fix.
 */
private fun friendlyError(e: Throwable, config: KermesConfig): String {
    val text = generateSequence(e) { it.cause }.joinToString("\n") { it.message ?: "" }.lowercase()
    val model = config.modelId
    val base = config.baseUrl
    return when {
        "does not support tools" in text || "support tools" in text ->
            "Model '$model' can't do tool-calling, which Kermes requires. " +
                "Switch to a tool-capable model (e.g. qwen2.5, llama3.1, mistral-nemo) — " +
                "run `kermes setup` or set KERMES_MODEL."

        "model not found" in text || "try pulling" in text ||
            ("404" in text && "not found" in text) ->
            "Model '$model' isn't available at $base. For Ollama, run `ollama pull $model` first."

        "connection refused" in text || "failed to connect" in text ||
            "connectexception" in text || "no route to host" in text ->
            "Can't reach the LLM server at $base. Is it running? For Ollama: `ollama serve`."

        "401" in text || "unauthorized" in text || "invalid api key" in text ->
            "The API key was rejected by $base. Re-run `kermes setup` to update it."

        else -> e.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(200)
            ?: (e::class.simpleName ?: "unknown error")
    }
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
