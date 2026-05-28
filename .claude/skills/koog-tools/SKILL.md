---
name: koog-tools
description: Define and register Koog tools — annotation-based (@Tool/@LLMDescription/ToolSet, JVM only) or class-based (Tool<Args, Result>/SimpleTool, multiplatform). Covers ToolRegistry composition, agent-as-tool, and serialization (KotlinxSerializer/JacksonSerializer). NOTE for 1.0.0: built-in file/user tools (ReadFileTool, SayToUser, etc.) described in docs are NOT on the koog-agents umbrella classpath — verify before use; confirmed built-ins are FinishTool/TerminationTool. Use when adding capabilities to an agent. Do not use for external tool servers like Playwright, Firecrawl, or Google Maps — see koog-interop (MCP) instead.
metadata:
  source: https://docs.koog.ai/tools-overview/
  koog_version: "1.0.0"
---

# Tools

Tools are functions agents can call. Koog has two main ways to define your own (annotation-based and class-based), plus a small set of built-in tools (verify availability per version — see caveat below).

## Decision matrix

| Approach | When to use | Multiplatform? |
|---|---|---|
| **Built-in tool** | File ops, user comm, exit — don't write your own | ✓ (with provider) |
| **Annotation-based (`@Tool`)** | JVM-only, want minimal boilerplate, parameters are primitives | ✗ JVM only |
| **Class-based (`Tool<Args, Result>` / `SimpleTool`)** | Multiplatform; complex args; agent-context access | ✓ |

## Built-in tools

> **⚠️ Verification caveat (Koog 1.0.0).** Earlier docs describe built-in tools
> named `SayToUser`, `AskUser`, `ExitTool`, `ReadFileTool`, `WriteFileTool`,
> `EditFileTool`, `ListDirectoryTool` (with a `JVMFileSystemProvider.ReadOnly/
> ReadWrite` argument). **These class names are NOT present on the `koog-agents`
> 1.0.0 umbrella classpath** as written — they may live in a separate module, be
> renamed, or be defined as objects. Verify the exact class + module against your
> build before using them. The confirmed-present built-ins are below.

**Confirmed present in `koog-agents` 1.0.0:**

| Tool | Location | Purpose |
|---|---|---|
| `FinishTool` | `ai.koog.agents.ext.agent.FinishTool` | Signal task completion / terminate the run |
| `TerminationTool` | `ai.koog.agents.core.environment.TerminationTool` | Termination signalling |

For file access, user prompts, etc., the safe path in 1.0 is to **write your own
annotation-based tools** (below) or wire an MCP filesystem server (see
`koog-interop`) until you've confirmed the built-in file-tool API for your
Koog version.

### Registering tools

```kotlin
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor

val toolRegistry = ToolRegistry {
    tools(MyToolSet())            // your annotation-based ToolSet (see below)
}

val agent = AIAgent(
    promptExecutor = MultiLLMPromptExecutor(OpenAILLMClient(apiToken)),
    systemPrompt = "You are a helpful assistant.",
    llmModel = OpenAIModels.Chat.GPT4o,
    toolRegistry = toolRegistry,
)
```

## Annotation-based tools (JVM)

Lightest path to expose Kotlin/Java functions to the LLM.

```kotlin
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.Tool
import ai.koog.agents.core.tools.reflect.LLMDescription

@LLMDescription("Tools for getting weather information")
class WeatherToolSet : ToolSet {

    @Tool
    @LLMDescription("Get the current weather for a location")
    fun getWeather(
        @LLMDescription("The city and state/country, e.g. 'Berlin, DE'")
        location: String
    ): String {
        return "The weather in $location is sunny and 22°C"
    }
}
```

### Registering a ToolSet

```kotlin
val toolRegistry = ToolRegistry {
    tools(WeatherToolSet())       // note: `tools()` (plural) for a ToolSet instance
    tools(AnotherToolSet())       // compose as many sets as you like
}
```

### Annotation rules

- `@Tool` on the function (optional `customName` to rename for the LLM)
- `@LLMDescription` on the class (set group description), function (what the tool does), and each parameter
- Parameter types should be primitives — `String`, `Boolean`, `Int`, `Double`, `List<T>` of primitives
- Return types: any serializable type or a plain `String`
- The class implements `ToolSet` interface (marker)

