# Kermes

A Kotlin/JVM agent inspired by [Hermes Agent](https://github.com/NousResearch/hermes-agent), built on [Koog](https://github.com/JetBrains/koog).

## Mission

Build a self-improving, skill-aware AI agent that runs anywhere the JVM runs, without locking us into a single LLM provider or rebuilding what Koog already ships.

## Design principles

1. **System efficiency** — bounded context regardless of skill/tool count
2. **Speed to v1** — ship a working prototype fast; cut anything not on the critical path
3. **Provider diversity** — no Anthropic-only features (no exclusive prompt-cache breakpoints, no provider-locked APIs)
4. **Maximum Koog reuse** — if Koog ships it, we don't write it

## Implementation status (as of first working build)

The project compiles (5 modules, ~2700 LOC + 21 tests) and boots end-to-end
against **Koog 1.0.0** via the single umbrella artifact `ai.koog:koog-agents`
(transitively pulls agents-core, tools, event-handler, memory, snapshot,
opentelemetry, embeddings-llm, prompt-executor clients, retry, etc.).

| Component | Status | Notes |
|---|---|---|
| Gradle multi-module build | ✅ done | `kermes-core/-tools/-schedule/-tui/-app` |
| Koog `AIAgent` (basic) wiring | ✅ done | `OpenAILLMClient` → `RetryingLLMClient` → `MultiLLMPromptExecutor` |
| `EventHandler` feature (audit log) | ✅ done | `onToolCallStarting/Completed/Failed`, writes `~/.kermes/audit.log` |
| Vector store | ✅ done | **Custom** `KoogVectorStore` — Koog 1.0 ships no concrete impl (see below) |
| Skill registry + `SkillsToolSet` | ✅ done | agentskills.io parser, scoped roots |
| `LearnerToolSet` (six perspectives) | ✅ done | tools registered; routing logic present |
| `MemoryStore` (markdown + vectors) | ✅ done | |
| `bash` + `web_search` tools | ✅ done | annotation-based `@Tool` sets |
| `FileToolSet` (read/write/edit/list) | ✅ done | scoped to working tree; write/edit gated by `PermissionGuard`. Koog 1.0 ships no usable built-in file tools, so we wrote our own. |
| REPL + slash commands | ✅ done | `/help /skills /memory /inbox /new /model /yolo /quit` |
| Lifecycle CLI | ✅ done | `kermes init / status / version / help / -q "<prompt>"` (init/status/version/help need no API key) |
| `kermes-schedule` (cron + inbox) | ✅ done + **wired** | started from Main when `schedules.yaml` exists; delivers to inbox |
| `ChatMemory` install | ✅ done | `install(ChatMemory) { windowSize(50) }`; run keyed by session id |
| `Persistence` install | ✅ done | `install(Persistence) { storage = JVMFilePersistenceStorageProvider(checkpoints) }` |
| `PermissionGuard` enforcement | ✅ done | **tool-boundary** gate, not a pipeline interceptor — Koog 1.0's tool-call interceptor is observe-only. Gating in the tool returns "denied" to the model instead of aborting. Injected into `BashToolSet`/`SkillsToolSet`/`FileToolSet`. Runtime `/yolo` toggle. |
| Skill manifest in prompt | ✅ done | **static** full manifest injected at build (correct at MVP scale) |
| Eager memory in prompt | ✅ done | `user.md` + `preferences.md` injected into system prompt |
| Self-learner auto-trigger | ✅ done | `SessionLearner` — at session end a tool-less extractor agent pulls durable facts from the transcript and routes them to the six perspectives. Provider-agnostic plain-text format (not Koog's graph-coupled `FactRetrievalHistoryCompressionStrategy`). |
| Per-turn top-K manifest injection | ⏳ deferred | static full manifest suffices until ~100+ skills |
| TUI token streaming | ⏳ deferred | needs a custom streaming strategy graph (`nodeLLMRequestStreaming` outputs `Flow<StreamFrame>`, forcing a full tool-loop rebuild). High risk, polish-only. |

**Reality-check on Koog reuse:** Koog 1.0 does **not** ship a concrete vector
store (`EmbeddingStorage` / `JVMFileVectorStorageBackend` do not exist — only
`SearchStorage`/`WriteStorage` interfaces + `SimilaritySearchRequest`). We wrote
a small file-backed `KoogVectorStore` using Koog's `LLMEmbedder` +
`Vector.cosineSimilarity()`. Also `simpleOpenAIExecutor` does not exist in 1.0;
the single-provider path is `MultiLLMPromptExecutor(client)`. And the tool-call
interceptor (`interceptToolCallStarting`) is **observe-only** (`Interceptor`, not
`TransformInterceptor`) — a custom Feature cannot block a tool call, so
permission enforcement lives at the tool boundary instead. Sections below are
corrected to match the shipping API.

**MVP behavioral completeness:** every MVP capability is implemented —
skills + discovery, memory (+ ChatMemory/Persistence/eager injection),
self-learner auto-trigger, permission gating, file/bash/web tools, scheduler,
audit log, embedding-based skill retrieval, plus the terminal command surface
(lifecycle CLI + slash commands + one-shot). The only deferrals are **TUI token
streaming** (high-risk graph rewrite, polish-only) and **per-turn top-K manifest**
(only needed at ~100+ skills). Tests: 21 green; boots end-to-end and all commands
verified.

## Terminal command surface

Commands fall into four categories. Kermes ships the MVP subset; the rest are
deferred (Hermes has ~40 CLI families + ~60 slash commands — most are product-scale).

### Lifecycle / management (`kermes <cmd>` — no API key needed)
| Command | Status | Does |
|---|---|---|
| `kermes init` | ✅ | Bootstrap `~/.kermes/*`, seed the sample skill, write `schedules.yaml`, print next steps |
| `kermes status` | ✅ | Non-network health: key set?, model, base URL, dir presence, skill count |
| `kermes version` | ✅ | Print version |
| `kermes help` | ✅ | Usage |
| `kermes config` / `doctor` / `update` / `backup` | ⏳ Phase 2 | `config` needs the `config.yaml` overlay we haven't built |

### Interaction
| Command | Status | Does |
|---|---|---|
| `kermes` | ✅ | Interactive REPL |
| `kermes -q "<prompt>"` | ✅ | One-shot: run + print + exit (no scheduler/REPL) |

### In-session slash commands
| Command | Status |
|---|---|
| `/help /skills /memory /inbox /new /model /yolo /quit` | ✅ |
| `/usage /cost` (token tracking), `/compress /rollback /branch /retry` (session control) | ⏳ Phase 2 |

### Resource management (`kermes skill/cron/sessions/logs`)
Deferred. The agent self-manages skills via the `create_skill` tool, and
`schedules.yaml` is hand-edited for now. CLI wrappers come in Phase 2.

### Distribution
`curl … | bash` installer + packaged distribution → see [Distribution & install](#distribution--install).

## Settled architecture decisions

| Concern | Decision | Notes |
|---|---|---|
| **Skill storage** | `<project>/.kermes/skills/` → `~/.kermes/skills/` → bundled (resources) | Project scope takes precedence over user scope |
| **Skill discovery** | Embedding-based top-K retrieval via Koog `LLMEmbedder` + custom `KoogVectorStore` | Replaces Hermes' Anthropic prompt-cache trick. Provider-agnostic. |
| **Skill activation** | Progressive disclosure (`load_skill` tool returns full `SKILL.md`) | Per agentskills.io spec |
| **Sandbox** | Local with command approval at v1; Docker in Phase 2 | Docker is ~1-2 weeks of plumbing for zero v1 functional gain |
| **Self-learner** | Six perspective-specific tools (no single `learn` verb): `learn_procedure`, `remember_user`, `set_preference`, `remember_context`, `record_episode`, `note_feedback` | Each perspective has its own storage, trigger, and idempotency rule. [Details](#self-learner) |
| **Memory subsystem** | Markdown files + custom `KoogVectorStore` (tagged single-pool); eager system-prompt for identity/prefs, lazy `recall()` for episodes. Session-end fact extraction via `nodeLLMCompressHistory` + `FactRetrievalHistoryCompressionStrategy`. | `MemoryStore` + tools done. `ChatMemory`/`HistoryCompression`/`Persistence` installs still pending. [Details](#memory-subsystem) |
| **Memory scaling** | File-size caps in MVP; hierarchical episode consolidation (daily → weekly → monthly) in Phase 2; Qdrant + GC in Phase 3 | Embedding volume stays roughly constant via roll-ups |
| **Drift handling** | Upsert-by-key for identity/preferences + time-decay scoring on retrieval + explicit `correct_memory` tool | Time decay is the quiet workhorse |
| **Agent type** | Koog **basic agent** (implicit LLM↔tool loop) with custom Features layered on | No graph DSL for MVP — adds no v1 value. Phase 2+ may introduce subgraphs for distinct subsystems. |
| **LLM provider** | `OpenAILLMClient` with `baseUrl` override → `RetryingLLMClient` (PRODUCTION config) → `MultiLLMPromptExecutor(client)`; default `baseUrl` = OpenRouter, default model a strong tool-use model (e.g. GPT-4o) | `simpleOpenAIExecutor` does NOT exist in 1.0. Any OpenAI-compatible endpoint works (OpenAI, OpenRouter, Together, Groq, vLLM, Ollama, LM Studio). [Details](#llm-provider) |
| **Vector store** | Custom `KoogVectorStore`: `LLMEmbedder` + in-memory rows + cosine via `Vector.cosineSimilarity()`, persisted to one JSON file. Behind a Kermes `VectorStore` interface. | Koog 1.0 ships NO concrete vector store — only interfaces. Phase 2 can swap to Qdrant without touching call sites. [Details](#vector-store) |
| **Embeddings** | `LLMEmbedder(OpenAILLMClient(...), <model>)`; default model `text-embedding-3-small`; Ollama via `OllamaEmbeddingModels.NOMIC_EMBED_TEXT` for fully-local | Mirrors LLM provider story |
| **Skill distribution** | Plain folder copy + `kermes skill install <git-url>` | No registry in MVP. agentskills.io registry pull deferred to Phase 2. [Details](#skill-distribution) |
| **Permissions** | Tiered defaults (read=auto, write/exec=prompt) + session memory + config allowlist/denylist | Implemented as a custom Koog Feature with pipeline interceptor (for blocking) + `EventHandler` (for audit). Defaults flip to permissive inside Docker (Phase 2). [Details](#permissions--approval) |

## Feature triage

Hermes parity surface, triaged against our constraints.

| Hermes feature | Kermes phase | Strategy |
|---|---|---|
| Skill system (agentskills.io) | **MVP** | Custom on Koog (see [skills design](#skills-system)) |
| Self-learner (six perspectives) | **MVP** ✅ | Per-perspective `@Tool`s in a `LearnerToolSet` + `SessionLearner` auto-trigger (tool-less extractor agent → routes to perspectives). Plain-text format, provider-agnostic. |
| Memory subsystem (Honcho-style + episodic) | **MVP** | Markdown files + custom `KoogVectorStore`. (Planned: `install(ChatMemory)` buffer, `install(HistoryCompression)` summaries, `install(Persistence)` resume.) |
| File / bash / web-search tools | **MVP** | Use Koog built-ins (`ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ListDirectoryTool`, plus `SayToUser`/`AskUser`); hand-write only `bash` and `web_search` |
| Local execution | **MVP** | Subprocess + approval prompt |
| Minimal TUI (REPL + Ctrl-C) | **MVP** ✅ | Hand-rolled REPL + slash commands. Token streaming **deferred** (needs custom streaming strategy graph — high risk, polish-only). |
| Embedding-based skill retrieval | **MVP** | Koog `LLMEmbedder` + custom `KoogVectorStore` (cosine scan) |
| Scheduled tasks (basic) | **MVP** | Coroutine scheduler + `cron-utils`; deliver to inbox file. Resumable via `install(Persistence)`. |
| Audit log | **MVP** | `install(EventHandler)` on `onToolCallStarting/Completed/Failed`; appends to `~/.kermes/audit.log` |
| MCP client | **Phase 2** | Koog already has it; just expose config |
| Browser tool | **Phase 2** | Wire Playwright MCP server (no native impl) |
| Docker sandbox | **Phase 2** | Bind-mount, cap-drop, persistent container |
| One messaging gateway (Telegram) | **Phase 2** | Ktor + Telegram bot API |
| Scheduled-task delivery to gateways | **Phase 2** | Route inbox → Telegram once gateway exists |
| Slash commands in TUI | **MVP** ✅ | `/help /skills /memory /inbox /new /model /yolo /quit` (pulled forward — trivial, makes the agent feel real) |
| Lifecycle CLI (`init`/`status`/`version`/`-q`) | **MVP** ✅ | onboarding + one-shot; no API key for init/status/version |
| Observability + tracing | **Phase 2** | `install(OpenTelemetry)` + `addLangfuseExporter` (or W&B Weave / Datadog); auto spans for `InvokeAgentSpan` / `InferenceSpan` / `ExecuteToolSpan` |
| Content moderation | **Phase 2** | `moderate()` on user input before agent runs; OpenAI `Moderation.Omni` or Ollama `LLAMA_GUARD_3` |
| Image gen / TTS / cloud browser | **Phase 2** | Per-provider `@Tool` wrappers |
| SSH sandbox | **Phase 3** | sshj or JSch |
| Discord / Slack / Matrix gateways | **Phase 3** | One per release |
| Tool Gateway abstraction | **Phase 3** | Only once we have 3+ providers per category |
| Multi-platform cron delivery | **Phase 3** | After gateways exist |
| Home Assistant integration | **Phase 3** | Niche — only on user demand |
| WhatsApp / Signal | **Maybe never** | Business API / signal-cli friction |
| Daytona / Modal / Singularity | **Won't do** | Docker covers the threat model |
| DSPy + GEPA self-evolution | **Won't do** | JVM ecosystem gap; offline-only anyway |

## MVP scope (target: 4-6 weeks)

A single-user agent you can run in a terminal that loads skills, learns about you across sessions, and can be extended by dropping a `SKILL.md` into a folder.

**Modules**
- `kermes-core` — Koog agent wiring, skill engine, memory, perspective routing, `PermissionGuard` Feature
- `kermes-tools` — `bash` and `web_search` only (file + user-comm tools come from Koog built-ins)
- `kermes-schedule` — coroutine cron, schedule store, inbox writer
- `kermes-tui` — REPL consuming Koog streaming frames, inbox surfacing on launch
- `kermes-app` — `main()`, config loading, Koog feature installs, agent assembly

**Koog Features — installed vs planned**

Wired now:
- `EventHandler` — audit log via `onToolCallStarting/Completed/Failed` ✅

Planned (feature classes are already on the classpath via `koog-agents`):
- `ChatMemory` — per-session conversation buffer keyed by TUI session ID
- `HistoryCompression` (default `WholeHistory`) — keeps context bounded
- Custom `nodeLLMCompressHistory` with `FactRetrievalHistoryCompressionStrategy` at session end — extracts the six perspectives' `Concept`s and routes to the matching tools
- `Persistence` (from `agents-features-snapshot`) — resume scheduled tasks and crashed runs
- `PermissionGuard` (custom Kermes Feature) — pipeline interceptor enforces allow/deny; EventHandler logs the decision
- `SkillManifestInjector` (custom Feature) — `onLLMCallStarting` per-turn top-K manifest

**External dependencies (actual, resolved)**
- `ai.koog:koog-agents:1.0.0` — the **umbrella** artifact. Transitively brings agents-core, agents-tools, agents-features-event-handler, agents-features-memory, agents-features-snapshot, agents-features-opentelemetry, agents-features-trace, agents-mcp, embeddings-base, embeddings-llm, prompt-executor clients (OpenAI/Anthropic/Google/etc.), retry, prompt-cache. No separate sub-artifacts needed for MVP.
- kotlinx-coroutines, kotlinx-serialization-json (vector index persistence)
- kaml (frontmatter parsing + schedule store)
- `com.cronutils:cron-utils` (cron expression parsing + next-fire calculation)
- slf4j + logback (logging)
- mordant (TUI colors, basic line editing) — Phase 2
- HTTP client comes transitively via Koog's Ktor client; no separate dep needed for MVP

> Note: `ai.koog:agents-features-memory` is also declared but is redundant —
> it's already transitive via `koog-agents`. Harmless; can be dropped.

**Out of scope for MVP**
- Docker / SSH / cloud sandboxes
- Messaging gateways (Telegram etc.) — scheduled results land in an inbox file instead
- Slash commands
- MCP client wiring (the capability exists in Koog; we just don't expose it yet)
- TTS / image gen / cloud browser

## Phase 2 (8-12 weeks after MVP)

Goal: turn the local prototype into something a small team can self-host.

- Docker sandbox executor (read-only skills mount, writable workdir, dropped caps)
- MCP client config — let users plug in Playwright, Firecrawl, anything
- Telegram bot gateway (Ktor + telegram-bot-api lib)
- Scheduled-task delivery to Telegram (in addition to MVP inbox file)
- Slash commands in TUI (`/skills`, `/memory`, `/model`, `/cost`, `/schedule`)
- `install(OpenTelemetry)` + `addLangfuseExporter()` (or W&B Weave / Datadog) for production tracing
- Content moderation pass on user input via `moderate()` (OpenAI `Moderation.Omni` or Ollama `LLAMA_GUARD_3`)
- Image generation tool (FAL or alternative)
- TTS tool (OpenAI-compatible, swappable)

## Phase 3 (post-Phase 2)

- SSH sandbox executor
- Second + third messaging gateways (Discord, then Slack/Matrix based on demand)
- Tool Gateway abstraction (only after we have 3+ providers in a category that justify it)
- Multi-platform delivery routing for cron jobs
- Home Assistant integration
- Android target via Koog Multiplatform (free with Koog 1.0)

## Won't do (until proven need)

- Daytona / Modal / Singularity backends — Docker covers ~95% of users
- WhatsApp / Signal gateways — too much per-platform friction
- DSPy + GEPA offline self-evolution — Python-only, separate research workflow
- Honcho-as-a-service integration — Koog memory is sufficient

## Skills system

See agentskills.io spec. Implementation summary:

```
.kermes/
└── skills/
    └── pdf-processing/
        ├── SKILL.md         # YAML frontmatter + body
        ├── scripts/         # executable code
        ├── references/      # docs loaded on demand
        └── assets/          # templates
```

**Tools exposed to the agent** (all `@Tool`-annotated methods on a `SkillsToolSet : ToolSet`, registered via `ToolRegistry { tools(SkillsToolSet(...)) }`):
- `load_skill(name)` — stage-2 activation
- `read_skill_file(skill, path)` — stage-3, references/assets
- `run_skill_script(skill, path, args)` — stage-3, scripts
- `create_skill(name, description, body)` — self-improvement
- `update_skill(name, body?, description?)` — self-improvement

**Discovery:** at each turn, embed the user message via Koog's `LLMEmbedder`, run a top-K cosine query against the skill-tagged rows of the custom `KoogVectorStore`, inject the top-K (default 8) descriptions into the system prompt. Constant context size regardless of skill count.

**Where the discovery code lives:** a custom Koog Feature that hooks `onLLMCallStarting` to rewrite the system prompt with the top-K manifest — cleaner than re-wiring the prompt assembly on every `agent.run()`.

## Self-learner

"Learning" is decomposed into six perspectives. Each has its own tool, storage, trigger, and idempotency rule. There is no single `learn(x)` verb — the model picks the perspective via tool descriptions.

| Perspective | Tool | Storage | Update semantics | Primary trigger |
|---|---|---|---|---|
| **User identity** | `remember_user(trait, value)` | `~/.kermes/memory/user.md` | upsert by `trait` | model detects a personal fact ("I'm a Kotlin dev") |
| **Preferences** | `set_preference(key, value)` | `~/.kermes/memory/preferences.md` | upsert by `key` | "from now on", "always", explicit correction |
| **Domain context** | `remember_context(topic, fact)` | `~/.kermes/memory/context.md` | append within `topic`, dedupe at similarity > 0.92 | factual info about env/tools |
| **Procedures** | `learn_procedure(name, description, body, scripts?)` | `~/.kermes/skills/agent-created/<name>/` | similarity check → new skill or `update_skill` | periodic nudge + post-turn summarizer |
| **Episodes** | `record_episode(when, summary, tags)` | `~/.kermes/memory/episodes/<date>/<id>.md` + vector index | append-only, time-stamped | session-end summarizer (system-initiated) |
| **Self-feedback** | `note_feedback(observation, rule)` | `~/.kermes/memory/feedback.md` | append; periodic rollup into persona | user correction of agent behavior |

**Validation on every write:**
- Length limits per file (see [Memory scaling](#memory-subsystem))
- No injection of system-prompt markers
- Schema check for keyed entries
- Synchronous embedding refresh — the call doesn't return until the vector store sees the new entry

**Guidance to the model** lives in a ~150-token block in the system prompt summarizing each perspective and when to use it. Detailed semantics live in each tool's `@LLMDescription`.

**Code shape:** all six tools are `@Tool`-annotated methods on a single `LearnerToolSet : ToolSet`, registered via `ToolRegistry { tools(LearnerToolSet(...)) }` and merged into the agent's full registry alongside `SkillsToolSet`.

**Session-end fact extraction (the trigger):** at session close, a `nodeLLMCompressHistory` runs with `FactRetrievalHistoryCompressionStrategy` and the six perspective `Concept`s:

```kotlin
val perspectives = listOf(
    Concept("user_traits",      "facts about the user's identity",       MULTIPLE),
    Concept("preferences",      "stated user preferences and rules",     MULTIPLE),
    Concept("context_facts",    "domain/environment facts mentioned",    MULTIPLE),
    Concept("procedures_seen",  "repeatable procedures demonstrated",    MULTIPLE),
    Concept("episode_summary",  "what was accomplished this session",    SINGLE),
    Concept("feedback",         "agent-behavior corrections the user gave", MULTIPLE),
)
```

The extracted facts get routed to the corresponding tools (`remember_user`, `set_preference`, ...) by a small dispatcher. We get production-tested fact extraction from Koog; we only own the perspective → tool routing.

**Storage location for agent-created procedures:** always `~/.kermes/skills/agent-created/<name>/` — never bundled or project scope.

**Quality control:**
- Per-skill usage count + last-used timestamp in `~/.kermes/skills/.stats.json`
- Phase 2: prune skills unused for 30 days, surface in TUI for user confirmation
- Before `learn_procedure` writes, do a top-K query for similar skills; if max similarity > 0.85, return a hint to the agent: "skill `foo` is highly similar — update instead?"

**Safety scan (regex deny-list):** `rm -rf /`, `curl * | sh`, `sudo`, network calls in scripts trigger a warning before write. AST scan is out of scope for MVP.

## Memory subsystem

Memory and skills are **parallel subsystems** sharing the vector store as infrastructure. Skills hold procedural knowledge (how); memory holds semantic + episodic knowledge (what's true, what happened).

### Layout

```
~/.kermes/memory/
├── user.md                      # identity facts, ≤ 2KB
├── preferences.md               # how to behave, ≤ 4KB
├── context.md                   # domain/env facts, sectioned by topic
├── feedback.md                  # agent-behavior corrections, periodically rolled up
└── episodes/
    ├── 2026-05/
    │   ├── 2026-05-28-<session>.md
    │   ├── daily.md             # Phase 2 rollup
    │   └── monthly.md           # Phase 2 rollup
    └── 2026-W22/
        └── weekly.md            # Phase 2 rollup
```

### Create — one principle

**Upsert by semantic key, except episodes** (which are inherently temporal and append-only). Every write does two things synchronously: (a) update the file, (b) write/update the embedding in the vector store. No background indexing.

### Recall — three tiers

**Tier 1 — Eager (every turn, no tool needed):**
- `user.md` + `preferences.md` injected into system prompt
- Combined budget: ~1.5KB / ~400 tokens
- File-size caps prevent prompt bloat

**Tier 2 — Implicit (every turn, hidden):**
- A custom Koog Feature hooks `onLLMCallStarting` to embed the latest user message and run a single `SimilaritySearchRequest` (top_k=12) against the tagged pool. Filtered candidates split into skill-manifest entries and "Relevant context" block in the system prompt.
- One embedding + one query per turn — bounded cost regardless of memory size

**Tier 3 — Lazy (explicit `recall(query, k=5, perspectives?)` tool):**
- Searches episodes + feedback by default (large, time-skewed pools)
- Returns markdown chunks with timestamps and tags
- Agent calls when it needs deeper history

```
[every turn]
   embed(user_message)
       ↓
   vector_store.query(top_k=12, filter: tag ∈ {skill, context})
       ↓                ↓
   top skills      top context fragments
       ↓                ↓
   skill manifest  "Relevant context" block

[on demand]
   recall(query) → vector query over episodes + feedback
```

### Scaling tiers

| Tier | When it kicks in | Mechanism |
|---|---|---|
| **A (MVP)** | always | File-size caps; date-partitioned episodes (`episodes/2026-05/`); tagged single-pool vector index |
| **B (Phase 2)** | episodes exceed ~10K entries | Hierarchical consolidation: session → daily → weekly → monthly rollups. Re-embed only summaries; drop raw embeddings (keep markdown for audit). |
| **C (Phase 3)** | multi-user / long deployment | Move vector index from file-backed to Qdrant; one collection per perspective; nightly memory GC scoring entries by usage × recency × redundancy |

### Drift / evolution

| Drift type | Mechanism |
|---|---|
| Keyed memory updated ("user changed their name") | Upsert by key — old value gone. Free. |
| Episode/context gradually stales ("user changed jobs") | **Time-decay weighting on retrieval**: `score = cosine_sim × exp(-age_days / λ)`. Old entries fade unless nothing newer is relevant. |
| Explicit correction needed ("actually X is wrong") | `correct_memory(reference, new_value)` — finds the entry, rewrites, re-embeds, logs to audit |
| Context topic evolves substantially | `update_context(topic, new_content)` replaces the topic section wholesale |

Time-decay is the quiet workhorse — handles most drift implicitly without requiring agent action.

### Koog reuse scorecard

| Piece | Koog provides | Kermes writes | Wired? |
|---|---|---|---|
| Embeddings | ✅ `LLMEmbedder` + `Vector` (with `cosineSimilarity`) | the model choice | ✅ |
| Vector store | ❌ interfaces only — **no concrete backend in 1.0** | the whole `KoogVectorStore` (file-backed, cosine scan) | ✅ |
| Conversation buffer | ✅ `ChatMemory` Feature with pluggable `ChatHistoryProvider` | session-ID strategy | ⏳ |
| Working memory + compression | ✅ `HistoryCompression` (`WholeHistory`, `FromLastNMessages`, `Chunked`) | install config | ⏳ |
| Session-end fact extraction | ✅ `FactRetrievalHistoryCompressionStrategy` + `Concept` objects | the six perspective `Concept`s + routing | ⏳ |
| Resume after crash | ✅ `Persistence` + file storage provider (`agents-features-snapshot`) | install config | ⏳ |
| Tool-call audit | ✅ `EventHandler` (`onToolCall*`) | log writer | ✅ |
| Skills (procedural) | nothing — by design | the agentskills.io engine | ✅ |
| Time-decay scoring on retrieval | nothing | `score = cos × exp(-age/λ)` wrapper | ⏳ |
| Hierarchical episode rollup | ✅ `WholeHistory` for the LLM call | the scheduler that triggers it | ⏳ |

AgentMemory is deprecated — we do **not** install it. Of the ✅-Koog-provided
features above, only Embeddings + EventHandler are wired so far; the memory
features (ChatMemory / HistoryCompression / Persistence) are on the classpath
and pending an install block.

## Scheduling

Koog has no scheduler concept — by design. We layer one on top.

**Trigger:** coroutine + [`com.cronutils:cron-utils`](https://github.com/jmrozanec/cron-utils). Parse the cron expression, `delay()` until next fire time, then call `agent.run(prompt)`. Survives process restart by persisting schedules to `~/.kermes/schedules.yaml`.

**Storage:**
```yaml
# ~/.kermes/schedules.yaml
- id: morning-brief
  cron: "0 8 * * *"
  prompt: "Summarize my unread emails and overnight GitHub notifications."
  deliver: inbox          # MVP
- id: deploy-check
  cron: "*/15 * * * *"
  prompt: "Check the prod deploy status. If anything is failing, draft an incident summary."
  deliver: inbox
```

**Delivery (MVP):** append to `~/.kermes/inbox/<schedule-id>-<timestamp>.md`. TUI shows "3 new" on launch; user opens with `/inbox`.

**Delivery (Phase 2):** add `deliver: telegram` (or any registered gateway). Same scheduler, new sink.

**Why this is enough for MVP:**
- Same memory + skill context as interactive sessions, carried by `install(Persistence) { storage = FilePersistenceStorageProvider(Path("~/.kermes/checkpoints")) }`
- No external services (no Quartz JobStore, no system cron)
- Trivially extensible — `deliver:` is just a string → `(Sink) -> Unit` lookup
- Ports cleanly to any gateway we add later

**Crash recovery:** with Persistence installed, a scheduled task that's interrupted mid-run (machine sleep, process restart) resumes from its last per-node checkpoint via `rollbackToLatestCheckpoint(context)`. No re-paying for prior LLM calls.

## LLM provider

**Default contract: OpenAI-compatible Chat Completions.** Koog's `OpenAILLMClient` accepts a `baseUrl` override, so the same client talks to OpenAI, OpenRouter, Together, Groq, Fireworks, vLLM, LM Studio, Ollama.

**Wiring:**

```kotlin
// Verified against Koog 1.0.0 (this is what kermes-app actually runs).
val rawClient = OpenAILLMClient(
    apiKey = config.apiKey,
    settings = OpenAIClientSettings(baseUrl = config.baseUrl),  // OpenRouter by default
)
val client   = RetryingLLMClient(rawClient, RetryConfig.PRODUCTION)   // 3 attempts, 1s/20s
val executor = MultiLLMPromptExecutor(client)   // single-provider path; `simpleOpenAIExecutor` does NOT exist in 1.0
val model    = OpenAIModels.Chat.GPT4o
```

**Default config:**
- `baseUrl`: OpenRouter (`https://openrouter.ai/api/v1`)
- model: a strong tool-use model (GPT-4o-class or DeepSeek-V3 at ship time — revisit at release)
- embeddings: `text-embedding-3-small`
- retries: `RetryConfig.PRODUCTION` (handles HTTP 429/500/502/503/504 transparently)

**Why not Anthropic / Gemini direct?** Violates the diversity principle and uses provider-specific schemas (Messages API, prompt-cache breakpoints) we explicitly chose to avoid relying on.

**Caveat for users:** tool-use quality is model-bound, not framework-bound. Local 7B-13B models fail at multi-turn tool calls. Docs steer toward 30B+ class or hosted frontier models. Ship presets in `.env.example` for OpenAI, OpenRouter, Ollama, and Nous Portal.

**Dev convenience:** wrap in `CachedPromptExecutor` with `FilePromptCache(Path("./.kermes/llm-cache"))` to avoid re-paying for identical prompts during iteration.

## Vector store

**Reality:** Koog 1.0 ships embedding *interfaces* (`Embedder`, `Vector`) and an
`LLMEmbedder` impl, plus RAG *search-request* types (`SimilaritySearchRequest`)
and storage *interfaces* (`SearchStorage`, `WriteStorage`) — but **no concrete
vector storage backend**. (`JVMFileVectorStorageBackend`, `EmbeddingStorage`,
`FileDocumentEmbeddingStorage` do not exist in 1.0.) So we wrote a small one.

**MVP wiring (what `kermes-app` actually runs):**

```kotlin
// Koog provides the embedder; Kermes provides the store.
val embeddingClient = OpenAILLMClient(apiKey, OpenAIClientSettings(baseUrl = baseUrl))
val embedder        = LLMEmbedder(embeddingClient, OpenAIModels.Embeddings.TextEmbedding3Small)
val vectors         = KoogVectorStore(embedder, storageRoot = Path("~/.kermes/vectors"))
```

`KoogVectorStore` (in `kermes-core`):
- `embed(text)` via Koog's `LLMEmbedder` → `Vector`
- stores rows `{ id, text, tag, vector, attrs }` in a `LinkedHashMap`
- query = linear cosine scan using Koog's built-in `Vector.cosineSimilarity()`
- persists the whole index to one `index.json` synchronously after each write

**Tag strategy:** single-pool; rows carry a `DocTag` (skill / context / episode /
feedback) and queries filter by tag before scoring.

**Sizing for MVP:** hundreds of skills + low-thousands of memory vectors — a
linear scan is fine. **Phase 2 swap:** implement the same Kermes `VectorStore`
interface against Qdrant/pgvector when scale demands it.

**Note:** no chunking pipeline ships in Koog either — we chunk before `upsert()`.

## Skill distribution

Skills are folders. Distribution is just "get a folder onto disk under `~/.kermes/skills/` (or `<project>/.kermes/skills/`)."

**MVP CLI:**
```
kermes skill add <local-path>            # symlink or copy a local folder
kermes skill install <git-url> [--tag t] # git clone into skills dir
kermes skill list
kermes skill remove <name>
kermes skill update <name>               # git pull
```

**Discovery convention:** community skills publish to GitHub with the topic `kermes-skill` (or `agent-skill` for cross-tool reuse). No registry, no infra.

**Trust:** any installed skill can execute code via its `scripts/`. `install` from a git URL prints a summary ("3 scripts, 1 reference, 0 assets — review before running"). In MVP, the local sandbox + permissions tier is the safety net; in Phase 2, Docker contains blast radius.

**Phase 2:** if agentskills.io stabilizes a registry, resolve `kermes skill install <name>` against it before falling through to git URL.

## Permissions & approval

**Model:** Claude-Code-style tiered defaults with session memory and config-level allow/deny rules. Three approval scopes when a prompt fires: `allow once`, `allow this session`, `always allow` (persists to config).

**Default tiers:**

| Category | Default | Tools |
|---|---|---|
| Read-only | auto-allow | `read_file`, `list_dir`, `glob`, `grep`, `web_search`, `read_skill_file`, `load_skill`, `list_skills` |
| Write to user files | prompt | `write_file`, `edit_file`, `create_skill`, `update_skill` |
| Execute code | prompt | `bash`, `run_skill_script` |
| Network mutations | prompt | gateway sends (Phase 2: posting to Telegram etc.) |

**Config-level overrides:**
```yaml
permissions:
  bash:
    allow: ["git:*", "ls:*", "cat:*", "rg:*", "find:*"]
    deny:  ["rm -rf /*", "sudo:*", "curl * | sh", "curl * | bash"]
  write_file:
    allow: ["~/.kermes/**", "${cwd}/**"]
```

`deny` is hard — no override prompt. `allow` skips the prompt. Otherwise, prompt.

**Implementation:** a **custom Koog Feature** (`PermissionGuard`) implementing `AIAgentGraphFeature` + `AIAgentFunctionalFeature` + `AIAgentPlannerFeature`. Its install function registers a **pipeline interceptor** that fires before each tool call and can short-circuit (deny) — something `EventHandler` cannot do. The audit log is a separate `EventHandler` install hooking `onToolCallStarting/Completed/Failed`.

```kotlin
install(PermissionGuard) {
    tiers      = defaultTiers
    allowList  = config.permissions.allow
    denyList   = config.permissions.deny
    onPrompt   = { toolName, args -> tui.askApproval(toolName, args) }
}

install(EventHandler) {
    onToolCallStarting   { ctx -> audit.write(ctx, decision = "starting") }
    onToolCallCompleted  { ctx -> audit.write(ctx, decision = "completed") }
    onToolCallFailed     { ctx -> audit.write(ctx, decision = "failed") }
}
```

Two features, one for enforcement, one for audit — both idiomatic Koog.

**Phase 2 (Docker):** default policy flips to "allow all" inside the container. Same code path, executor-aware policy resolution within `PermissionGuard`.

**Escape hatch:** `--dangerously-skip-permissions` flag for power users / CI.

## Distribution & install

Goal: `curl -fsSL https://<host>/install.sh | bash`, like Hermes. Feasible today —
the only JVM-specific wrinkle is the runtime (Hermes ships Python + venv; we ship
a JVM app that needs a JRE).

**What we already have:** `kermes-app` uses Gradle's `application` plugin, so
`./gradlew :kermes-app:installDist` (and `distZip`/`distTar`) produce a ready
launcher — verified working:

```
build/install/kermes/
├── bin/kermes        # POSIX launch script (runs `java -cp lib/* ai.kermes.app.MainKt`)
├── bin/kermes.bat    # Windows
└── lib/*.jar         # all deps
```

`bin/kermes version` runs standalone against the system Java.

**Install paths (easiest → best UX):**

| Approach | JRE needed? | Effort | Notes |
|---|---|---|---|
| **distZip + `install.sh`** | yes (Java 17+) | low | `install.sh` checks Java, downloads the release zip, unpacks to `~/.kermes/app`, symlinks `bin/kermes` into PATH, runs `kermes init`. Mirrors Hermes' approach (it checks Python; we check Java). **Recommended for v1.** |
| **`jpackage` bundle** | no (JRE embedded) | medium | Per-OS bundle with a trimmed runtime. Bigger download, zero external deps. |
| **GraalVM native-image** | no (single binary) | high | Best UX (fast start, one file). Risk: reflection / kotlinx-serialization / Ktor native-image config. Defer until proven. |

**v1 `install.sh` sketch:**

```bash
#!/usr/bin/env bash
set -euo pipefail
command -v java >/dev/null || { echo "Java 17+ required"; exit 1; }
ver=$(java -version 2>&1 | head -1 | grep -oE '[0-9]+' | head -1)
[ "$ver" -ge 17 ] || { echo "Java 17+ required (found $ver)"; exit 1; }

dest="${KERMES_HOME:-$HOME/.kermes}/app"
url="https://github.com/<org>/kermes/releases/latest/download/kermes.zip"
mkdir -p "$dest"
curl -fsSL "$url" -o /tmp/kermes.zip && unzip -oq /tmp/kermes.zip -d "$dest"

mkdir -p "$HOME/.local/bin"
ln -sf "$dest/kermes/bin/kermes" "$HOME/.local/bin/kermes"
"$HOME/.local/bin/kermes" init
echo "Installed. Ensure ~/.local/bin is on PATH, then: export KERMES_API_KEY=… && kermes"
```

**Release flow:** tag → CI runs `./gradlew :kermes-app:distZip` → upload `kermes.zip`
as a GitHub release asset → host `install.sh` (GitHub raw, or a domain).

**The one honest caveat:** requiring Java 17+ is a real prerequisite Hermes
doesn't have (Python is more ubiquitous than a modern JDK). If that friction
matters, move to `jpackage` (bundled JRE) — then `curl | bash` is truly
zero-dependency.

## Open questions

- [ ] Naming: keep `Kermes` or revisit?
- [ ] License for the project itself?
- [ ] First gateway in Phase 2 — Telegram (assumed) or driven by early user demand?
