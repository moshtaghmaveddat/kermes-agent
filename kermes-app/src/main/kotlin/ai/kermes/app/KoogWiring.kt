package ai.kermes.app

import ai.kermes.core.vector.KoogVectorStore
import ai.koog.embeddings.local.LLMEmbedder
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
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

    /** Build the prompt executor: OpenAI-compatible client + retry wrapper. */
    fun buildPromptExecutor(apiKey: String, baseUrl: String): PromptExecutor {
        val rawClient = OpenAILLMClient(
            apiKey = apiKey,
            settings = OpenAIClientSettings(baseUrl = baseUrl),
        )
        val retrying = RetryingLLMClient(rawClient, RetryConfig.PRODUCTION)
        return MultiLLMPromptExecutor(retrying)
    }

    /**
     * Build the file-backed vector store, using an [LLMEmbedder] over an
     * OpenAI-compatible client. Embeddings model is overridable but defaults
     * to OpenAI's text-embedding-3-small (cheap, broadly available).
     */
    fun buildVectorStore(
        apiKey: String,
        baseUrl: String,
        storageRoot: Path,
        embeddingsModel: LLModel = OpenAIModels.Embeddings.TextEmbedding3Small,
    ): KoogVectorStore {
        val embeddingClient = OpenAILLMClient(
            apiKey = apiKey,
            settings = OpenAIClientSettings(baseUrl = baseUrl),
        )
        val embedder = LLMEmbedder(embeddingClient, embeddingsModel)
        return KoogVectorStore(embedder, storageRoot)
    }

    /** Resolve a model ID string to a Koog [LLModel]. Falls back to GPT-4o. */
    fun resolveModel(id: String): LLModel = when (id.lowercase()) {
        "gpt-4o", "openai/gpt-4o" -> OpenAIModels.Chat.GPT4o
        "gpt-4o-mini", "openai/gpt-4o-mini" -> OpenAIModels.Chat.GPT4oMini
        "gpt-4.1", "openai/gpt-4.1" -> OpenAIModels.Chat.GPT4_1
        "gpt-5", "openai/gpt-5" -> OpenAIModels.Chat.GPT5
        "gpt-5-mini", "openai/gpt-5-mini" -> OpenAIModels.Chat.GPT5Mini
        else -> OpenAIModels.Chat.GPT4o
    }
}
