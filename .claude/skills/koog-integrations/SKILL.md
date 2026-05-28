---
name: koog-integrations
description: Embed Koog into existing JVM web frameworks — the Ktor plugin (install(Koog), aiAgent() in route handlers, llm().execute/.executeStreaming/.moderate) and the Spring Boot starter (auto-configured executor beans like openAIExecutor, anthropicExecutor, injected via @Qualifier). Use when exposing agents as HTTP endpoints or embedding into existing Spring/Ktor services. For protocol-based exposure (A2A, ACP) see koog-interop.
metadata:
  source: https://docs.koog.ai/ktor-plugin/
  koog_version: "1.0.0"
---

# Framework integrations

## Ktor plugin

A drop-in `install(Koog)` for any Ktor `Application`.

### Install

```kotlin
fun Application.module() {
    install(Koog) {
        // optional: programmatic config (otherwise reads from application.yaml)
    }

    routing {
        post("/chat") {
            val input = call.receiveText()
            val result = aiAgent(
                model = OpenAIModels.Chat.GPT4o,
                systemPrompt = "You are a helpful assistant.",
            ) {
                run(input)
            }
            call.respondText(result)
        }
    }
}
```

### Provider config via `application.yaml`

```yaml
koog:
  openai:
    api-key: ${OPENAI_API_KEY}
  anthropic:
    api-key: ${ANTHROPIC_API_KEY}
  ollama:
    base-url: http://localhost:11434
```

Supports OpenAI, Anthropic, Google, OpenRouter, DeepSeek, Ollama.

### Route-level APIs

| Function | Purpose |
|---|---|
| `aiAgent(...) { run(input) }` | Create + run an agent inside a route handler |
| `llm().execute(prompt, model)` | Lower-level: direct LLM call without an agent |
| `llm().executeStreaming(prompt, model)` | Streaming responses |
| `llm().moderate(prompt, model)` | Content moderation |

### MCP from Ktor

MCP tool integration is JVM-only and works exactly like in non-Ktor agents — build an MCP `ToolRegistry` and pass it to `aiAgent`.

### When to use

- Web-facing agent endpoint (chat API, embed in existing Ktor service)
- Streaming responses to a frontend (SSE/WebSocket via Ktor + Koog's streaming LLM call)
- Phase 2+ of Kermes when adding HTTP gateways

## Spring Boot starter

Auto-configures `PromptExecutor` beans per configured provider. Inject and use.

### Install

```kotlin
implementation("ai.koog:koog-spring-boot-starter:$koogVersion")
```

### Configure providers

```properties
# application.properties
koog.openai.api-key=${OPENAI_API_KEY}
koog.anthropic.api-key=${ANTHROPIC_API_KEY}
koog.google.api-key=${GOOGLE_API_KEY}
```

Or YAML:

```yaml
koog:
  openai:
    api-key: ${OPENAI_API_KEY}
  anthropic:
    api-key: ${ANTHROPIC_API_KEY}
```

Supported: OpenAI, Anthropic, Google, OpenRouter, DeepSeek, Mistral, Ollama.

### Auto-generated beans

| Provider configured | Bean name |
|---|---|
| OpenAI | `openAIExecutor` |
| Anthropic | `anthropicExecutor` |
| Google | `googleExecutor` |
| OpenRouter | `openRouterExecutor` |
| DeepSeek | `deepseekExecutor` |
| Mistral | `mistralExecutor` |
| Ollama | `ollamaExecutor` |

### Inject and use

```kotlin
@RestController
class ChatController(
    @Qualifier("openAIExecutor") private val executor: PromptExecutor,
) {
    @PostMapping("/chat")
    suspend fun chat(@RequestBody input: String): String {
        val prompt = prompt("chat-${UUID.randomUUID()}") {
            system("You are a helpful assistant.")
            user(input)
        }
        return executor.execute(prompt, OpenAIModels.Chat.GPT4o)
            .first().content
    }
}
```

### Multi-provider fallback

Wrap multiple executor beans in `MultiLLMPromptExecutor` for cross-provider routing:

```kotlin
@Bean
fun multiExecutor(
    openAi: PromptExecutor,
    anthropic: PromptExecutor,
) = MultiLLMPromptExecutor(
    LLMProvider.OpenAI to /* underlying client */,
    LLMProvider.Anthropic to /* underlying client */,
)
```

### Bean qualifier gotcha

If multiple providers are configured and you don't qualify, Spring picks ambiguously. Always `@Qualifier("openAIExecutor")` (or similar) when injecting `PromptExecutor`.

## Choose your framework

| You're building | Pick |
|---|---|
| Existing Spring Boot service | Spring Boot starter |
| New Kotlin-first web service | Ktor plugin |
| Embedded into a non-web JVM app | Plain `AIAgent`, no framework |
| Multiplatform (JS, Android, iOS) | Plain `AIAgent`, framework not applicable |

## Pitfalls

- **Hardcoding keys in `application.yaml`** — use `${OPENAI_API_KEY}` env-var substitution
- **No `@Qualifier`** in Spring when multiple providers — ambiguous bean injection
- **Trying to use the Spring starter in non-Spring code** — it requires the Spring context; pull in `agents-core` directly instead
- **MCP from non-JVM target** — JVM-only

## Related skills

- `koog-overview` — provider list and model classes
- `koog-prompts-models` — prompt DSL used in route/controller code
- `koog-interop` — exposing your agent over A2A or ACP (alternatives to plain HTTP)
