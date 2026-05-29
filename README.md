# Kermes

> # ⚠️ NOT READY FOR USE
>
> **Kermes is an experimental project under active development.** It is
> incomplete, unstable, and changes without notice. Expect breakage, missing
> features, and rough edges. Do **not** rely on it for anything real — use it
> only to explore or hack on. You have been warned.

A Kotlin/JVM AI agent inspired by [Hermes Agent](https://github.com/NousResearch/hermes-agent), built on [Koog](https://github.com/JetBrains/koog).

Skill-aware, self-improving, provider-agnostic, and runs anywhere the JVM runs.
See [KERMES.md](KERMES.md) for the full design, scope, and roadmap.

## Status

**0.1 — experimental MVP, not production-ready.** Boots end-to-end against Koog 1.0. Loads agentskills.io
skills, remembers you across sessions (and learns automatically at session end),
gates dangerous tools behind approval, reads/writes files, searches the web, and
runs scheduled tasks. The one deferral is TUI token-streaming (output is returned
per-turn, not token-by-token).

## Install

```bash
curl -fsSL https://raw.githubusercontent.com/moshtaghmaveddat/kermes-agent/main/install.sh | bash
```

No prerequisites beyond `curl` + `tar`/`unzip` — the installer **auto-provisions
a Java 17+ runtime** (Eclipse Temurin) if you don't already have one, downloads
the latest release into `~/.kermes/app`, and writes a `kermes` launcher into
`~/.local/bin`. Then:

```bash
kermes setup     # configure provider + API key (+ optional Telegram)
kermes           # start chatting
# (or skip the wizard: export KERMES_API_KEY=sk-... )
```

## Commands

```
kermes                  start the interactive REPL
kermes -q "<prompt>"    one-shot: run a single prompt and print the reply
kermes setup            interactive setup (provider, API key, Telegram)
kermes init             bootstrap ~/.kermes (dirs, sample skill, schedules)
kermes status           show config + health (no network)
kermes update           update to the latest release (re-runs the installer)
kermes version          print version
kermes help             usage

# in-session slash commands
/help /skills /memory /inbox /new /model /yolo /quit
```

## Build from source

The Gradle wrapper is committed, so:

```bash
./gradlew build                 # compile + test
./gradlew :kermes-app:run       # run the REPL (needs KERMES_API_KEY)
./gradlew :kermes-app:installDist   # produce build/install/kermes/bin/kermes
```

Config via env vars:

| Var | Default | Notes |
|---|---|---|
| `KERMES_API_KEY` | — | required (or `OPENROUTER_API_KEY` / `OPENAI_API_KEY`) |
| `KERMES_BASE_URL` | `https://openrouter.ai/api/v1` | any OpenAI-compatible endpoint |
| `KERMES_MODEL` | `openai/gpt-4o` | |
| `KERMES_EMBEDDINGS_MODEL` | `openai/text-embedding-3-small` | |
| `KERMES_DANGEROUSLY_SKIP_PERMISSIONS` | `false` | bypass approval prompts (also `/yolo`) |

## Architecture in one paragraph

A Koog **basic agent** with five annotation-based tool sets — `SkillsToolSet`,
`LearnerToolSet` (six memory perspectives), `BashToolSet`, `WebSearchToolSet`,
and `FileToolSet` (Koog 1.0 ships no usable built-in file tools, so we wrote our
own) — plus Koog Features: `ChatMemory` (per-session buffer), `Persistence`
(checkpoint/resume), and `EventHandler` (tool-call audit log). Permission
enforcement is a **tool-boundary** gate (`PermissionGuard`): Koog 1.0's tool-call
interceptor is observe-only, so gating lives in the tools and returns a "denied"
result to the model instead of aborting the run. The skill manifest and eager
memory (identity + preferences) are injected **statically** into the system
prompt at build (correct at MVP scale; per-turn top-K retrieval is a later
upgrade). Self-learning runs at **session end**: `SessionLearner` hands the
transcript to a tool-less extractor agent that emits durable facts in a
provider-agnostic line format, routed to the six perspectives. The vector store
is a small file-backed `KoogVectorStore` (Koog's `LLMEmbedder` +
`Vector.cosineSimilarity`), since Koog 1.0 ships only storage interfaces.

## Skills

Drop a folder containing a `SKILL.md` ([agentskills.io](https://agentskills.io)
format) into one of, in priority order:

- `<project>/.kermes/skills/` — project-local, highest priority
- `~/.kermes/skills/` — user
- bundled in this repo at `skills/`

The agent discovers it on next start. It can also write its own skills (via the
`create_skill` tool) under `~/.kermes/skills/agent-created/`.

## Layout

```
kermes/
├── KERMES.md                  design + roadmap
├── install.sh                 curl | bash installer
├── .github/workflows/         CI: tag → distZip → GitHub release
├── kermes-core/               skill engine, memory, vector store, PermissionGuard
├── kermes-tools/              bash + web_search + file tools
├── kermes-schedule/           cron + inbox writer
├── kermes-tui/                (reserved; REPL currently lives in kermes-app)
├── kermes-app/                main(), CLI, Koog wiring, SessionLearner
└── skills/example/            bundled demonstration skill
```

## Releasing

Push a tag to publish a release `kermes.zip` (the installer downloads `latest`):

```bash
git tag v0.1.0 && git push origin v0.1.0
```

The `release` workflow runs tests, builds `:kermes-app:distZip`, and uploads the
zip as a release asset. Manual runs are available via the Actions tab.

## License

[MIT](LICENSE) © 2026 Moshtagh Maveddat
