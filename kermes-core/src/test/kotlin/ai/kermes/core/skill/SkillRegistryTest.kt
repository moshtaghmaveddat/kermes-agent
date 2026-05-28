package ai.kermes.core.skill

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SkillRegistryTest {

    @Test
    fun `discovers skills across multiple roots with project taking precedence`(@TempDir tmp: Path) {
        val projectRoot = tmp.resolve("project/.kermes/skills").also { it.createDirectories() }
        val userRoot = tmp.resolve("user/.kermes/skills").also { it.createDirectories() }

        writeSkill(projectRoot.resolve("shared"), "shared", "Project variant")
        writeSkill(userRoot.resolve("shared"), "shared", "User variant")
        writeSkill(userRoot.resolve("user-only"), "user-only", "Lives only in user root")

        val registry = SkillRegistry(listOf(
            SkillRegistry.ScopedRoot(SkillScope.Project, projectRoot),
            SkillRegistry.ScopedRoot(SkillScope.User, userRoot),
        ))

        assertEquals(2, registry.all().size)
        // Project root wins
        assertEquals("Project variant", registry.get("shared")?.frontmatter?.description)
        assertEquals(SkillScope.Project, registry.scopeOf("shared"))
        assertNotNull(registry.get("user-only"))
        assertEquals(SkillScope.User, registry.scopeOf("user-only"))
    }

    @Test
    fun `silently skips invalid skills`(@TempDir tmp: Path) {
        val root = tmp.resolve("skills").also { it.createDirectories() }
        writeSkill(root.resolve("good"), "good", "Valid skill")
        // Invalid: name doesn't match directory
        root.resolve("bad").createDirectories()
        root.resolve("bad/SKILL.md").writeText("""
            ---
            name: actually-different
            description: name doesn't match dir
            ---
            body
        """.trimIndent())

        val registry = SkillRegistry(listOf(
            SkillRegistry.ScopedRoot(SkillScope.User, root),
        ))

        assertEquals(1, registry.all().size)
        assertNotNull(registry.get("good"))
        assertNull(registry.get("actually-different"))
    }

    @Test
    fun `discovery manifest format is one line per skill`(@TempDir tmp: Path) {
        val root = tmp.resolve("skills").also { it.createDirectories() }
        writeSkill(root.resolve("first"), "first", "First description")
        writeSkill(root.resolve("second"), "second", "Second description")

        val registry = SkillRegistry(listOf(
            SkillRegistry.ScopedRoot(SkillScope.User, root),
        ))

        val manifest = registry.discoveryManifest()
        assertTrue(manifest.contains("**first**: First description"))
        assertTrue(manifest.contains("**second**: Second description"))
    }

    private fun writeSkill(dir: Path, name: String, description: String) {
        dir.createDirectories()
        dir.resolve("SKILL.md").writeText("""
            ---
            name: $name
            description: $description
            ---
            body for $name
        """.trimIndent())
    }
}
