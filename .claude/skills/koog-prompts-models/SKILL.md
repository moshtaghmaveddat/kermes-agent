---
name: koog-prompts-models
description: Construct Koog prompts via the DSL (system/user/assistant/tool messages), set LLMParams (temperature, maxTokens, schema, toolChoice), attach multimodal content (image/audio/video/document), and handle rate-limit/transient failures (HTTP 429/500/502/503/504) via RetryingLLMClient and CachedPromptExecutor. Covers provider-side prompt caching (Anthropic, Bedrock) and content moderation. Use when authoring or tuning prompts, configuring retries/timeouts, or running moderation. Do not use for tool definitions (see koog-tools) or agent loop configuration (see koog-agents).
metadata:
  source: https://docs.koog.ai/prompts/
  koog_version: "1.0.0"
---

# Prompts, models, and LLM execution

A Koog **Prompt** is a `data class` with:
- `id` — unique identifier (used for caching)
- `messages` — conversation history (system, user, assistant, tool)
- `params` — `LLMParams` (temperature, maxTokens, schema, toolChoice)

You normally don't construct it directly — use the DSL.

## The prompt DSL (Kotlin)

```kotlin
import ai.koog.prompt.dsl.prompt

val myPrompt = prompt("hello-koog") {
    system("You are a helpful assistant.")
    user("What is 5 + 3?")
    assistant("The result is 8.")     // optional: few-shot priming
}
```

Java equivalent:

```java
Prompt p = Prompt.builder("hello-koog")
    .system("You are a helpful assistant.")
    .user("What is 5 + 3?")
    .assistant("The result is 8.")
    .build();
```

### Message types

| Type | Use for |
|---|---|
| `system(...)` | Persona, rules, role definition |
| `user(...)` | Input from the application/user |
| `assistant(...)` | Past LLM responses or few-shot examples |
| Tool-related | Pre-filled tool calls + results (rarely written manually) |

### Extending an existing prompt

Pass an existing `Prompt` as a base — useful when an agent wants to layer behavior on top of a template.

## LLM parameters (`LLMParams`)

```kotlin
val prompt = prompt(
    id = "dev-assistant",
    params = LLMParams(
        temperature = 0.7,
        maxTokens = 500,
        toolChoice = LLMParams.ToolChoice.Auto,
    )
) { ... }
```

| Param | Type | Notes |
|---|---|---|
| `temperature` | Double | 0.0 = deterministic, 1.0+ = creative |
| `maxTokens` | Int | Hard cap on generated tokens |
| `schema` | JsonSchema | For structured output |
| `toolChoice` | enum | `Auto`, `None`, `Required`, `All`, `Named(name)` |

### Where else to set params

- **Per subgraph**: `subgraphWithTask<I,O>(llmParams = LLMParams(...), ...)`
- **Mid-session**: `llm.writeSession { changeLLMParams(LLMParams(...)) }`

### Provider-specific params

Beyond `LLMParams`, providers have dedicated classes:
- `OpenAIChatParams`
- `AnthropicParams`
- `GoogleParams`
- etc.

