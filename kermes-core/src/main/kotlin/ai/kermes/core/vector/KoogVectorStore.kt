package ai.kermes.core.vector

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * File-backed in-process vector store. Wraps a Koog [Embedder] to compute
 * embeddings; stores rows in memory and serializes the whole index to a JSON
 * file synchronously after each write.
 *
 * Koog 1.0's `rag-base` ships storage interfaces and search-request types
 * but no concrete vector-storage implementation — so we provide one. Linear
 * cosine scan is fine for the MVP scale of hundreds-to-low-thousands of
 * vectors. For larger deployments, implement [VectorStore] against an
 * external vector DB (Qdrant, pgvector).
 */
class KoogVectorStore(
    private val embedder: Embedder,
    private val storageRoot: Path,
) : VectorStore {

    private val log = LoggerFactory.getLogger(KoogVectorStore::class.java)
    private val lock = Mutex()
    private val rows: MutableMap<String, Row> = LinkedHashMap()
    private val indexFile = storageRoot.resolve("index.json")
    private var dirty = false

    init {
        storageRoot.createDirectories()
        loadFromDisk()
    }

    override suspend fun upsert(doc: KermesDoc) {
        // Embeddings may be unavailable (e.g. Ollama with no local embed model).
        // The caller's markdown files are the source of truth and eager memory is
        // file-based, so degrade gracefully: skip indexing rather than failing the
        // whole memory write. Only semantic `recall` is affected.
        val vector = try {
            embedder.embed(doc.text)
        } catch (e: Exception) {
            log.warn("embedding unavailable — '{}' saved but not indexed for semantic search ({})", doc.id, e.message)
            return
        }
        lock.withLock {
            rows[doc.id] = Row(
                id = doc.id,
                text = doc.text,
                tag = doc.tag.name,
                vector = vector.values,
                attrs = doc.attrs,
            )
            dirty = true
        }
        flush()
    }

    override suspend fun delete(id: String) {
        lock.withLock {
            if (rows.remove(id) != null) dirty = true
        }
        flush()
    }

    override suspend fun query(text: String, topK: Int, tags: Set<DocTag>?): List<Hit> {
        val queryVec = try {
            embedder.embed(text)
        } catch (e: Exception) {
            // No embeddings → no semantic search. Eager memory (user/preferences
            // injected into the prompt) still covers identity recall.
            log.warn("embedding unavailable — semantic recall returned no results ({})", e.message)
            return emptyList()
        }
        val snapshot = lock.withLock { rows.values.toList() }

        val filtered = if (tags == null) {
            snapshot
        } else {
            val tagNames = tags.map { it.name }.toSet()
            snapshot.filter { it.tag in tagNames }
        }

        return filtered.asSequence()
            .map { row ->
                val rowVector = Vector(row.vector)
                val score = queryVec.cosineSimilarity(rowVector)
                Hit(
                    id = row.id,
                    text = row.text,
                    tag = DocTag.valueOf(row.tag),
                    score = score,
                    attrs = row.attrs,
                )
            }
            .sortedByDescending { it.score }
            .take(topK)
            .toList()
    }

    override suspend fun flush() {
        val snapshot = lock.withLock {
            if (!dirty) return
            dirty = false
            rows.values.toList()
        }
        val payload = Index(version = 1, rows = snapshot)
        indexFile.writeText(json.encodeToString(Index.serializer(), payload), Charsets.UTF_8)
        log.debug("vector index flushed: {} rows", snapshot.size)
    }

    private fun loadFromDisk() {
        if (!indexFile.exists()) return
        try {
            val payload = json.decodeFromString(Index.serializer(), indexFile.readText(Charsets.UTF_8))
            payload.rows.forEach { rows[it.id] = it }
            log.info("loaded {} vectors from {}", payload.rows.size, indexFile)
        } catch (e: Exception) {
            log.warn("could not load vector index from {} — starting fresh: {}", indexFile, e.message)
        }
    }

    @Serializable
    private data class Row(
        val id: String,
        val text: String,
        val tag: String,
        val vector: List<Double>,
        val attrs: Map<String, String>,
    )

    @Serializable
    private data class Index(val version: Int, val rows: List<Row>)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    }
}
