---
name: koog-agents
description: Pick between Koog's basic, functional, and graph-based agent styles and configure AIAgent parameters (systemPrompt, llmModel, toolRegistry, temperature, maxIterations). Use when starting a new agent, choosing between implicit tool-use loops and explicit workflows, or troubleshooting agent loop behavior (iteration limits, system prompt, tool dispatch). Do not use for graph DSL internals (see koog-strategies-graphs), tool authoring (see koog-tools), or feature installation (see koog-features).
metadata:
  source: https://docs.koog.ai/agents/
  koog_version: "1.0.0"
---

# Koog agent types

Koog ships three agent styles. Pick by how much control you need over the loop.

| Style | Loop | Visualization | Persistence | Use when |
|---|---|---|---|---|
| **Basic** | Implicit (Koog runs the LLM → tools → LLM loop) | No | Yes (via Persistence feature) | Standard tool-use agents — 80% of cases |
| **Functional** | Explicit (you write the function) | No | No | Rapid prototyping; sequential pipelines (draft → refine → format) |
| **Graph-based** | Explicit (you wire nodes + edges) | Yes | Yes | Complex branching/conditional workflows, multi-agent orchestration |

## Basic agent (default choice)

```kotlin
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor

// `simple*Executor` helpers do NOT exist in 1.0 — wrap a client in an executor.
val agent = AIAgent(
    promptExecutor = MultiLLMPromptExecutor(OpenAILLMClient(System.getenv("OPENAI_API_KEY"))),
    llmModel = OpenAIModels.Chat.GPT4o,
    systemPrompt = "You are a helpful assistant.",
    toolRegistry = ToolRegistry { tools(MyToolSet()) },
    temperature = 0.7,
    maxIterations = 50,            // safety cap on the tool-use loop
)

val result = agent.run("your question here")
```

### Parameters

| Param | Required | Notes |
|---|---|---|
| `promptExecutor` | yes | A `PromptExecutor` — e.g. `MultiLLMPromptExecutor(client)` |
| `llmModel` | yes | A model from a `*Models` object |
| `systemPrompt` | no | Defines role and behavior. Recommended even when optional. |
| `toolRegistry` | no | Omit for chat-only agents |
| `temperature` | no | Default varies by model; 0.7 is a common starting point |
| `maxIterations` | no | Default 50. Hard cap on LLM↔tool back-and-forth. |

### What the basic agent does internally

1. Send system prompt + user input to the LLM
2. If LLM returns a text message → return it (done)
3. If LLM returns tool calls → execute each tool, append results, loop to step 1
4. Stop at `maxIterations` regardless

This is the right shape for the vast majority of agents. Reach for graph-based only when this loop genuinely doesn't fit.

## Functional agent

Write the loop yourself when you need a fixed pipeline (no conditional branching needed) but want LLM in the middle.

```kotlin
val agent = AIAgent(
    promptExecutor = executor,
    llmModel = model,
    strategy = functionalStrategy { input ->
        val draft = llm.execute("Draft an answer for: $input")
        val improved = llm.execute("Improve this draft: $draft")
        val formatted = llm.execute("Format as markdown: $improved")
        formatted
    },
)
```

When to choose over basic:
- Need a fixed sequence of LLM calls (draft → refine → format)
- Want explicit control over what each step does
- Prototyping before deciding if you need a graph

When NOT to choose:
- Need conditional branching → graph-based
- Loop is just "LLM + tools" → basic agent

Tradeoffs: no visualization, no automatic persistence, no built-in iteration cap.

## Graph-based agent

Explicit state machine — nodes are actions, edges are transitions.

```kotlin
val strategy = strategy<String, String>("calculator") {
    val request by nodeLLMRequest()
    val execTools by nodeExecuteTools()
    val sendResults by nodeLLMSendToolResults()

    edge(nodeStart forwardTo request)
    edge(request forwardTo execTools onToolCalls { true })
    edge(request forwardTo nodeFinish onTextMessage { true })  // text → done
    edge(execTools forwardTo sendResults)
    edge(sendResults forwardTo request)                         // loop back
}

val agent = AIAgent(
    promptExecutor = executor,
    llmModel = model,
    strategy = strategy,
    toolRegistry = ToolRegistry { tools(calcTools) },
)
```

This graph is exactly what a basic agent does implicitly. Reaching for the explicit form makes sense when:

- You want branching beyond text-vs-tools (e.g., classify input → route to different LLM prompts)
- You're composing **subgraphs** for distinct subsystems (summarizer, recall, etc.)
- You need parallel node execution
- You want every step traced and checkpoint-persistent

### Standard nodes

| Node | What it does |
|---|---|
| `nodeStart` | Entry point. Receives initial input. |
| `nodeFinish` | Exit. Returns final output. |
| `nodeLLMRequest()` | Send prompt to LLM, return its message |
| `nodeExecuteTools()` | Run all tool calls from previous LLM message |
| `nodeLLMSendToolResults()` | Send tool results back to the LLM |

### Edge conditions

| Condition | Fires when |
|---|---|
| `onToolCalls { true }` | LLM message contains tool-call parts |
| `onTextMessage { true }` | LLM message is plain text (extracts the text into the next node's input) |
| `onMessageParts(MessagePart.Text::class) transformed { ... }` | Match specific part types with optional transformation |
| `onCondition { msg -> ... }` | Arbitrary predicate over the previous output |

## Requirements (all styles)

- JDK 17+
- Kotlin 2.2.0+ or Java
- Gradle 8.0+ or Maven 3.8+

## Decision tree

```
Is your loop "send to LLM, run any tools, repeat until done"?
├── YES → basic agent
└── NO → does the loop have conditional branching?
         ├── NO  → functional agent (fixed pipeline)
         └── YES → graph-based agent
```

## Pitfalls

- **Forgetting `maxIterations`** on basic agents — a misbehaving model can loop until budget runs out. Default 50 is sensible; lower for cost-sensitive paths.
- **Choosing graph-based prematurely** — most agents don't need it. The basic agent is itself implemented as a tiny graph; you only need explicit graphs when your shape is genuinely different.
- **Skipping the system prompt** — leads to inconsistent behavior. Even "You are a helpful assistant" is better than nothing.
- **No tools needed → still passing a `toolRegistry`** — leave it null instead of passing an empty registry.

## Related skills

- `koog-tools` — defining tools, ToolRegistry, built-ins
- `koog-strategies-graphs` — deep dive on the graph DSL, subgraphs, custom nodes
- `koog-prompts-models` — system prompt construction, LLM parameters
