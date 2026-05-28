package ai.kermes.core.vector

/**
 * Kermes-facing vector store. Wraps Koog's `EmbeddingStorage` so we can:
 *   - tag entries by perspective (skill / context / episode / feedback)
 *   - filter at query time
 *   - swap backend (file → Qdrant) without touching call sites
 */
interface VectorStore {

    /** Insert or update an entry. Embedding is computed by the implementation. */
    suspend fun upsert(doc: KermesDoc)

    /** Delete by id. */
    suspend fun delete(id: String)

    /**
     * Top-K similarity search. Optionally filter by tag set.
     * Time-decay (if applied) is layered on by the caller, not by this interface.
     */
    suspend fun query(text: String, topK: Int = 8, tags: Set<DocTag>? = null): List<Hit>

    /** Force any pending writes to durable storage. */
    suspend fun flush()
}

/** One stored document. `id` is application-chosen and stable. */
data class KermesDoc(
    val id: String,
    val text: String,
    val tag: DocTag,
    /** Free-form metadata for re-ranking, time-decay, audit. */
    val attrs: Map<String, String> = emptyMap(),
)

/**
 * One perspective per tag. Skills and the four memory perspectives all share
 * the underlying `EmbeddingStorage`; filtering happens at query time.
 */
enum class DocTag {
    Skill,
    UserFact,
    Preference,
    Context,
    Episode,
    Feedback,
}

data class Hit(
    val id: String,
    val text: String,
    val tag: DocTag,
    val score: Double,
    val attrs: Map<String, String> = emptyMap(),
)
