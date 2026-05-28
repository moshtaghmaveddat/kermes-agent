---
name: koog-memory
description: Build memory and history management in Koog — ChatMemory (per-session conversation buffer with ChatHistoryProvider backends, windowSize, filterMessages preprocessors), History Compression (WholeHistory, FromLastNMessages, Chunked, FactRetrievalHistoryCompressionStrategy with Concept-based fact extraction), and Persistence (checkpoint/rollback with InMemory or File providers). Use when conversations exceed context limits, when state must survive process restarts, when extracting structured facts from history, or when handling token-budget overflow. AgentMemory is deprecated — do not install it. For vector-store-based long-term semantic memory see koog-rag.
metadata:
  source: https://docs.koog.ai/features/chat-memory/
  koog_version: "1.0.0"
---

# Memory in Koog (modern pattern)

Three independent features cover the memory surface:

| Feature | Scope | What it does |
|---|---|---|
| **ChatMemory** | Per session | Auto-load + save conversation history across runs |
| **History Compression** | Per agent run | Reduce token usage by summarizing or fact-extracting history |
| **Persistence** | Per checkpoint | Save/restore mid-run state for crash recovery |

> **Don't install AgentMemory** — it's deprecated. The modern pattern is the three features above plus the vector store + embeddings APIs directly (see `koog-rag`).

## ChatMemory — conversation across runs

### Dependency

```kotlin
implementation("ai.koog:agents-features-memory:$koogVersion")
```

### Install

```kotlin
val agent = AIAgent(
    promptExecutor = executor,
    llmModel = OpenAIModels.Chat.GPT4oMini,
) {
    install(ChatMemory)
}
```

### Use — pass a session ID

```kotlin
agent.run("What is the capital of France?", "session-1")
agent.run("And what about Germany?", "session-1")   // remembers the previous turn
agent.run("Different conversation",      "session-2")   // independent history
```

### Pluggable backend

Default is in-memory. Implement `ChatHistoryProvider` for persistence (file, DB, Redis, etc.).

### Preprocessors

Run sequentially on the history before it's loaded into the prompt:

| Preprocessor | Effect |
|---|---|
| `windowSize(n)` | Keep only the last `n` messages |
| `filterMessages { msg -> ... }` | Keep messages matching the predicate |
| Custom `ChatMemoryPreProcessor` | Anything you want |

```kotlin
install(ChatMemory) {
    addPreprocessor(windowSize(20))
    addPreprocessor(filterMessages { it !is Message.Tool })
}
```

**Order matters** — `windowSize → filter` gives a different result than `filter → windowSize`. Filter first if you care about keeping N substantive messages.

## History Compression — token control

Independent from ChatMemory. ChatMemory handles cross-run *storage*; compression handles in-run *size*.

### When it triggers

- Between logical steps (between subgraphs)
- When context exceeds a threshold
- Manually inside a custom node

### Strategies

| Strategy | Behavior | Use when |
|---|---|---|
| **`WholeHistory`** (default) | Summarize entire conversation into one TLDR message | General-purpose, multi-turn chat |
| **`FromLastNMessages`** | Compress only the last N messages; discard older | Only recent context matters |
| **`Chunked`** | Split history into fixed-size chunks, compress each | Long conversation; want phase-by-phase summaries |
| **`FactRetrievalHistoryCompressionStrategy`** | LLM extracts specific `Concept`s from history | You know what to remember — names, decisions, action items |

### Using inside a graph

```kotlin
val compress by nodeLLMCompressHistory<String>(
    strategy = WholeHistory(),
    preserveMemory = true,    // keep memory-related messages intact
)
```

Java equivalent: `AIAgentNode.llmCompressHistory().preserveMemory()`.

### Using inside a custom node / write session

```kotlin
llm.writeSession {
    replaceHistoryWithTLDR()
}
```

### Fact retrieval — the powerful one

Use `Concept` objects to declare what to extract. Each concept:
- **keyword** — short identifier
- **description** — what to look for
- **type** — `SINGLE` (one value) or `MULTIPLE` (a list)

