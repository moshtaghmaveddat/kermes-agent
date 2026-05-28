---
name: koog-features
description: Install Koog Features (the cross-cutting plugin mechanism) and write custom ones — covers the install() pattern, the EventHandler callback catalog (onToolCallStarting, onToolCallCompleted, onAgentCompleted, onLLMCallStarting, plus 8 more across agent/strategy/node/LLM/tool lifecycles), and how custom features hook the pipeline to enforce policy. Use when wiring lifecycle hooks, audit logging, or permission gates that need to block tool calls. For ChatMemory/HistoryCompression/Persistence specifics see koog-memory, for OpenTelemetry tracing see koog-observability.
metadata:
  source: https://docs.koog.ai/features/
  koog_version: "1.0.0"
---

# Features

A **Feature** is Koog's plugin mechanism — composable extensions that hook into the agent lifecycle. Multiple handlers can subscribe to the same event.

## Built-in features

| Feature | Purpose |
|---|---|
| **EventHandler** | Subscribe to agent lifecycle, LLM, tool, node, strategy events |
| **Tracing** | Built-in comprehensive run tracing |
| **ChatMemory** | Per-session conversation history (see `koog-memory`) |
| **Persistence** | Checkpoint + resume agent state (see `koog-memory`) |
| **OpenTelemetry** | Export traces to Datadog, Langfuse, Weave (see `koog-observability`) |
| ~~AgentMemory (long-term)~~ | **Deprecated** — use vector store + HistoryCompression directly |

## Installing a feature

Inside the `AIAgent { ... }` builder, call `install(Feature) { config }`:

```kotlin
val agent = AIAgent(
    promptExecutor = executor,
    llmModel = OpenAIModels.Chat.GPT4o,
) {
    install(EventHandler) {
        onToolCallStarting { ctx ->
            println("tool=${ctx.toolName} args=${ctx.toolArgs}")
        }
        onAgentCompleted { ctx ->
            println("done: ${ctx.result}")
        }
    }

    install(ChatMemory)
    install(Persistence) {
        storage = FilePersistenceStorageProvider(...)
    }
}
```

Or use the equivalent extension form for EventHandler:

```kotlin
val agent = AIAgent(...) {
    handleEvents {
        onToolCallStarting { ctx -> ... }
        onAgentCompleted { ctx -> ... }
    }
}
```

## EventHandler — the catalog

12 callbacks across 5 categories. All callbacks receive an `eventContext` with the relevant data.

### Agent lifecycle

| Callback | Fires when |
|---|---|
| `onAgentStarting` | Agent run begins |
| `onAgentCompleted` | Successful completion (`ctx.result`) |
| `onAgentExecutionFailed` | Run threw |

### Strategy

| Callback | Fires when |
|---|---|
| `onStrategyStarting` | Strategy begins |
| `onStrategyCompleted` | Strategy returns |

### Node

| Callback | Fires when |
|---|---|
| `onNodeExecutionStarting` | A node begins |
| `onNodeExecutionCompleted` | A node returns |

### LLM

| Callback | Fires when |
|---|---|
| `onLLMCallStarting` | About to call the LLM |
| `onLLMCallCompleted` | LLM call returned |

### Tool

| Callback | Fires when |
|---|---|
| `onToolCallStarting` | About to invoke a tool (`ctx.toolName`, `ctx.toolArgs`) |
| `onToolCallCompleted` | Tool returned |
| `onToolCallFailed` | Tool threw |

### Important: EventHandler is observation-only

EventHandler callbacks **monitor** events — they don't block or modify execution. To enforce something (permissions, content filtering, rate limiting), write a **custom Feature** with pipeline interceptors instead.

## Writing a custom Feature

For *enforcement*, *interception*, or *state injection* beyond what EventHandler offers.

### Three components

1. **Feature class** — your logic
2. **Config class** — extends `FeatureConfig`, holds user-supplied settings
3. **Companion object** — implements the feature interface(s)

### Choose interface(s)

| Interface | For |
|---|---|
| `AIAgentGraphFeature` | Graph-based agents |
| `AIAgentFunctionalFeature` | Functional agents |
| `AIAgentPlannerFeature` | Planner agents |

Implement **all three** if your feature should work everywhere. Most production features do.

### Skeleton

```kotlin
class PermissionGuard(val config: Config) {
    class Config : FeatureConfig() {
        var allowList: List<String> = emptyList()
        var denyList: List<String> = emptyList()
        var onDecision: (toolName: String, allowed: Boolean) -> Unit = { _, _ -> }
    }

    companion object Feature : AIAgentGraphFeature<Config, PermissionGuard> {
        private val KEY = createStorageKey<PermissionGuard>("permission-guard")

        override fun createInitialConfig() = Config()

        override fun install(config: Config, pipeline: AIAgentPipeline) {
            val instance = PermissionGuard(config)
            // Register pipeline interceptors here:
            //   - before tool call → check + block if denied
            //   - on agent start → load context
            //   - etc.
        }
    }
}
```

Pipeline interceptors give you the **pre-execution** hook needed to block — unlike EventHandler.

### Filtering events

Custom features can filter events with `setEventFilter()` to reduce overhead. Exception: OpenTelemetry forces the full stream (filtering would break trace integrity).

### Installation works the same as built-ins

```kotlin
val agent = AIAgent(...) {
    install(PermissionGuard) {
        denyList = listOf("delete_file", "kill_process")
        onDecision = { name, allowed -> auditLog.log(name, allowed) }
    }
}
```

## Decision matrix

| Goal | Use |
|---|---|
| Log/trace what the agent does | EventHandler |
| Send metrics to a monitoring backend | OpenTelemetry feature |
| Persist conversation across runs | ChatMemory |
| Checkpoint/resume a run | Persistence |
| Compress history to save tokens | HistoryCompression |
| Block a tool call before it runs | Custom Feature (pipeline interceptor) |
| Filter LLM outputs / inject context | Custom Feature |
| Long-term semantic memory | Vector store + embeddings directly (NOT AgentMemory) |

## Best practices

- **One feature, one responsibility.** Don't bundle auth + caching + observability into one mega-feature.
- **Idempotent install.** Re-installing a feature shouldn't double-register listeners.
- **Use storage keys** (`createStorageKey<MyFeature>()`) to access per-run feature state safely.
- **Don't reach for custom features when EventHandler suffices.** Observability is the common case; build only when you need to *change* behavior.

## Pitfalls

- **Using EventHandler to enforce policy** — it can't block. You'll see the call go through. Use a custom Feature with a pipeline interceptor.
- **Installing AgentMemory** — deprecated. Build memory on the vector store + History Compression directly.
- **Forgetting `setEventFilter()`** in high-traffic features — emits the full stream and adds latency.
- **Implementing only one interface** — your feature won't load if the user picks a different agent type.

## Related skills

- `koog-memory` — ChatMemory, History Compression, Persistence (all are Features)
- `koog-observability` — OpenTelemetry feature + exporters
- `koog-strategies-graphs` — the events that EventHandler hooks fire on
