package ai.kermes.tools

import ai.kermes.core.feature.PermissionGuard
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Scoped filesystem tools. Koog 1.0 does NOT ship usable built-in file tools on
 * the koog-agents umbrella classpath (the documented ReadFileTool/WriteFileTool
 * names aren't present), so Kermes provides its own.
 *
 * - read_file / list_directory are read-only (no permission prompt).
 * - write_file / edit_file mutate and are gated through [PermissionGuard].
 *
 * All paths resolve against [baseDir]; a path escaping it (via ../ etc.) is
 * rejected, so the agent can't wander outside the working tree.
 */
@LLMDescription("Filesystem tools — read, write, edit text files and list directories within the working tree.")
class FileToolSet(
    private val baseDir: Path,
    private val permissionGuard: PermissionGuard? = null,
    private val maxReadBytes: Int = 64_000,
) : ToolSet {

    private val log = LoggerFactory.getLogger(FileToolSet::class.java)
    private val root = baseDir.normalize().absolute()

    @Tool
    @LLMDescription(
        "Read a UTF-8 text file and return its contents. Path is relative to the working " +
        "directory. Large files are truncated. Read-only — no approval needed."
    )
    fun read_file(
        @LLMDescription("File path, relative to the working directory.") path: String,
    ): String {
        val p = resolve(path) ?: return "ERROR: path escapes the working tree — $path"
        if (!p.exists()) return "ERROR: not found — $path"
        if (!p.isRegularFile()) return "ERROR: not a file — $path"
        val text = p.readText(Charsets.UTF_8)
        return if (text.length > maxReadBytes) {
            text.take(maxReadBytes) + "\n…[truncated ${text.length - maxReadBytes} more chars]"
        } else text
    }

    @Tool
    @LLMDescription(
        "List the entries of a directory (one level). Marks directories with a trailing '/'. " +
        "Path is relative to the working directory. Read-only — no approval needed."
    )
    fun list_directory(
        @LLMDescription("Directory path, relative to the working directory. Use '.' for the root.") path: String = ".",
    ): String {
        val p = resolve(path) ?: return "ERROR: path escapes the working tree — $path"
        if (!p.exists()) return "ERROR: not found — $path"
        if (!p.isDirectory()) return "ERROR: not a directory — $path"
        return Files.list(p).use { stream ->
            stream.sorted()
                .map { entry -> if (entry.isDirectory()) "${entry.name}/" else entry.name }
                .toList()
                .joinToString("\n")
                .ifEmpty { "(empty)" }
        }
    }

    @Tool
    @LLMDescription(
        "Write UTF-8 text to a file, creating parent directories and overwriting any existing " +
        "content. Requires approval. Returns the byte count written."
    )
    fun write_file(
        @LLMDescription("File path, relative to the working directory.") path: String,
        @LLMDescription("Full text content to write.") content: String,
    ): String {
        val p = resolve(path) ?: return "ERROR: path escapes the working tree — $path"
        gate("write_file", "$path (${content.length} chars)")?.let { return it }
        Files.createDirectories(p.parent)
        p.writeText(content, Charsets.UTF_8)
        log.info("write_file: {} ({} bytes)", path, content.toByteArray().size)
        return "OK: wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to $path"
    }

    @Tool
    @LLMDescription(
        "Make a single exact text replacement in a file. 'old' must appear exactly once. " +
        "Requires approval. Use this for targeted edits instead of rewriting whole files."
    )
    fun edit_file(
        @LLMDescription("File path, relative to the working directory.") path: String,
        @LLMDescription("Exact text to find. Must occur exactly once in the file.") old: String,
        @LLMDescription("Replacement text.") new: String,
    ): String {
        val p = resolve(path) ?: return "ERROR: path escapes the working tree — $path"
        if (!p.exists()) return "ERROR: not found — $path"
        val text = p.readText(Charsets.UTF_8)
        val count = countOccurrences(text, old)
        if (count == 0) return "ERROR: 'old' text not found in $path"
        if (count > 1) return "ERROR: 'old' text appears $count times in $path — make it unique"
        gate("edit_file", path)?.let { return it }
        p.writeText(text.replaceFirst(old, new), Charsets.UTF_8)
        log.info("edit_file: {}", path)
        return "OK: edited $path"
    }

    /** Resolve a user path against the base dir, rejecting escapes. */
    private fun resolve(path: String): Path? {
        val resolved = root.resolve(path).normalize().absolute()
        return if (resolved.startsWith(root)) resolved else null
    }

    /** Returns a denial message if the guard rejects the call, else null (allowed). */
    private fun gate(tool: String, args: String): String? {
        val guard = permissionGuard ?: return null
        val decision = runBlocking { guard.check(tool, args) }
        return if (decision is PermissionGuard.Decision.Deny) {
            "PERMISSION DENIED ($tool): ${decision.reason}. No changes were made."
        } else null
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var idx = haystack.indexOf(needle)
        var n = 0
        while (idx >= 0) {
            n++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return n
    }
}
