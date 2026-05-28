package ai.kermes.app

import ai.kermes.core.feature.PermissionGuard
import ai.kermes.core.memory.MemoryStore
import ai.kermes.core.skill.SkillRegistry
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.walk
import kotlin.io.path.writeText

const val KERMES_VERSION = "0.1.0"

/**
 * Terminal command surface. Three categories:
 *  - lifecycle/management: init, status, version, help (no API key required)
 *  - interaction: default REPL, and one-shot via `-q`
 *  - in-session slash commands: handled by [Slash] inside the REPL
 */
object Cli {

    sealed interface Command {
        data object Help : Command
        data object Version : Command
        data object Init : Command
        data object Setup : Command
        data object Status : Command
        /** query == null → interactive REPL; non-null → one-shot. */
        data class Chat(val query: String?) : Command
    }

    fun parse(args: Array<String>): Command = when (args.firstOrNull()) {
        null -> Command.Chat(null)
        "help", "--help", "-h" -> Command.Help
        "version", "--version", "-v" -> Command.Version
        "init" -> Command.Init
        "setup" -> Command.Setup
        "status" -> Command.Status
        "chat" -> Command.Chat(null)
        "-q", "--query" -> Command.Chat(args.drop(1).joinToString(" ").ifBlank { null })
        else -> {
            // bare prompt treated as one-shot: `kermes "what time is it"`
            if (args.first().startsWith("-")) Command.Help
            else Command.Chat(args.joinToString(" "))
        }
    }

    private fun kermesHome(): Path = ai.kermes.app.kermesHome()

    fun printUsage() = println(
        """
        Kermes $KERMES_VERSION — a Kotlin/JVM agent on Koog.

        USAGE
          kermes                     Start the interactive REPL
          kermes -q "<prompt>"       One-shot: run a single prompt and print the reply
          kermes setup               Interactive setup (LLM provider, API key, Telegram)
          kermes init                Bootstrap ~/.kermes (dirs, sample skill, schedules)
          kermes status              Show config + health (no network calls)
          kermes version             Print version
          kermes help                Show this help

        ENVIRONMENT
          KERMES_API_KEY             API key (or OPENROUTER_API_KEY / OPENAI_API_KEY)
          KERMES_BASE_URL            OpenAI-compatible base URL (default: OpenRouter)
          KERMES_MODEL               Model id (default: openai/gpt-4o)
          KERMES_BUNDLED_SKILLS      Path to bundled skills (default: ./skills)

        IN-SESSION SLASH COMMANDS
          /help /skills /memory /inbox /new /model /yolo /quit
        """.trimIndent()
    )

    fun printVersion() = println("kermes $KERMES_VERSION")