These cover provider-specific knobs (e.g., Anthropic's `cacheControl`).

## Multimodal content

```kotlin
prompt("vision-task") {
    user {
        text("What's in this image?")
        attachment("path/to/image.jpg")           // auto-configured by extension
    }
}
```

Supported (per provider — check capability):
- **Images**: JPG, PNG, WebP, GIF
- **Audio**: MP3, WAV, FLAC
- **Video**: MP4, AVI, MOV
- **Documents**: PDF, TXT, MD, etc.

`AttachmentContent` accepts URL, byte array, Base64 string, or plain text. Mix multiple attachment types in one `user()` message.

## Model capabilities

Each `LLModel` declares what it supports — tool use, structured output, vision, audio, streaming. Check capability before assuming. (E.g., not every Ollama model supports tool use.) The `*Models` objects expose pre-configured models with the right capability flags.

## Provider-side prompt caching (NOT recommended for portability)

Only for Anthropic and Amazon Bedrock. Skip if you want provider diversity.

**Anthropic — automatic**:
```kotlin
val params = AnthropicParams(cacheControl = ...)
// Anthropic places the breakpoint at the last cacheable block
```

**Anthropic — manual**: attach `cacheControl` to specific messages/tools. TTL options: default 5 min (1.25× write cost) or `OneHour` (2× write cost). Reads are cheap.

**Bedrock**: similar, with `Default`, `FiveMinutes`, `OneHour` TTLs. **JVM-only.**

Monitor via `cacheReadInputTokens` and `cacheCreationInputTokens` in responses.

## Local response caching (CachedPromptExecutor)

This **does** work across providers — it's a local key-value cache keyed by prompt+model.

```kotlin
val cachedExecutor = CachedPromptExecutor(
    cache = FilePromptCache(Path("path/to/cache")),
    nested = promptExecutor,
)
val response = cachedExecutor.execute(prompt, OpenAIModels.Chat.GPT4o)
```

Limitations:
- Streaming responses come back as a single chunk
- Moderation requests bypass cache
- Multiple-choice responses unsupported

**Use case**: dev/test where you don't want to re-pay for identical prompts.

## Retries and failure handling

`RetryingLLMClient` decorator. Predefined configs:

| Config | Attempts | Initial delay | Max delay | Use for |
|---|---|---|---|---|
| `DISABLED` | 1 | — | — | dev/test |
| `CONSERVATIVE` | 3 | 2s | 30s | background tasks |
| `AGGRESSIVE` | 5 | 500ms | 20s | critical paths |
| `PRODUCTION` | 3 | 1s | 20s | default production |

Custom: `maxAttempts`, `initialDelay`, `maxDelay`, `backoffMultiplier`, `jitterFactor`.

**Retryable patterns** (any matching → retry):
- `Status` — HTTP codes 429, 500, 502, 503, 504, 529
- `Keyword` — "rate limit", "request timeout"
- `Regex` — custom
- `Custom` — lambda

**Timeouts** — `ConnectionTimeoutConfig`: connect 60s, request 15min, socket 15min (defaults).

Streaming retries are off by default. Enable explicitly.

## Content moderation

Pre-screen text/images before sending to the main LLM.

```kotlin
val result = openAIClient.moderate(prompt, OpenAIModels.Moderation.Omni)
if (result.isHarmful) { /* handle */ }
```

Available via `LLMClient` or `PromptExecutor`. Providers + models:
- **OpenAI**: `Moderation.Text` (text), `Moderation.Omni` (text + images)
- **Ollama**: `Meta.LLAMA_GUARD_3` (text, local)

`ModerationResult` exposes:
- `isHarmful: Boolean`
- `categories: Map<Category, Boolean>`
- `violatedCategories: List<Category>` (convenience)

18 standardized Koog categories include harassment, hate, violence, sexual, self-harm (variants), illicit, defamation, privacy, IP, elections misinformation, specialized advice.

## Decision quickrefs

**Which executor?**
- One provider → `simple<Provider>Executor`
- Multiple providers, want to switch mid-conversation → `MultiLLMPromptExecutor`
- Need retries → wrap the client in `RetryingLLMClient` before passing to executor
- Need local cache → wrap the executor in `CachedPromptExecutor`

**Which caching?**
- Provider portability matters → `CachedPromptExecutor` (local) only; avoid provider-side
- Anthropic-only deployment → provider-side prompt caching can dramatically cut cost
- Both → wrap local around an Anthropic executor that uses provider-side too

## Pitfalls

- **Hardcoding API keys** — always `System.getenv(...)`
- **No `maxTokens`** — long responses can blow your budget; cap explicitly for cost-sensitive paths
- **Provider-side caching as a default strategy** — locks you to one provider
- **Forgetting retries in production** — transient 429/503 will fail otherwise
- **Caching moderation requests** — silently ignored; don't rely on it

## Related skills

- `koog-overview` — provider list, executor functions
- `koog-tools` — toolChoice, tool definitions
- `koog-rag` — structured output and streaming details
