---
name: koog-interop
description: Connect Koog to external tool servers and remote agents — MCP (Model Context Protocol for tools like Playwright, Firecrawl, Google Maps, filesystem, GitHub), A2A (Agent-to-Agent protocol for remote/distributed agents), and ACP (Agent Client Protocol for IDE/desktop client integrations, JVM only). Use when wiring an MCP server (stdio or SSE), exposing a Koog agent over A2A, consuming a remote agent, or building an IntelliJ/VSCode/Electron client. For local tool authoring see koog-tools, for HTTP endpoints see koog-integrations.
metadata:
  source: https://docs.koog.ai/model-context-protocol/
  koog_version: "1.0.0"
---

# Interop protocols

Three orthogonal protocols. Pick by direction of integration:

| Protocol | Purpose | Direction |
|---|---|---|
| **MCP** (Model Context Protocol) | Use *external tools* hosted by MCP servers | Koog agent → external tool server |
| **A2A** (Agent-to-Agent) | Talk between agents over a network | agent ↔ agent |
| **ACP** (Agent Client Protocol) | Standard bidirectional client↔agent interface (e.g., IDE integrations) | client app ↔ Koog agent |

## MCP — use external tools

MCP servers publish tools that any MCP-aware agent can call. Koog's MCP integration lets you plug those tools into a `ToolRegistry` transparently.

### Components

| Class | Role |
|---|---|
| `McpTool` | Bridges Koog's `Tool` interface to the MCP SDK |
| `McpToolDescriptorParser` | Converts MCP tool definitions to Koog descriptors |
| `McpToolRegistryProvider` | Connects to MCP servers and produces a `ToolRegistry` |

### Transports

| Transport | Use for |
|---|---|
| **stdio** | Process-based servers (CLI tools, Docker containers) |
| **SSE** | HTTP-based servers (web-hosted MCP services) |

### Pattern

```kotlin
// 1. Start an MCP server (Docker, CLI, etc.) and create the transport
val transport = /* stdio or SSE transport */

// 2. Generate a tool registry from the MCP server
val mcpRegistry = McpToolRegistryProvider.fromTransport(transport)

// 3. Pass to AIAgent
val agent = AIAgent(
    promptExecutor = executor,
    llmModel = OpenAIModels.Chat.GPT4o,
    toolRegistry = mcpRegistry + ToolRegistry { /* your local tools */ },
)
```

The agent doesn't know or care that some tools live in another process — they're just tools.

### Common MCP servers worth wiring

- **Playwright MCP** — browser automation
- **Google Maps MCP** — geocoding, directions
- **Filesystem MCP** — sandboxed file access (alternative to built-in tools)
- **Firecrawl** — web scraping
- **GitHub MCP** — issues, PRs, files
- **Database MCPs** — Postgres, SQLite, etc.

Mix MCP tools with your own and Koog's built-ins freely via `ToolRegistry` composition (`+` operator).

## A2A — agent-to-agent

The protocol for distributed agents talking to each other. Useful for multi-agent systems where specialized agents run as separate services.

### Server side (expose a Koog agent over A2A)

| Concept | Role |
|---|---|
| `AgentCard` | Declares identity, version, capabilities, transport, security |
| `AgentExecutor` | Implements your business logic — `execute()` + `cancel()` |
| `A2AServer` | The server runtime |
| `HttpJSONRPCServerTransport` | Standard transport |

```kotlin
val card = AgentCard(name = "research-bot", version = "1.0", /* skills, transport, security */)

class MyExecutor : AgentExecutor {
    override suspend fun execute(req, eventProcessor) {
        // For simple agents:
        eventProcessor.sendMessage(response)
        // For task-based agents:
        eventProcessor.sendTaskEvent(TaskStatusUpdateEvent(...))
    }
    override suspend fun cancel(...) { /* ... */ }
}

A2AServer(executor = MyExecutor(), card = card)
    .withTransport(HttpJSONRPCServerTransport(port = 8080))
    .start()
```

### Client side (consume an A2A agent from another Koog agent)

```kotlin
val transport  = HttpJSONRPCClientTransport(url = "http://remote-agent:8080")
val resolver   = UrlAgentCardResolver(url = "http://remote-agent:8080/.well-known/agent.json")

val client = A2AClient(transport = transport, agentCardResolver = resolver)

client.connect()                          // fetch + cache the AgentCard
val response = client.sendMessage(...)    // single request/response
client.sendMessageStreaming(...)          // streaming via Flow
client.getTask(id) / client.cancelTask(id) // for task-based agents
```

### When to use A2A vs MCP vs A2A-as-tool

- **MCP**: integrating tools (deterministic functions)
- **A2A**: integrating *agents* (autonomous, may run their own loops)
- You can also wrap a remote agent as a Koog tool (see `createAgentTool()` in `koog-tools`) — useful when the calling agent should treat the remote as a black-box function call

## ACP — bidirectional client/agent

For IDE plugins, desktop apps, and other interactive clients. **JVM-only.**

### Install

```kotlin
implementation("ai.koog:agents-features-acp:$koogVersion")
```

### Components

| Interface | Role |
|---|---|
| `AgentSupport` | Manages agent identity and session lifecycle |
| `AgentSession` | Handles individual conversations; implements `prompt()` |

### Wire your Koog agent into ACP

```kotlin
class MySession(val koogAgent: AIAgent) : AgentSession {
    override suspend fun prompt(input: String): String {
        return koogAgent.run(input)
    }
}

install(AcpAgent) {
    sessionId = "session-123"
    protocol = acpProtocol
    eventsProducer = eventChannel
    setDefaultNotifications = true   // auto-emit on completion/failure/LLM/tool events
}
```

### Default events

With `setDefaultNotifications = true`, Koog emits ACP notifications for:
- Agent completion
- Execution failures
- LLM responses
- Tool call lifecycle (start, complete, fail)

Disable for custom event flows. Use `sendEvent` or the raw `protocol` reference to push custom events anytime.

### Use cases

- IntelliJ IDEA plugin running a Koog agent
- VSCode extension
- Electron-based desktop app
- Any client that wants standardized agent communication beyond plain HTTP/JSON

## Decision matrix

| Goal | Protocol |
|---|---|
| Add browser/web/maps/DB capabilities to your agent | MCP (find a server, plug it in) |
| Two specialized Koog agents need to call each other | A2A |
| Expose your agent to an IDE plugin | ACP |
| Expose your agent to non-Koog clients | A2A server (or plain HTTP if you don't need the protocol) |
| Consume a tool from an LLM agnostic perspective | MCP |
| Build a multi-agent system | A2A + `createAgentTool()` |

## Pitfalls

- **Confusing MCP and A2A** — MCP = tools, A2A = agents. Different abstractions.
- **stdio MCP transport in production** — process management gets ugly; prefer SSE for hosted services
- **Forgetting `AgentCardResolver`** — A2A clients need to discover the remote agent's card before sending
- **ACP outside JVM** — JVM-only; multiplatform projects need a different bridge

## Related skills

- `koog-tools` — `ToolRegistry` composition, `createAgentTool()` for agent-as-tool
- `koog-features` — ACP install pattern
- `koog-integrations` — Ktor for HTTP servers (often paired with A2A)
