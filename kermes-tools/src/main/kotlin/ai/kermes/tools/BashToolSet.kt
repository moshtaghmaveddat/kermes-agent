package ai.kermes.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Single-command bash execution. Permission decisions are made *before* this
 * runs, by the PermissionGuard Feature. This class only does the execution.
 *
 * Output is capped to avoid blowing the LLM context.
 */
@LLMDescription("Shell execution — run a bash command and get its output.")
class BashToolSet(
    private val workdir: Path,
    private val permissionGuard: ai.kermes.core.feature.PermissionGuard? = null,
    private val timeoutSeconds: Long = 60,
    private val maxOutputBytes: Int = 16_384,
) : ToolSet {

    private val log = LoggerFactory.getLogger(BashToolSet::class.java)

    @Tool
    @LLMDescription(
        "Execute a bash command in the current working directory. " +
        "Combined stdout+stderr is returned along with the exit code. " +
        "Use for filesystem inspection (ls, cat, grep, find), git, build tools, etc. " +
        "Do NOT use for: file edits — use the dedicated file tools. " +
        "Long-running or interactive commands will time out."
    )
    fun bash(
        @LLMDescription("The bash command to run, exactly as you'd type at a shell prompt.") command: String,
    ): String = runBlocking {
        log.info("bash: {}", command)
        permissionGuard?.check("bash", command)?.let { decision ->
            if (decision is ai.kermes.core.feature.PermissionGuard.Decision.Deny) {
                return@runBlocking "PERMISSION DENIED (bash): ${decision.reason}. The command was not run."
            }
        }
        try {
            val proc = ProcessBuilder("bash", "-lc", command)
                .directory(workdir.toFile())
                .redirectErrorStream(true)
                .start()

            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return@runBlocking "TIMEOUT after ${timeoutSeconds}s\ncommand: $command"
            }

            val output = proc.inputStream.bufferedReader().readText()
            val truncated = if (output.length > maxOutputBytes) {
                output.substring(0, maxOutputBytes) + "\n[... truncated ${output.length - maxOutputBytes} bytes ...]"
            } else output

            "exit=${proc.exitValue()}\n---\n$truncated"
        } catch (e: Exception) {
            "ERROR: ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
