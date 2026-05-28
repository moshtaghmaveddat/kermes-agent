package ai.kermes.app

import ai.kermes.core.feature.PermissionGuard

/**
 * MVP approval UI — prints the tool call and asks the user via stdin.
 * Phase 2 will replace this with a mordant-backed interactive picker.
 */
object ConsoleApprover {

    fun ask(tool: String, args: String): PermissionGuard.PromptResponse {
        println()
        println("─── permission ─────────────────────────────")
        println("tool: $tool")
        println("args: ${args.take(400).let { if (args.length > 400) "$it…[${args.length - 400} more]" else it }}")
        println()
        println("[o] once   [s] this session   [a] always   [d] deny")
        print(" choice: ")
        return when (readlnOrNull()?.trim()?.lowercase()) {
            "s", "session" -> PermissionGuard.PromptResponse.AllowSession
            "a", "always" -> PermissionGuard.PromptResponse.AlwaysAllow
            "d", "deny", "n", "no" -> PermissionGuard.PromptResponse.Deny
            else -> PermissionGuard.PromptResponse.AllowOnce
        }
    }
}
