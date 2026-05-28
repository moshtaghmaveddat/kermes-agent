---
name: koog-rag
description: Build retrieval, structured output, and streaming in Koog — embed text via LLMEmbedder (OpenAI TextEmbedding3Small/Large/Ada002 or Ollama OllamaModels.Embeddings.*), compare with Vector.cosineSimilarity(), request typed responses with executeStructured / nodeLLMRequestStructured (kotlinx.serialization + StructureFixingParser auto-correction), and consume streaming responses frame-by-frame (StreamFrame.TextDelta, ToolCallComplete, End). Use when building knowledge bases or long-term semantic memory, parsing LLM outputs to data classes, or rendering progressive UIs. NOTE: Koog 1.0 ships NO concrete vector store — you implement storage yourself. For session buffer and history compression see koog-memory.
metadata:
  source: https://docs.koog.ai/retrieval-augmented-generation/
  koog_version: "1.0.0"
  verified: "against 1.0.0 JARs"
---

# Embeddings, RAG, structured output, streaming

Four related capabilities. **Everything below is verified against the actual
Koog 1.0.0 artifacts** — earlier doc-derived class names (`EmbeddingStorage`,
`JVMFileVectorStorageBackend`, `rag-vector`, etc.) do **not** exist in 1.0.

## Embeddings

Modules:
- `embeddings-base` — `Embedder` interface + `Vector` (with built-in similarity math)
- `embeddings-llm` — `LLMEmbedder`, which wraps **any** embedding-capable client
  (OpenAI, Ollama, Bedrock) — it is *not* Ollama-specific.

### `Vector` has the math built in

```kotlin
val v1: Vector = embedder.embed("the cat sat on the mat")
val v2: Vector = embedder.embed("a feline rested on the rug")

v1.cosineSimilarity(v2)   // higher = more similar
v1.euclideanDistance(v2)
v1.dotProduct(v2)
v1.magnitude()
embedder.diff(v1, v2)     // embedder-defined distance (lower = more similar)
```

`Vector.values` is a `List<Double>`; `Vector.dimension` gives the size.

### OpenAI embeddings

```kotlin
val client   = OpenAILLMClient(apiKey, OpenAIClientSettings(baseUrl = baseUrl))
val embedder = LLMEmbedder(client, OpenAIModels.Embeddings.TextEmbedding3Small)
// also: TextEmbedding3Large, TextEmbeddingAda002
```

### Local embeddings via Ollama

Access via `OllamaModels.Embeddings.*` (NOT `OllamaEmbeddingModels`):

| Constant | Notes |
|---|---|
| `OllamaModels.Embeddings.NOMIC_EMBED_TEXT` | general purpose, balanced |
| `OllamaModels.Embeddings.ALL_MINI_LM` | small, fast (note the spelling) |
| `OllamaModels.Embeddings.MULTILINGUAL_E5` | 100+ languages |
| `OllamaModels.Embeddings.BGE_LARGE` | high-quality English |
| `OllamaModels.Embeddings.MXBAI_EMBED_LARGE` | high-quality, larger |

```kotlin
val ollama   = OllamaClient(/* baseUrl defaults to http://localhost:11434 */)
val embedder = LLMEmbedder(ollama, OllamaModels.Embeddings.NOMIC_EMBED_TEXT)
```

### AWS Bedrock embeddings

`LLMEmbedder(BedrockLLMClient(...), <BedrockModels embedding model>)` — Titan / Cohere.

## RAG — the important correction

**Koog 1.0 ships only `rag-base`. There is no `rag-vector` module and no
concrete vector storage implementation.** What `rag-base` actually contains:

| Category | Types that exist |
|---|---|
| Storage **interfaces** | `SearchStorage`, `WriteStorage`, `LookupStorage`, `DeletionStorage` |
| Search requests | `SimilaritySearchRequest`, `KeywordSearchRequest`, `HybridSearchRequest` |
| Search results | `SearchResult`, `Score`, `ScoreMetric` |
| Documents | `TextDocument`, `DocumentWithPayload` |
| File access | `JVMFileSystemProvider`, `JVMDocumentProvider` |

What does **NOT** exist (despite older docs): `EmbeddingStorage`,
`InMemoryVectorStorageBackend`, `FileVectorStorageBackend`,
`JVMFileVectorStorageBackend`, `FileDocumentEmbeddingStorage`,
`TextDocumentEmbedder`, `JVMTextDocumentEmbedder`.

### What you actually do: bring your own store

