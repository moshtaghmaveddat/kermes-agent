---
name: koog-observability
description: Add span-based tracing to Koog agents — install the OpenTelemetry feature, emit hierarchical spans (CreateAgentSpan, InvokeAgentSpan, InferenceSpan, ExecuteToolSpan, NodeExecuteSpan), and export to Langfuse, W&B Weave, Datadog, or any OTLP backend. Use when adding production observability, debugging multi-step runs, tracking cost/latency per LLM call, or meeting audit/compliance requirements. For lightweight in-process callbacks (no exporter setup) use EventHandler — see koog-features instead.
metadata:
  source: https://docs.koog.ai/features/open-telemetry/
  koog_version: "1.0.0"
---

# Observability

Koog's primary observability is **OpenTelemetry-based** — install one feature, pick exporter(s), get traces in the backend of your choice. There's also a separate `Tracing` feature for in-process trace gathering.

## The OpenTelemetry feature

### Install

```kotlin
val agent = AIAgent(
    promptExecutor = executor,
    llmModel = OpenAIModels.Chat.GPT4o,
    systemPrompt = "...",
    installFeatures = {
        install(OpenTelemetry) {
            setServiceInfo("my-agent", "1.0.0")
            addSpanExporter(LoggingSpanExporter.create())   // dev
            // or production OTLP:
            addSpanExporter(
                OtlpGrpcSpanExporter.builder()
                    .setEndpoint("http://localhost:4317")
                    .build()
            )
            addResourceAttributes(mapOf("env" to "prod"))
            setVerbose(false)   // true = unmask message content for debugging
        }
    }
)
```

Java equivalent:

```java
var agent = AIAgent.builder()
    .promptExecutor(promptExecutor)
    .llmModel(OpenAIModels.Chat.GPT4o)
    .systemPrompt("...")
    .install(OpenTelemetry.Feature, config -> {
        config.setServiceInfo("my-agent", "1.0.0");
        config.addSpanExporter(LoggingSpanExporter.create());
    })
    .build();
```

### Configuration knobs

| Method | Purpose |
|---|---|
| `setServiceInfo(name, version)` | Identifies your service in traces |
| `addSpanExporter(exporter)` | Where spans go (can call multiple times) |
| `addResourceAttributes(map)` | Static metadata on every span |
| `setVerbose(true)` | Include full message content (dev only — leaks PII) |

### Automatic spans

Koog emits these span types out of the box — no manual instrumentation:

| Span | Captures |
|---|---|
| `CreateAgentSpan` | Agent lifecycle (construction/init) |
| `InvokeAgentSpan` | One full agent invocation |
| `InferenceSpan` | A single LLM call (latency, model, tokens) |
| `ExecuteToolSpan` | A single tool invocation (name, args, result, latency) |
| `NodeExecuteSpan` | One node's execution within a strategy |

Spans nest by execution context — parent/child relationships make complex agent runs traceable.

## Exporters

### Langfuse

For LLM-app observability (specialized for trace visualization + evals).

```kotlin
// env: LANGFUSE_HOST, LANGFUSE_PUBLIC_KEY, LANGFUSE_SECRET_KEY
install(OpenTelemetry) {
    addLangfuseExporter()
}
```

Trace attributes worth setting:
- `langfuse.session.id` — group related traces (same conversation, same user, etc.)
- `langfuse.environment` — `prod` vs `dev`
- `langfuse.trace.tags` — array of labels for filtering

### W&B Weave

Good if you already use W&B for ML experiments.

```kotlin
// env: WANDB_API_KEY (from https://wandb.ai/authorize)
install(OpenTelemetry) {
    addWeaveExporter()   // entity + project picked up from env or passed as args
}
```

Captures spans the same way; visualizes in W&B's workspace. Optionally include LLM prompt/response content.

### Datadog

For unified APM with non-LLM services.

```kotlin
install(OpenTelemetry) {
    addDatadogExporter()
}
```

Uses Datadog's LLM Observability product. Standard Datadog env vars (`DD_API_KEY`, `DD_SITE`, etc.) apply.

### Generic OTLP (any backend)

Tempo, Jaeger, Honeycomb, self-hosted OpenTelemetry collector — all reachable via OTLP:

```kotlin
addSpanExporter(
    OtlpGrpcSpanExporter.builder()
        .setEndpoint("http://otel-collector:4317")
        .build()
)
```

You can register **multiple exporters** simultaneously — e.g., Datadog for ops + Langfuse for prompt-eng debugging.

## The Tracing feature (separate from OpenTelemetry)

`Tracing` is Koog's built-in trace gathering — useful when you want comprehensive run information without exporting to a backend. Install it independently:

```kotlin
install(Tracing) {
    // configuration
}
```

Use when:
- You want logs without standing up an OTel pipeline
- You need application-side trace access (e.g., to dump on error)

OpenTelemetry's filter override applies here too — it forces full event streams; can't be filtered without breaking traces.

## EventHandler vs OpenTelemetry vs Tracing

| Goal | Best tool |
|---|---|
| Print "tool X called with Y" to stdout | EventHandler |
| Ship traces to a SaaS for visualization | OpenTelemetry + exporter |
| Write structured logs to disk | EventHandler or Tracing |
| Production APM | OpenTelemetry + Datadog/OTLP |
| LLM-specific eval + debugging UI | OpenTelemetry + Langfuse |
| Audit log for compliance | EventHandler (capture all hooks) + persist yourself |

Stack them — Tracing + OpenTelemetry + EventHandler all coexist.

## Verbose mode and PII

`setVerbose(true)` unmasks message content (user inputs, LLM outputs, tool args/results) into spans.

- **Dev:** essential for understanding what the agent actually said
- **Prod:** never enable without explicit consent + retention policy — these spans contain user PII, secrets in tool args, etc.

Default is `false`. Treat `true` like a debug flag, not a default.

## Best practices

- **Always set `setServiceInfo`** — distinguishes your agent from others on the same backend
- **Tag with environment** — `addResourceAttributes(mapOf("env" to "prod"))` so dashboards filter cleanly
- **Use Langfuse session IDs** to group traces per user conversation
- **Multiple exporters are fine** — pick the right tool for each consumer
- **Don't compress events before OTel** — `setEventFilter()` is overridden for a reason

## Pitfalls

- **Verbose in production** — leaks PII into traces
- **Forgetting env vars for exporters** — silently fails or auth-errors at first span flush
- **Sampling at exporter level for cost** — fine, but lose the ability to debug rare incidents
- **Treating Tracing and OpenTelemetry as alternatives** — they're complementary

## Related skills

- `koog-features` — EventHandler for cheap observation
- `koog-memory` — Persistence captures *state*, OTel captures *behavior*; both have a place
- `koog-strategies-graphs` — node/subgraph names show up as span names
