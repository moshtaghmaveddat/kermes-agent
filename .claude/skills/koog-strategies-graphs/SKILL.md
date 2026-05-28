---
name: koog-strategies-graphs
description: Author Koog graph-based strategies — define nodes (12+ predefined including nodeLLMRequest, nodeExecuteTools, nodeLLMSendToolResults, nodeLLMCompressHistory, nodeLLMRequestStructured), wire edges with conditions (onToolCalls, onTextMessage, onCondition, onToolNotCalled), compose subgraphs and subgraphWithTask, pass data via AIAgentStorage, parallelize nodes, and use LLM read/write sessions. Use when building branching workflows or decomposing complex agents into subsystems. Do not use for the basic agent loop (see koog-agents) or history-compression-as-a-feature (see koog-memory).
metadata:
  source: https://docs.koog.ai/custom-strategy-graphs/
  koog_version: "1.0.0"
---

# Strategies, graphs, and subgraphs

The graph DSL lets you model agent behavior as an explicit state machine. Pick this over a basic agent when you need conditional branching, multi-stage pipelines, or composability across subsystems.

## Architecture

```
Strategy (top-level, typed Input → Output)
  └── one or more Subgraphs (self-contained, own tools/context)
        └── Nodes (operations) connected by Edges (transitions)
```

## Minimal strategy

```kotlin
val strategy = strategy<String, String>("calculator") {
    val request by nodeLLMRequest()
    val execTools by nodeExecuteTools()
    val sendResults by nodeLLMSendToolResults()

    edge(nodeStart forwardTo request)
    edge(request forwardTo execTools onToolCalls { true })
    edge(request forwardTo nodeFinish onTextMessage { true })
    edge(execTools forwardTo sendResults)
    edge(sendResults forwardTo request)
}
```

This is exactly what a basic agent does implicitly.

## Predefined nodes — full catalog

### Core / boundary

| Node | Signature | Purpose |
|---|---|---|
| `nodeStart` | implicit | Entry. Receives initial input. |
| `nodeFinish` | implicit | Exit. Returns final output. |
| `nodeDoNothing<T>(name)` | `node<T, T>` | Pass-through. Useful as a placeholder/connector. |

### LLM interaction

| Node | Purpose |
|---|---|
| `nodeLLMRequest(name)` | Append user message, get LLM response. Tool calls allowed per config. |
| `nodeAppendPrompt(name)` | Add messages to the prompt without calling the LLM (build context). |
| `nodeLLMSendMessageOnlyCallingTools(name)` | Force LLM to respond *only* with tool calls. |
| `nodeLLMSendMessageForceOneTool(name)` | Force LLM to execute one specific tool. |
| `nodeLLMRequestStructured(name)` | Structured output with error correction. |
| `nodeLLMRequestStreaming(name)` | Streaming response with optional transformation. |
| `nodeLLMCompressHistory(name)` | Summarize conversation to reduce tokens. |

### Tool execution

| Node | Purpose |
|---|---|
| `nodeExecuteTools(name)` | Execute the tool calls from the previous LLM message. |
| `nodeLLMSendToolResults(name)` | Append tool results, request LLM response. |
| `nodeExecuteMultipleTools(name)` | Execute multiple tool calls (optionally parallel). |
| `nodeLLMSendMultipleToolResults(name)` | Append multiple results, get multiple LLM responses. |

## Edges and conditions

### Syntax

```kotlin
edge(from forwardTo to)                                    // unconditional
edge(from forwardTo to onCondition { input -> ... })       // predicate
edge(from forwardTo to onToolCalls { call -> ... })        // tool-call match
edge(from forwardTo to onTextMessage { msg -> ... })       // text-message match
edge(from forwardTo to onToolNotCalled)                    // negation helper
```

### Transformation

```kotlin
edge(from forwardTo to
    onCondition { input -> input.length > 10 }
    transformed { input -> input.uppercase() }
)
```

`onMessageParts(MessagePart.Text::class) transformed { ... }` lets you match specific message parts.

## Subgraphs

A **subgraph** is a self-contained processing unit with its own name, internal nodes, and (optionally) its own tool subset. They're the right shape for distinct subsystems within a larger agent.

### Basic subgraph

```kotlin
val firstSubgraph by subgraph<Input, Output>(
    name = "first",
    tools = listOf(someTool),    // optional tool restriction
) {
    val n1 by nodeLLMRequest()
    val n2 by nodeExecuteTools()
    edge(nodeStart forwardTo n1)
    edge(n1 forwardTo n2 onToolCalls { true })
    edge(n2 forwardTo nodeFinish)
}
```

### Tool selection strategies

| Approach | Code |
|---|---|
| All tools from parent registry | `ToolSelectionStrategy.ALL` |
| Specific list | `limitedTools(listOf(t1, t2))` |
| Dynamic filtering | via context methods at runtime |

### Higher-level subgraph patterns

| Pattern | What it does |
|---|---|
| `subgraphWithTask<I, O>(...)` | Multi-response LLM interactions inside a task envelope |
| `subgraphWithVerification(...)` | Validates task completion, returns success/failure + feedback |

### Composing subgraphs

```kotlin
val strategy = strategy<UserMsg, FinalAnswer>("research-agent") {
    val classify   by classifySubgraph()
    val research   by researchSubgraph()
    val synthesize by synthesisSubgraph()

    edge(nodeStart forwardTo classify)
    edge(classify forwardTo research onCondition { it.needsResearch })
    edge(classify forwardTo synthesize onCondition { !it.needsResearch })
    edge(research forwardTo synthesize)
    edge(synthesize forwardTo nodeFinish)
}
```