Compute vectors with `LLMEmbedder`, store + search them yourself. Minimal
in-process pattern (this is what Kermes' `KoogVectorStore` does):

```kotlin
class SimpleVectorStore(private val embedder: Embedder) {
    private data class Row(val id: String, val text: String, val vec: Vector)
    private val rows = LinkedHashMap<String, Row>()

    suspend fun add(id: String, text: String) {
        rows[id] = Row(id, text, embedder.embed(text))
    }

    suspend fun query(text: String, topK: Int): List<Pair<String, Double>> {
        val q = embedder.embed(text)
        return rows.values
            .map { it.id to q.cosineSimilarity(it.vec) }
            .sortedByDescending { it.second }
            .take(topK)
    }
}
```

For persistence: serialize `Vector.values` (a `List<Double>`) to JSON/disk.
For scale: implement the same shape against Qdrant/pgvector. There is **no
built-in chunking pipeline** — chunk text before you embed it.

### Agentic RAG — expose search as a tool

Let the agent decide when to retrieve, rather than injecting everything upfront:

```kotlin
class KnowledgeBaseTool(private val store: SimpleVectorStore) : ToolSet {
    @Tool
    @LLMDescription("Search the knowledge base for relevant passages.")
    suspend fun searchKnowledge(
        @LLMDescription("Search query") query: String,
    ): String =
        store.query(query, topK = 5).joinToString("\n---\n") { it.first }
}
```

Register it like any other `ToolSet` (see `koog-tools`).

## Structured output

Get the LLM to return *typed data*. This is real and shipped.

### Define the structure

```kotlin
@Serializable
@LLMDescription("A parsed task with title, priority, and due date")
data class Task(
    @LLMDescription("Short task title") val title: String,
    @LLMDescription("Priority level") val priority: Priority,
    @LLMDescription("ISO 8601 date, e.g. 2026-06-15") val dueDate: String?,
)

@Serializable
enum class Priority { HIGH, MEDIUM, LOW }
```

### Request — via the executor

`executeStructured` returns a `Result<StructuredResponse<T>>`. An optional
`StructureFixingParser` re-asks an auxiliary LLM to repair malformed output.

```kotlin
val result: Result<StructuredResponse<Task>> = executor.executeStructured(
    prompt = prompt,
    model  = OpenAIModels.Chat.GPT4o,
    serializer = Task.serializer(),
    examples = listOf(Task("Review PR #42", Priority.HIGH, "2026-06-10")),
    fixingParser = StructureFixingParser(/* retries, fixing model */),
)
val task = result.getOrThrow().structure
```

### Request — via a graph node

```kotlin
val parseTask by nodeLLMRequestStructured<Task>(/* serializer, examples, fixing parser */)
// also available: nodeLLMSendMessageStructured
```

### Supported shapes

Nested data classes, `List<T>` / `Map<String, T>`, enums, and polymorphic
sealed classes (with type discrimination). JSON-schema generation is handled
per-provider (`OpenAIStandardJsonSchemaGenerator`,
`AnthropicStandardJsonSchemaGenerator`).

### Best practices

- Concrete `@LLMDescription` on every field.
- Provide 1-3 `examples` — large reliability gain.
- Start flat; add nesting once the model gets it right.
- A `StructureFixingParser` costs extra LLM calls — use deliberately.

## Streaming API

Consume `StreamFrame`s as the model generates. Frame types are **nested under
`StreamFrame`** (`StreamFrame.TextDelta`, etc.).

| Frame | Meaning |
|---|---|
| `StreamFrame.TextDelta` | incremental assistant text chunk |
| `StreamFrame.TextComplete` | full text once deltas done |
| `StreamFrame.ReasoningDelta` / `ReasoningComplete` | reasoning stream |
| `StreamFrame.ToolCallDelta` / `ToolCallComplete` | partial / finalized tool call |
| `StreamFrame.End` | stream finished (finish reason + metadata) |

### Consume

```kotlin
client.executeStreaming(prompt, model).collect { frame ->
    when (frame) {
        is StreamFrame.TextDelta        -> print(frame.text)
        is StreamFrame.ToolCallComplete -> handleTool(frame)
        is StreamFrame.End              -> println("\n[done]")
        else -> Unit
    }
}
```

> There are **no** `filterTextOnly()` / `collectText()` helpers in 1.0 — filter
> frames yourself with `when`/`filterIsInstance<StreamFrame.TextDelta>()`.

### Hook into streaming via events

`EventHandler` exposes `onLLMStreamingFrameReceived` (plus
`onLLMStreamingStarting/Completed/Failed`) — see `koog-features`.

## Testing

A Koog testing module exists but is **not** on the default `koog-agents`
classpath — add the test artifact explicitly and verify its API against your
version before relying on `install(Testing)`. Typical use: mock LLM responses
for deterministic CI, assert tool calls without real API spend.

## Decision matrix

| Goal | Use |
|---|---|
| Knowledge base / semantic memory | `LLMEmbedder` + your own store (no built-in) |
| Let the agent decide when to retrieve | expose search as a `@Tool` (agentic RAG) |
| LLM returns JSON-shaped data | `executeStructured` / `nodeLLMRequestStructured` |
| Progressive UI updates | streaming `StreamFrame`s |
| Cheap local embeddings | `OllamaModels.Embeddings.NOMIC_EMBED_TEXT` |
| Highest-quality embeddings | `OpenAIModels.Embeddings.TextEmbedding3Large` |

## Pitfalls

- **Expecting a built-in vector store** — there is none in 1.0; you write it.
- **Looking for `rag-vector`** — module doesn't exist; only `rag-base`.
- **`OllamaEmbeddingModels`** — wrong; it's `OllamaModels.Embeddings.*`.
- **`filterTextOnly()` / `collectText()`** — don't exist; filter frames manually.
- **Forgetting `StreamFrame.End`** — you miss finish reason / token usage.
- **Mixing embedding models in one store** — breaks similarity; pick one.
- **No chunking** — Koog ships none; chunk before `embed()`.

## Related skills

- `koog-memory` — ChatMemory / HistoryCompression / Persistence (session + history)
- `koog-tools` — registering the agentic-RAG search tool
- `koog-strategies-graphs` — `nodeLLMRequestStructured`, streaming nodes
- `koog-overview` — embedding model constants live on the provider `*Models` objects