```kotlin
val userName     = Concept("user_name",      "the user's name",      ConceptType.SINGLE)
val userGoals    = Concept("user_goals",     "stated user goals",    ConceptType.MULTIPLE)
val decisions    = Concept("decisions_made", "decisions in this run", ConceptType.MULTIPLE)

val compress by nodeLLMCompressHistory<String>(
    strategy = FactRetrievalHistoryCompressionStrategy(
        concepts = listOf(userName, userGoals, decisions),
    ),
    preserveMemory = true,
)
```

This is the replacement for the deprecated AgentMemory. Use it for session-end summarization, periodic distillation, etc.

### Memory preservation

`preserveMemory = true` keeps memory-derived messages through compression. Important if you've injected facts from a previous session's recall — you don't want compression to erase them.

## Agent Persistence — checkpoint + resume

### What it persists

- Message history (system + user + assistant + tool messages)
- Last successfully executed node + its output data
- Selected LLM + parameters
- Selected tools
- `AIAgentStorage` contents
- Creation timestamp

**Only serializable values** — non-serializable contents are silently dropped. Configure your serializer in `AIAgentConfig`.

### Providers

| Provider | Persists across process restart? |
|---|---|
| `InMemoryPersistenceStorageProvider` | No |
| `FilePersistenceStorageProvider` | Yes (writes to disk) |
| `NoPersistenceStorageProvider` | No (default) |
| Custom (implement `PersistenceStorageProvider`) | Up to you |

### Install

```kotlin
val agent = AIAgent(...) {
    install(Persistence) {
        storage = FilePersistenceStorageProvider(Path("/var/data/checkpoints"))
    }
}
```

Continuous persistence is on by default — checkpoints created after each node execution.

### Checkpoint API

```kotlin
val checkpoint = context.persistence().createCheckpointAfterNode(
    agentContext = context,
    nodePath = context.executionInfo.path(),
    lastOutput = outputData,
    lastOutputType = outputType,
    checkpointId = context.runId,
    version = 0L,
)
```

Most code doesn't call this manually — it happens automatically per node.

### Rollback API

```kotlin
context.persistence().rollbackToCheckpoint(checkpointId, context)
context.persistence().rollbackToLatestCheckpoint(context)
```

### Rollback Tool Registry

If tools have side effects (DB writes, external API calls), register an undo:

```kotlin
install(Persistence) {
    storage = FilePersistenceStorageProvider(...)
    rollbackToolRegistry = RollbackToolRegistry {
        registerRollback(::createUser, ::removeUser)
    }
}
```

On rollback, the registered undo tools run in **reverse order** of their original execution.

### Works with all agent types

Basic, functional, graph, planner — Persistence operates at the execution context level.

## Comparison table — pick the right one

| Need | Feature |
|---|---|
| "Same user, multiple conversations over time" | ChatMemory |
| "Conversation got too long" | History Compression |
| "Process restart shouldn't lose work" | Persistence |
| "Resume an interrupted scheduled job" | Persistence |
| "Pull specific facts out of history" | History Compression with `FactRetrievalHistoryCompressionStrategy` |
| "Semantic search over past conversations" | Vector store + embeddings (see `koog-rag`) |
| "Undo a side-effect-bearing tool" | Persistence rollback tool registry |

You can — and usually should — install **multiple** of these together.

## The AgentMemory deprecation

If you find docs referring to `AgentMemory`, `MemorySubjects`, `MemoryScope`, `Concept`/`Subject`/`Fact` *in the memory-feature sense*: those are the deprecated API.

**Replacement pattern:**
- ChatMemory → conversation buffer
- History Compression (especially `FactRetrieval...`) → fact extraction with the *same* `Concept` mental model, but stored as compressed messages, not in a separate fact DB
- Vector store + embeddings (see `koog-rag`) → long-term semantic store

Same problems, modern primitives.

## Pitfalls

- **Reaching for AgentMemory** — deprecated; use the three features above instead
- **No `windowSize`** on long-running ChatMemory sessions — context grows without bound
- **Compression without `preserveMemory`** — wipes facts you injected from past sessions
- **Persistence without serializable values** — silently dropped, hard to debug
- **Side-effect tools with no rollback** — checkpoint/resume gets you back to a state that lies about reality

## Related skills

- `koog-features` — install mechanism
- `koog-rag` — vector store, embeddings, the semantic memory layer
- `koog-strategies-graphs` — `nodeLLMCompressHistory` and `AIAgentStorage`