Each subgraph has clear responsibility, isolated tools, and is independently testable.

## Custom nodes

When predefined nodes don't fit, write one.

```kotlin
val myNode by node<Input, Output>("node_name") { input ->
    /* do work */
    returnValue
}
```

### Parameterized custom node

```kotlin
fun AIAgentSubgraphBuilderBase<*, *>.myFilterNode(
    name: String? = null,
    threshold: Int,
): AIAgentNodeDelegate<String, String> = node(name) { input ->
    if (input.length > threshold) input.uppercase() else input
}
```

### Generic node (reified)

```kotlin
inline fun <reified T> AIAgentSubgraphBuilderBase<*, *>.passthrough(
    name: String? = null
): AIAgentNodeDelegate<T, T> = node(name) { it }
```

### Java equivalent

```java
var myNode = AIAgentNode.builder("node_name")
    .withInput(Input.class)
    .withOutput(Output.class)
    .withAction((input, ctx) -> result)
    .build();
```

## Data transfer between nodes — `AIAgentStorage`

Type-safe key-value store for passing data across nodes/subgraphs.

```kotlin
data class UserData(val name: String, val age: Int)

val USER_KEY = createStorageKey<UserData>("user-data")

val capture by node<String, String>("capture") { input ->
    storage.set(USER_KEY, UserData("Mostafa", 30))
    input
}

val recall by node<String, String>("recall") { input ->
    val user = storage.get(USER_KEY)        // typed retrieval
    "Hello ${user?.name}"
}
```

Key properties:
- `createStorageKey<T>("identifier")` — type parameter ensures compile-time safety
- Each key instance is unique (the name is *not* the identity — two keys with the same name are distinct)
- Thread-safe via mutex
- `get`, `set`, `getValue` (non-nullable), `remove`, `clear`, `toMap`

## Parallel node execution

Run independent operations concurrently.

```kotlin
val results = parallel(
    node1 with input,
    node2 with input,
    node3 with input,
)

val best = results.selectByIndex { results -> /* pick winner */ }
```

### Merge strategies

| Strategy | Behavior |
|---|---|
| `selectBy { ... }` | Filter by predicate |
| `selectByMax { ... }` | Pick highest-scoring |
| `selectByIndex { ... }` | Custom selector lambda |
| `fold(init) { acc, r -> ... }` | Aggregate all results |

Canonical example: "best joke" agent — three LLMs draft jokes in parallel, then a fourth LLM picks the best.

**Caveats:** only parallelize independent operations; mind rate limits and total API spend.

## LLM sessions — manual history management

When you need direct LLM control inside a node (rare), use sessions.

| Session | Allows | Concurrency |
|---|---|---|
| `AIAgentLLMReadSession` | Inspect prompt + tools | Multiple readers OK |
| `AIAgentLLMWriteSession` | Modify prompt, change params, call LLM, run tools | Exclusive (blocks others) |

```kotlin
llm.writeSession {
    appendPrompt {
        user("Refine the previous answer")
    }
    val response = requestLLM()
    // history auto-appended
}
```

Common operations on write sessions:
- `requestLLM()` — standard call with tools enabled
- `requestLLMWithoutTools()` — force text-only response
- `requestLLMStructured()` — schema-constrained output
- `requestLLMStreaming()` — incremental
- `replaceHistoryWithTLDR()` — compress to summary
- `changeLLMParams(LLMParams(...))` — tune temperature etc. mid-run

**Prefer node-based APIs** (`nodeLLMRequest` etc.) when possible. Sessions are the escape hatch.

## Agent events

Every workflow emits events. Categories:

| Category | Events |
|---|---|
| Agent lifecycle | `AgentStartingEvent`, `AgentCompletedEvent`, `AgentExecutionFailedEvent`, `AgentClosingEvent` |
| Strategy | Strategy starting/completed (graph + functional variants) |
| Subgraph | Subgraph starting/completed/failed |
| Node | Node starting/completed/failed |
| LLM | Call starting/completed/failed; streaming variants |
| Tool | `ToolCallStartingEvent`, `ToolCallCompletedEvent`, `ToolValidationFailedEvent`, `ToolCallFailedEvent` |

All events carry an `AgentExecutionInfo` for tracing nested contexts (parent → child).

Subscribe via the EventHandler feature (see `koog-features` skill).

## Predefined strategies

Koog ships ready-to-use strategies. Use these as starting points or references for your own. (See the docs page on predefined strategies for the full list.)

## Best practices

- **One responsibility per subgraph.** "Classify input", "fetch context", "synthesize answer" — not "do everything".
- **Use AIAgentStorage** for cross-subgraph data, not closures or globals.
- **Name nodes meaningfully** — names show up in traces and tools like Langfuse.
- **Prefer predefined nodes** to custom ones when they fit — they're tested and observable.
- **Parallelize only what's actually independent** — rate limits and cost matter.
- **Test subgraphs in isolation** — Koog's Testing feature supports this.

## Pitfalls

- **Forgetting `nodeFinish`** — strategy with no exit hangs at runtime
- **Missing edge** — node has nowhere to go after completing
- **`onCondition` that never matches** — agent stalls; always have a default/fall-through edge
- **Storage keys with same name assumed equal** — they're not; instance identity rules
- **Custom node returning wrong type** — caught at edge wiring; pay attention to compiler

## Related skills

- `koog-agents` — when to choose graph-based over basic
- `koog-features` — EventHandler subscribes to the events listed above
- `koog-tools` — tool registry behavior inside subgraphs
- `koog-rag` — `nodeLLMRequestStructured` and structured output