    /** Bootstrap the home directory. No API key required. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun runInit() {
        val home = kermesHome()
        val dirs = listOf("skills", "memory", "vectors", "checkpoints", "inbox")
        dirs.forEach { home.resolve(it).createDirectories() }

        // Seed the bundled example skill into the user skills dir, if available.
        val bundled = System.getenv("KERMES_BUNDLED_SKILLS")?.let { Paths.get(it) }
            ?: Paths.get("").toAbsolutePath().resolve("skills")
        val example = bundled.resolve("example")
        val target = home.resolve("skills/example")
        if (example.exists() && !target.exists()) {
            example.walk().forEach { src ->
                val dest = target.resolve(example.relativize(src).toString())
                if (src.isDirectory()) dest.createDirectories()
                else { dest.parent.createDirectories(); src.copyTo(dest, overwrite = false) }
            }
            println("seeded sample skill → ${target}")
        }

        val schedules = home.resolve("schedules.yaml")
        if (!schedules.exists()) {
            schedules.writeText("# Kermes scheduled tasks. Example:\n# - id: morning-brief\n#   cron: \"0 8 * * *\"\n#   prompt: \"Summarize my unread notifications.\"\n#   deliver: inbox\n")
        }

        println(
            """
            Initialized $home

            Next:
              export KERMES_API_KEY=sk-...     # OpenRouter / OpenAI key
              kermes                            # start chatting
            """.trimIndent()
        )
    }

    /**
     * Interactive setup wizard. Writes ~/.kermes/config (dotenv-style) with the
     * LLM provider, API key, model, and optional Telegram delivery settings.
     * Reads from /dev/tty so it works even under `curl … | bash`.
     */
    fun runSetup() {
        runInit()  // ensure dirs exist first

        val tty = openTty()
        fun ask(prompt: String, default: String? = null, secret: Boolean = false): String {
            val suffix = if (default != null) " [$default]" else ""
            print("$prompt$suffix: ")
            System.out.flush()
            val raw = if (secret) readSecret(tty) else (tty?.readLine() ?: readlnOrNull())
            val v = raw?.trim().orEmpty()
            return v.ifEmpty { default ?: "" }
        }

        println("\n── Kermes setup ─────────────────────────────")
        println("Configure your LLM provider. Press Enter to accept [defaults].\n")

        val provider = ask("Provider (openrouter / openai / ollama / custom)", "openrouter").lowercase()
        val (defBase, defModel) = when (provider) {
            "openai" -> "https://api.openai.com/v1" to "gpt-4o"
            "ollama" -> "http://localhost:11434/v1" to "llama3.1"
            "custom" -> "" to "gpt-4o"
            else -> "https://openrouter.ai/api/v1" to "openai/gpt-4o"
        }
        val baseUrl = ask("Base URL", defBase)
        val model = ask("Model id", defModel)
        val apiKey = if (provider == "ollama") ask("API key (blank for local Ollama)", "ollama")
                     else ask("API key", secret = true)

        print("\nSet up Telegram delivery for scheduled tasks? (y/N): ")
        val wantTg = (tty?.readLine() ?: readlnOrNull())?.trim()?.lowercase() == "y"
        var tgToken = ""
        var tgChat = ""
        if (wantTg) {
            println("Create a bot with @BotFather to get a token; message your bot, then")
            println("get your chat id from https://api.telegram.org/bot<token>/getUpdates")
            tgToken = ask("Telegram bot token", secret = true)
            tgChat = ask("Telegram chat id")
        }

        val lines = buildList {
            add("# Kermes config — written by `kermes setup`. Env vars override these.")
            add("KERMES_BASE_URL=$baseUrl")
            add("KERMES_MODEL=$model")
            if (apiKey.isNotBlank()) add("KERMES_API_KEY=$apiKey")
            if (tgToken.isNotBlank()) add("KERMES_TELEGRAM_BOT_TOKEN=$tgToken")
            if (tgChat.isNotBlank()) add("KERMES_TELEGRAM_CHAT_ID=$tgChat")
        }
        ConfigSource.configFile.writeText(lines.joinToString("\n") + "\n")
        // tighten perms — the file holds an API key
        runCatching { ConfigSource.configFile.toFile().setReadable(false, false); ConfigSource.configFile.toFile().setReadable(true, true) }

        println("\nSaved → ${ConfigSource.configFile}")
        if (apiKey.isBlank()) println("(no API key entered — set KERMES_API_KEY or re-run setup before chatting)")
        if (wantTg) println("Telegram: scheduled tasks with `deliver: telegram` will post to chat $tgChat.")
        println("\nRun `kermes` to start.")
    }

    private fun openTty(): java.io.BufferedReader? = runCatching {
        java.io.File("/dev/tty").takeIf { it.exists() }?.let { java.io.BufferedReader(java.io.FileReader(it)) }
    }.getOrNull()

    private fun readSecret(tty: java.io.BufferedReader?): String? {
        val console = System.console()
        return if (console != null) String(console.readPassword()) else (tty?.readLine() ?: readlnOrNull())
    }