### Java equivalent

Static methods with `@Tool` and `@LLMDescription`. The framework handles serialization + registration via reflection. Subclassing `Tool<Args, Result>` from Java is **not** supported — annotations are the Java path.

## Class-based tools

For full control or multiplatform projects.

### `Tool<Args, Result>` — full control

```kotlin
@Serializable
data class WeatherArgs(val location: String, val units: String = "celsius")

@Serializable
data class WeatherResult(val description: String, val tempC: Double)

class WeatherTool : Tool<WeatherArgs, WeatherResult>() {
    override val argsSerializer = WeatherArgs.serializer()

    override val descriptor = ToolDescriptor(
        name = "get_weather",
        description = "Get the current weather for a location",
        parameters = /* derived from the data class */,
    )

    override suspend fun execute(args: WeatherArgs): WeatherResult {
        return WeatherResult("sunny", 22.0)
    }
}
```

### `SimpleTool<Args>` — text-returning shortcut

For tools that return plain text (most tools). Extends `Tool<Args, ToolResult.Text>`. You implement only:
- `argsSerializer`
- `descriptor`
- `doExecute(args)` returning a `String`

### `AgentContextAwareTool` — needs agent state

Inject live `AIAgentContext` to access agent state, run IDs, configuration during execution.

### Custom output formatting

Kotlin: implement `ToolResult.TextSerializable` and override `textForLLM()` to control what the LLM sees.
Java: return formatted strings (markdown, etc.) directly.

## ToolRegistry

The container for tools available to an agent.

```kotlin
val base = ToolRegistry {
    tools(FilesystemToolSet())     // your own annotation-based sets
}

val extra = ToolRegistry {
    tools(WeatherToolSet())
}

// Compose
val combined = base + extra        // Kotlin operator
// or in Java: base.plus(extra)
```

Capabilities:
- Retrieve tools by name (`getTool("name")`) or by type
- Merge registries with `+` / `.plus()`
- Pass to the agent via `toolRegistry = ...`

## Tools run in `AIAgentLLMWriteSession`

You don't normally call tools manually — the agent loop does. But within a custom node (graph-based agent) you can call:
- Single tool call
- Named tool call
- Type-based tool call
- **Parallel** via `toParallelToolCallsRaw` extension

## Agents as tools

`createAgentTool()` wraps an agent so it can be invoked by another agent. Use for hierarchical / multi-agent architectures where a "coordinator" agent delegates to specialized "worker" agents.

## Serialization

Tool args + results are JSON-serialized via Koog's library-agnostic `JSONSerializer` interface.

| Backend | Source | When |
|---|---|---|
| `KotlinxSerializer` | `agents-core` (default) | Default — uses kotlinx-serialization |
| `JacksonSerializer` | `ai.koog:serialization-jackson` | JVM-only; existing Jackson codebase |

Configure via `AIAgentConfig`:

```kotlin
AIAgentConfig(serializer = JacksonSerializer())
```

If omitted, `KotlinxSerializer` is used. Both work with `JSONElement` (Koog's neutral JSON type — `JSONObject` / `JSONArray` / `JSONPrimitive`).

## Best practices

- **One ToolSet per logical group** — `WeatherToolSet`, `FilesystemToolSet`, etc.
- **Clear `@LLMDescription` on every parameter** — the LLM picks tools by reading these
- **Return informative results** — don't return `"ok"` when you could return `"wrote 4231 bytes to /path/to/file"`
- **Primitive types** for annotation-based tools; reach for class-based when you need structured args
- **Check built-ins before writing your own** — but verify the class exists in your Koog version first (see caveat above)
- **Scope filesystem access** with `ReadOnly` provider unless you specifically need writes

## Pitfalls

- Subclassing `Tool<Args, Result>` from Java — **not supported**, use annotations
- Forgetting that `tools(set)` is plural for a ToolSet, `tool(t)` is singular for a single tool
- Returning huge results — they end up back in the LLM context; truncate or summarize
- Tool name collisions across merged registries — names must be unique

## Related skills

- `koog-agents` — `toolRegistry` parameter on AIAgent
- `koog-strategies-graphs` — running tools manually inside custom nodes
- `koog-interop` — MCP tools (external tool servers) plug into ToolRegistry
