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
import ai.koog.prompt.llm.LLModel
import java.nio.file.Path

/**
 * Concrete wiring of Koog primitives. All Koog imports live here so the rest
 * of the app talks only to Kermes-facing types.
 */
object KoogWiring {

    /**
     * Build a provider-appropriate client from the base URL.
     *
     * Koog's `OpenAILLMClient` speaks the OpenAI **Responses API** (`/responses`),
     * which only OpenAI implements. OpenRouter, Ollama, and most "OpenAI-
     * compatible" servers only speak **Chat Completions** (`/chat/completions`) —
     * so for everything except api.openai.com we use `OpenRouterLLMClient`
     * (a chat-completions client) with the base URL overridden.
     */
    private fun buildClient(apiKey: String, baseUrl: String): LLMClient =
        if (baseUrl.contains("api.openai.com")) {
            OpenAILLMClient(apiKey, OpenAIClientSettings(baseUrl = baseUrl))
        } else {
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