    /** Non-network health check. No API key required. */
    fun runStatus() {
        val home = kermesHome()
        // Reflect what the agent will actually see: env → ~/.kermes/config.
        val keySet = sequenceOf("KERMES_API_KEY", "OPENROUTER_API_KEY", "OPENAI_API_KEY")
            .any { !ConfigSource.get(it).isNullOrBlank() }
        val keySource = when {
            sequenceOf("KERMES_API_KEY", "OPENROUTER_API_KEY", "OPENAI_API_KEY").any { !System.getenv(it).isNullOrBlank() } -> "env"
            keySet -> "config file"
            else -> "none"
        }
        val model = ConfigSource.get("KERMES_MODEL") ?: "openai/gpt-4o"
        val baseUrl = ConfigSource.get("KERMES_BASE_URL") ?: "https://openrouter.ai/api/v1"

        val roots = buildList {
            val proj = Paths.get("").toAbsolutePath().resolve(".kermes/skills")
            if (proj.exists()) add(proj)
            add(home.resolve("skills"))
            val bundled = System.getenv("KERMES_BUNDLED_SKILLS")?.let { Paths.get(it) }
                ?: Paths.get("").toAbsolutePath().resolve("skills")
            if (bundled.exists()) add(bundled)
        }
        val skillCount = roots.filter { it.exists() && it.isDirectory() }
            .sumOf { root ->
                runCatching {
                    java.nio.file.Files.list(root).use { s ->
                        s.filter { it.isDirectory() && it.resolve("SKILL.md").exists() }.count()
                    }
                }.getOrDefault(0L)
            }

        println(
            """
            Kermes $KERMES_VERSION — status

              API key:     ${if (keySet) "set ✓ (from $keySource)" else "MISSING ✗  (run: kermes setup)"}
              model:       $model
              base URL:    $baseUrl
              home:        $home ${if (home.exists()) "✓" else "✗ (run: kermes init)"}
              skills dir:  ${home.resolve("skills").let { "$it ${if (it.exists()) "✓" else "✗"}" }}
              memory dir:  ${home.resolve("memory").let { "$it ${if (it.exists()) "✓" else "✗"}" }}
              schedules:   ${home.resolve("schedules.yaml").let { if (it.exists()) "$it ✓" else "none" }}
              skills found: $skillCount across ${roots.size} root(s)
            """.trimIndent()
        )
    }
}

/** In-session slash command handling. */
object Slash {
    enum class Outcome { Handled, Exit, NotACommand }

    /** Holds mutable session state the REPL owns. */
    class Session(var id: String)

    suspend fun handle(
        line: String,
        registry: SkillRegistry,
        memory: MemoryStore,
        guard: PermissionGuard,
        modelId: String,
        session: Session,
    ): Outcome {
        if (!line.startsWith("/")) return Outcome.NotACommand
        val parts = line.removePrefix("/").trim().split(Regex("\\s+"), limit = 2)
        return when (parts[0].lowercase()) {
            "quit", "exit" -> Outcome.Exit
            "help" -> { printHelp(); Outcome.Handled }
            "skills" -> {
                val skills = registry.all()
                println(if (skills.isEmpty()) "(no skills loaded)" else registry.discoveryManifest())
                Outcome.Handled
            }
            "memory" -> {
                val user = memory.userFacts()
                val prefs = memory.preferences()
                if (user.isEmpty() && prefs.isEmpty()) println("(nothing remembered yet)")
                else {
                    if (user.isNotEmpty()) { println("Identity:"); user.forEach { (k, v) -> println("  $k: $v") } }
                    if (prefs.isNotEmpty()) { println("Preferences:"); prefs.forEach { (k, v) -> println("  $k: $v") } }
                }
                Outcome.Handled
            }
            "inbox" -> { showInbox(); Outcome.Handled }
            "new" -> {
                session.id = "tui-" + System.currentTimeMillis()
                println("started a new session (${session.id})")
                Outcome.Handled
            }
            "model" -> { println("model: $modelId"); Outcome.Handled }
            "yolo" -> {
                val on = guard.toggleYolo()
                println(if (on) "YOLO on — permission prompts disabled" else "YOLO off — prompts re-enabled")
                Outcome.Handled
            }
            else -> { println("unknown command: /${parts[0]} (try /help)"); Outcome.Handled }
        }
    }

    private fun printHelp() = println(
        """
        Slash commands:
          /help     show this help
          /skills   list loaded skills
          /memory   show what Kermes remembers about you
          /inbox    show scheduled-task results
          /new      start a fresh conversation
          /model    show the current model
          /yolo     toggle permission prompts on/off
          /quit     exit
        """.trimIndent()
    )

    private fun showInbox() {
        val inbox = Paths.get(System.getProperty("user.home")).resolve(".kermes/inbox")
        if (!inbox.exists()) { println("(inbox empty)"); return }
        val files = runCatching {
            java.nio.file.Files.list(inbox).use { it.sorted().toList() }
        }.getOrDefault(emptyList())
        if (files.isEmpty()) { println("(inbox empty)"); return }
        println("Inbox (${files.size}):")
        files.forEach { println("  - ${it.name}") }
        println("(open files under $inbox to read)")
    }
}
