package ai.kermes.schedule

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Where a scheduled run's output lands. */
fun interface DeliverySink {
    suspend fun deliver(entry: ScheduleEntry, ranAt: Instant, output: String)
}

/** MVP sink — one markdown file per run under `~/.kermes/inbox/`. */
class InboxFileSink(private val inboxRoot: Path) : DeliverySink {

    init { inboxRoot.createDirectories() }

    override suspend fun deliver(entry: ScheduleEntry, ranAt: Instant, output: String) {
        val ts = ranAt.epochSecond
        val file = inboxRoot.resolve("${entry.id}-$ts.md")
        file.writeText(
            buildString {
                appendLine("---")
                appendLine("schedule: ${entry.id}")
                appendLine("ran_at: $ranAt")
                appendLine("cron: ${entry.cron}")
                appendLine("---")
                appendLine()
                appendLine("# ${entry.id}")
                appendLine()
                appendLine("**Prompt:** ${entry.prompt}")
                appendLine()
                appendLine("## Output")
                appendLine()
                appendLine(output)
            },
            Charsets.UTF_8,
        )
    }
}

/** Composite sink — fan out to multiple delivery targets. */
class CompositeSink(private val sinks: Map<String, DeliverySink>) : DeliverySink {
    override suspend fun deliver(entry: ScheduleEntry, ranAt: Instant, output: String) {
        val sink = sinks[entry.deliver]
            ?: throw IllegalStateException("no sink registered for '${entry.deliver}'")
        sink.deliver(entry, ranAt, output)
    }
}

/** Count unread items in the inbox. Used by the TUI on launch. */
class InboxReader(private val inboxRoot: Path) {
    fun unread(): List<Path> {
        if (!inboxRoot.toFile().exists()) return emptyList()
        return inboxRoot.toFile()
            .listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.map { it.toPath() }
            ?: emptyList()
    }
}
