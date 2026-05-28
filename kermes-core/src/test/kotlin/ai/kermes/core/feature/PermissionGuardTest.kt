package ai.kermes.core.feature

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PermissionGuardTest {

    @Test
    fun `read-only tools are auto-allowed by default`() = runBlocking {
        val guard = PermissionGuard(
            PermissionGuard.Config(prompt = { _, _ -> error("should not prompt") })
        )
        val decision = guard.check("__read_file__", "/some/path")
        assertTrue(decision is PermissionGuard.Decision.Allow)
    }

    @Test
    fun `unknown tools fall through to prompt`() = runBlocking {
        var prompted = false
        val guard = PermissionGuard(
            PermissionGuard.Config(
                prompt = { _, _ -> prompted = true; PermissionGuard.PromptResponse.AllowOnce }
            )
        )
        val decision = guard.check("custom_tool", "x")
        assertTrue(prompted)
        assertTrue(decision is PermissionGuard.Decision.Allow)
    }

    @Test
    fun `deny pattern blocks unconditionally`() = runBlocking {
        val guard = PermissionGuard(
            PermissionGuard.Config(
                denyPatterns = listOf(PermissionGuard.RulePattern("bash:rm -rf*")),
                prompt = { _, _ -> error("should not reach prompt") },
            )
        )
        val decision = guard.check("bash", "rm -rf /tmp/foo")
        assertTrue(decision is PermissionGuard.Decision.Deny)
    }

    @Test
    fun `allow pattern auto-allows`() = runBlocking {
        val guard = PermissionGuard(
            PermissionGuard.Config(
                allowPatterns = listOf(PermissionGuard.RulePattern("bash:git*")),
                prompt = { _, _ -> error("should not reach prompt") },
            )
        )
        val decision = guard.check("bash", "git status")
        assertTrue(decision is PermissionGuard.Decision.Allow)
    }

    @Test
    fun `allowSession remembers within a single guard`() = runBlocking {
        var promptCount = 0
        val guard = PermissionGuard(
            PermissionGuard.Config(
                prompt = { _, _ ->
                    promptCount++
                    PermissionGuard.PromptResponse.AllowSession
                }
            )
        )
        guard.check("bash", "ls /tmp")
        guard.check("bash", "ls /tmp")
        assertEquals(1, promptCount, "second identical call should use session memory")
    }

    @Test
    fun `dangerouslySkipAll bypasses everything`() = runBlocking {
        val guard = PermissionGuard(
            PermissionGuard.Config(
                denyPatterns = listOf(PermissionGuard.RulePattern("bash:*")),
                prompt = { _, _ -> error("should not prompt") },
                dangerouslySkipAll = true,
            )
        )
        val decision = guard.check("bash", "rm -rf /")
        assertTrue(decision is PermissionGuard.Decision.Allow)
    }

    @Test
    fun `glob pattern matches arg substrings`() {
        val pat = PermissionGuard.RulePattern("write_file:~/.kermes/**")
        assertTrue(pat.matches("write_file", "~/.kermes/memory/user.md"))
    }
}
