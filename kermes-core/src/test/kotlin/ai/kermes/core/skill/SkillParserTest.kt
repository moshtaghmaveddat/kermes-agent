package ai.kermes.core.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SkillParserTest {

    @Test
    fun `parses minimal valid skill`(@TempDir tmp: Path) {
        val dir = tmp.resolve("hello-world").also { it.createDirectories() }
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: hello-world
            description: A greeting skill used for testing the parser.
            ---

            # Body content goes here.
            """.trimIndent()
        )

        val skill = SkillParser.parse(dir)
        assertEquals("hello-world", skill.frontmatter.name)
        assertEquals("A greeting skill used for testing the parser.", skill.frontmatter.description)
        assertNotNull(skill.body)
    }

    @Test
    fun `parses skill with optional fields`(@TempDir tmp: Path) {
        val dir = tmp.resolve("pdf-tools").also { it.createDirectories() }
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: pdf-tools
            description: PDF utilities for extraction and merging.
            license: Apache-2.0
            metadata:
              author: example
              version: "1.0"
            allowed-tools: Bash(git:*) Read
            ---

            Body.
            """.trimIndent()
        )

        val skill = SkillParser.parse(dir)
        assertEquals("Apache-2.0", skill.frontmatter.license)
        assertEquals("example", skill.frontmatter.metadata["author"])
        assertEquals("Bash(git:*) Read", skill.frontmatter.allowedTools)
    }

    @Test
    fun `rejects when name does not match directory`(@TempDir tmp: Path) {
        val dir = tmp.resolve("actual-dir").also { it.createDirectories() }
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: different-name
            description: Mismatched name.
            ---
            body
            """.trimIndent()
        )

        assertThrows(SkillValidationException::class.java) { SkillParser.parse(dir) }
    }

    @Test
    fun `rejects invalid name format`(@TempDir tmp: Path) {
        val dir = tmp.resolve("BadName").also { it.createDirectories() }
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: BadName
            description: Uppercase not allowed.
            ---
            body
            """.trimIndent()
        )

        assertThrows(SkillValidationException::class.java) { SkillParser.parse(dir) }
    }

    @Test
    fun `rejects missing frontmatter`(@TempDir tmp: Path) {
        val dir = tmp.resolve("no-fm").also { it.createDirectories() }
        dir.resolve("SKILL.md").writeText("# Just markdown, no frontmatter.")

        assertThrows(SkillValidationException::class.java) { SkillParser.parse(dir) }
    }

    @Test
    fun `rejects oversize description`(@TempDir tmp: Path) {
        val dir = tmp.resolve("big-desc").also { it.createDirectories() }
        val giantDesc = "x".repeat(1025)
        dir.resolve("SKILL.md").writeText(
            """
            ---
            name: big-desc
            description: $giantDesc
            ---
            body
            """.trimIndent()
        )

        assertThrows(SkillValidationException::class.java) { SkillParser.parse(dir) }
    }
}
