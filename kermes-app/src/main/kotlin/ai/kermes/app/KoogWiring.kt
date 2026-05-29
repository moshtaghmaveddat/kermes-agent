package ai.kermes.app

import ai.kermes.core.vector.KoogVectorStore
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLModel
import java.nio.file.Path

/**
 * Concrete wiring of Koog primitives. All Koog imports live here so the rest
 * of the app talks only to Kermes-facing types.
 */
object KoogWiring {

    /** Which provider family a base URL points at — decides client + API dialect. */
    private enum class Provider { OPENAI, OLLAMA, CHAT_COMPLETIONS }

    /**
     * Infer the provider from the base URL. We key off the URL (rather than a
     * stored provider name) so existing configs keep working: Ollama always
     * listens on :11434, OpenAI on api.openai.com, everything else is treated
     * as an OpenAI-compatible Chat-Completions server (OpenRouter, vLLM, …).
     */
    private fun providerOf(baseUrl: String): Provider {
        val u = baseUrl.lowercase()
        return when {
            "api.openai.com" in u -> Provider.OPENAI
            "11434" in u || "ollama" in u -> Provider.OLLAMA
            else -> Provider.CHAT_COMPLETIONS
        }
    }

    /**
     * Ollama's native client wants the bare host (it appends `/api/chat`,
     * `/api/tags`, …) — NOT the `/v1` OpenAI-compat shim. Strip the trailing
     * version segment that the rest of the app stores in its base URL.
     */
    private fun ollamaBase(baseUrl: String): String =
        baseUrl.trimEnd('/').removeSuffix("/v1").removeSuffix("/api")
            .ifBlank { "http://localhost:11434" }

    /**
     * Build a provider-appropriate client from the base URL.
     *
     * - **Ollama** → the native `OllamaClient` (correct API, correct name in
     *   logs/errors, and a path to local embeddings later). It speaks Ollama's
     *   native protocol, not the OpenAI shim.
     * - **OpenAI** → `OpenAILLMClient`, which speaks the OpenAI **Responses
     *   API** (`/responses`) that only api.openai.com implements.
     * - **Everything else** → `OpenRouterLLMClient`, a **Chat Completions**
     *   (`/chat/completions`) client, with the base URL overridden. This covers
     *   OpenRouter and any OpenAI-compatible server.
     */
    private fun buildClient(apiKey: String, baseUrl: String): LLMClient =
        when (providerOf(baseUrl)) {
            Provider.OPENAI ->
                OpenAILLMClient(apiKey, OpenAIClientSettings(baseUrl = baseUrl))
            Provider.OLLAMA ->
                OllamaClient(baseUrl = ollamaBase(baseUrl))
            Provider.CHAT_COMPLETIONS ->
                // The base URL already includes the version segment (…/v1 or …/api/v1),
                // so paths are single-segment relative. (OpenRouter's defaults hardcode
                // `api/v1/…`, which would double up against our base → 404.)
                OpenRouterLLMClient(
                    apiKey.ifBlank { "none" },
                    OpenRouterClientSettings(
                        baseUrl = baseUrl,
                        chatCompletionsPath = "chat/completions",
                        modelsPath = "models",
                        embeddingsPath = "embeddings",
                    ),
                )
        }

    /** Prompt executor: provider client wrapped in retry. */
    fun buildPromptExecutor(apiKey: String, baseUrl: String): PromptExecutor {
        val client = RetryingLLMClient(buildClient(apiKey, baseUrl), RetryConfig.PRODUCTION)
        return MultiLLMPromptExecutor(client)
    }

    /**
     * Build the chat model. The provider is taken from the client so it matches
     * the executor's routing key (rather than guessing); capabilities are
     * borrowed from a known chat model so tool-calling etc. are advertised.
     */
    fun resolveModel(apiKey: String, baseUrl: String, id: String): LLModel {
        val provider = buildClient(apiKey, baseUrl).llmProvider()
        return LLModel(provider, id, OpenAIModels.Chat.GPT4o.capabilities)
    }

    /**
     * File-backed vector store, embedding via the same provider client.
     * Default embeddings model is OpenAI's text-embedding-3-small (works on
     * OpenAI + OpenRouter). Local Ollama embeddings need a local embed model —
     * a separate concern from chat.
     */
    fun buildVectorStore(
        apiKey: String,
        baseUrl: String,
        storageRoot: Path,
        embeddingsModel: LLModel = OpenAIModels.Embeddings.TextEmbedding3Small,
    ): KoogVectorStore {
        val embedder = LLMEmbedder(buildClient(apiKey, baseUrl), embeddingsModel)
        return KoogVectorStore(embedder, storageRoot)
    }
}
