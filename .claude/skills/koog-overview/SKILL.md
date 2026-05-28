---
name: koog-overview
description: Install Koog, pick an LLM provider, and write the minimal AIAgent. Covers the OpenAI/Anthropic/OpenRouter/Ollama/Gemini/Bedrock/Mistral/DeepSeek/Azure/DashScope client and executor classes. Use when scaffolding a new Koog project, choosing or switching providers, or answering "what does Koog support". Do not use for tool definitions (see koog-tools), graph workflows (see koog-strategies-graphs), or memory/features (see koog-memory, koog-features).
metadata:
  source: https://docs.koog.ai/
  koog_version: "1.0.0"
---

# Koog overview

Koog is JetBrains' open-source JVM framework for building AI agents in Kotlin and Java. Production-ready as of Koog 1.0 (May 2026), with a stability guarantee on stable modules for at least one year.

## When to use Koog

- Building a Kotlin/Java agent that needs tools, memory, retrieval, or multi-step workflows
- Targeting JVM (server, Android) or Kotlin Multiplatform (JS, WasmJS, iOS)
- Want LLM-provider portability without rewriting (it ships clients for 9 providers)
- Want production primitives (retries, history compression, persistence, observability) out of the box

## When NOT to use Koog

- Pure Python project — use LangChain/LangGraph
- One-off LLM call with no tools/state — just use the provider's SDK directly
- Need DSPy-style program optimization — Koog doesn't have it

## Install (Gradle Kotlin DSL)

```kotlin
dependencies {
    implementation("ai.koog:koog-agents:1.0.0")
}
```

For Maven, the artifactId is `koog-agents-jvm`.

## Minimal agent

```kotlin
// Verified against Koog 1.0.0. NOTE: convenience `simple*Executor` helper
// functions do NOT exist in 1.0 — build a client and wrap it in an executor.
import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val apiKey = System.getenv("OPENAI_API_KEY") ?: error("OPENAI_API_KEY not set")

    val client   = OpenAILLMClient(apiKey)
    val executor = MultiLLMPromptExecutor(client)   // single-provider path in 1.0

    val agent = AIAgent(
        promptExecutor = executor,
        llmModel = OpenAIModels.Chat.GPT4o,
    )

    println(agent.run("Hello! How can you help me?"))
}
```

The minimum: build a provider client, wrap it in a `MultiLLMPromptExecutor`, pick a model from the provider's `*Models` object, call `agent.run(input)`.

## Supported LLM providers

You construct each provider's **client**, then wrap one or more clients in a
`MultiLLMPromptExecutor`. (There are no `simple*Executor` convenience functions
in 1.0.)

| Provider | Client class | Models object |
|---|---|---|
| OpenAI | `OpenAILLMClient` | `OpenAIModels.Chat.GPT4o`, etc. |
| Azure OpenAI | `OpenAILLMClient` (custom `baseUrl`) | same as OpenAI |
| Anthropic | `AnthropicLLMClient` | `AnthropicModels.*` |
| Google (Gemini) | `GoogleLLMClient` | `GoogleModels.*` |
| DeepSeek | `DeepSeekLLMClient` | `DeepSeekModels.*` |
| OpenRouter | `OpenRouterLLMClient` (or `OpenAILLMClient` + baseUrl) | upstream model IDs |
| Amazon Bedrock | `BedrockLLMClient` | `BedrockModels.*` |
| Mistral | `MistralAILLMClient` | `MistralAIModels.*` |
| Alibaba DashScope | `DashScopeLLMClient` | DashScope model IDs |
| Ollama (local) | `OllamaClient` | `OllamaModels.*` |

Client packages live under `ai.koog.prompt.executor.clients.<provider>`.

## Single vs multi-provider executors

**Single provider** (most common) — wrap one client:

```kotlin
val executor = MultiLLMPromptExecutor(OpenAILLMClient(System.getenv("OPENAI_API_KEY")))
```

**Multiple providers** — pass several clients (keyed by provider); the executor
routes by the model you pass to `execute`:

```kotlin
val executor = MultiLLMPromptExecutor(
    LLMProvider.OpenAI to OpenAILLMClient(openAiKey),
    LLMProvider.Anthropic to AnthropicLLMClient(anthropicKey),
)

executor.execute(prompt, OpenAIModels.Chat.GPT4o)       // routes to OpenAI
executor.execute(prompt, AnthropicModels.Sonnet_4_5)    // routes to Anthropic
```

`MultiLLMPromptExecutor` is what gives Koog its "switch models mid-conversation"
feature. For production reliability, wrap each client in `RetryingLLMClient`
(see `koog-prompts-models`).

## Key features at a glance

| Feature | Use it via |
|---|---|
| Tools (callable functions) | `@Tool` annotations + `ToolRegistry` |
| Graph-based workflows | `strategy<I, O>("name") { ... }` DSL |
| Functional agents (custom loop) | `AIAgent { ... }` with explicit loop |
| Memory across runs | `install(ChatMemory)` feature |
| History compression | `install(HistoryCompression)` feature |
| State persistence + resume | `install(Persistence)` feature |
| Event hooks | `install(EventHandler)` feature |
| OpenTelemetry tracing | `install(OpenTelemetry)` feature |
| MCP tool servers | `mcpClient(...)` configuration |
| Embeddings + RAG | `embeddings/` and RAG APIs |
| Spring Boot integration | `koog-spring-boot-starter` dep |
| Ktor plugin | `Koog` plugin for Ktor servers |

## Supported platforms

- **JVM** (Kotlin, Java) — primary
- **Kotlin Multiplatform** — JS, WasmJS, Android, iOS for compatible modules

## Provider key handling

All examples read keys from env vars. Never hardcode. Recommended env-var names:

```
OPENAI_API_KEY
ANTHROPIC_API_KEY
GOOGLE_API_KEY
OPENROUTER_API_KEY
MISTRAL_API_KEY
DEEPSEEK_API_KEY
AWS_BEARER_TOKEN_BEDROCK
```

For Ollama, no key — just point the client at the local endpoint (default `http://localhost:11434`).

## Pitfalls to avoid

- **Don't pick AgentMemory** — it's deprecated. Use ChatMemory + HistoryCompression + the vector store APIs instead.
- **Don't hand-roll a tool-use loop** if the basic agent does what you need. The graph DSL is for *complex* workflows, not every agent.
- **Don't reach for prompt caching** unless you've committed to Anthropic. It's provider-specific.
- **Don't write file/list/edit tools** — they ship as built-ins (`ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ListDirectoryTool`).

## Where to look next

- For tool definitions → see the `koog-tools` skill
- For graph/strategy workflows → see the `koog-strategies-graphs` skill
- For memory + history compression → see the `koog-memory` skill
- For event hooks → see the `koog-features` skill
- For MCP integration → see the `koog-interop` skill
