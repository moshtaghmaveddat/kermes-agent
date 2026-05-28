package ai.kermes.app

/**
 * The Kermes TUI home screen: a KERMES wordmark, a red Pac-Man mascot
 * (a nod to the Kotlin mark, in Kermes red), and a Hermes-style info panel
 * listing tools + skills. Colors are raw ANSI; honors NO_COLOR.
 */
object Banner {
    private val color = System.getenv("NO_COLOR").isNullOrEmpty()
    private fun c(code: String) = if (color) code else ""

    val RESET = c("[0m")
    val RED = c("[38;5;196m")        // kermes red
    val DRED = c("[38;5;160m")       // deep red (shadow)
    val GOLD = c("[38;5;214m")
    val DIM = c("[2m")
    val WHITE = c("[38;5;252m")
    private val BOLD = c("[1m")

    // KERMES — ANSI Shadow figlet.
    private val wordmark = listOf(
        "██╗  ██╗███████╗██████╗ ███╗   ███╗███████╗███████╗",
        "██║ ██╔╝██╔════╝██╔══██╗████╗ ████║██╔════╝██╔════╝",
        "█████╔╝ █████╗  ██████╔╝██╔████╔██║█████╗  ███████╗",
        "██╔═██╗ ██╔══╝  ██╔══██╗██║╚██╔╝██║██╔══╝  ╚════██║",
        "██║  ██╗███████╗██║  ██║██║ ╚═╝ ██║███████╗███████║",
        "╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝╚═╝     ╚═╝╚══════╝╚══════╝",
    )

    // Red Pac-Man, mouth open toward the panel, munching pellets.
    private val pacman = listOf(
        "    ▟██████▙",
        "  ▟██████████▙",
        " ███████▛",
        " █████▛        ● ● ●",
        " ███████▙",
        "  ▜██████████▛",
        "    ▜██████▛",
    )

    // Fixed tool surface (matches the registered tool sets).
    private val tools = listOf(
        "skills" to "list_skills, load_skill, read_skill_file, run_skill_script, create_skill",
        "memory" to "remember_user, set_preference, remember_context, record_episode, recall, …",
        "shell"  to "bash",
        "web"    to "web_search",
        "file"   to "read_file, write_file, edit_file, list_directory",
    )
    private val toolCount = 5 + 7 + 1 + 1 + 4

    fun render(version: String, model: String, cwd: String, skills: List<String>): String {
        val sb = StringBuilder()

        // ---- wordmark ----
        sb.append('\n')
        wordmark.forEachIndexed { i, line ->
            val shade = if (i >= 4) DRED else RED
            sb.append("  ").append(shade).append(line).append(RESET).append('\n')
        }
        sb.append('\n')

        // ---- left column (pacman + meta) ----
        val left = buildList {
            addAll(pacman.map { it to RED })
            add("" to "")
            add(model to GOLD)
            add(cwd.let { if (it.length > 34) "…" + it.takeLast(33) else it } to DIM)
        }
        val leftW = left.maxOf { it.first.length }

        // ---- right column (panel) ----
        val right = buildList {
            add("${BOLD}${GOLD}Kermes Agent v$version$RESET  ${DIM}· kermes-agent$RESET")
            add("")
            add("${GOLD}Available Tools$RESET")
            tools.forEach { (cat, list) ->
                add("  ${GOLD}${cat.padEnd(7)}$RESET ${WHITE}$list$RESET")
            }
            add("")
            add("${GOLD}Available Skills$RESET")
            if (skills.isEmpty()) {
                add("  ${DIM}(none yet — drop a SKILL.md in ~/.kermes/skills)$RESET")
            } else {
                val shown = skills.take(8).joinToString(", ")
                val more = if (skills.size > 8) ", … (+${skills.size - 8})" else ""
                add("  ${WHITE}$shown$more$RESET")
            }
            add("")
            add("${DIM}$toolCount tools · ${skills.size} skills · /help for commands$RESET")
        }

        // ---- zip the two columns ----
        val rows = maxOf(left.size, right.size)
        for (i in 0 until rows) {
            val (lText, lColor) = left.getOrElse(i) { "" to "" }
            val lPadded = lText.padEnd(leftW)
            val lOut = if (lColor.isEmpty()) lPadded else "$lColor$lPadded$RESET"
            val rOut = right.getOrElse(i) { "" }
            sb.append("  ").append(lOut).append("   ").append(rOut).append('\n')
        }

        // ---- footer ----
        sb.append('\n')
        sb.append("  ${WHITE}Welcome to Kermes.$RESET ${DIM}Type a message, or /help for commands.$RESET\n")
        sb.append("  ${DIM}Tip: `kermes -q \"…\"` runs a single prompt and exits.$RESET\n")
        return sb.toString()
    }
}
