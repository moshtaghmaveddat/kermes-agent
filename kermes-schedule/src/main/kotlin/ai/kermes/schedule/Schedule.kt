package ai.kermes.schedule

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Serializable
data class ScheduleEntry(
    val id: String,
    val cron: String,
    val prompt: String,
    /** Delivery sink name. "inbox" for MVP; "telegram" / "discord" later. */
    val deliver: String = "inbox",
    /** Optional human-readable description. */
    val notes: String? = null,
)

class ScheduleStore(private val file: Path) {

    fun load(): List<ScheduleEntry> {
        if (!file.exists()) return emptyList()
        val text = file.readText(Charsets.UTF_8).trim()
        if (text.isEmpty()) return emptyList()
        return Yaml.default.decodeFromString(ListSerializer(ScheduleEntry.serializer()), text)
    }

    fun save(entries: List<ScheduleEntry>) {
        val yaml = Yaml.default.encodeToString(ListSerializer(ScheduleEntry.serializer()), entries)
        file.writeText(yaml, Charsets.UTF_8)
    }
}
