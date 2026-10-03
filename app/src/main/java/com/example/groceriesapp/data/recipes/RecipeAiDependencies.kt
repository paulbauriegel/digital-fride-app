package com.example.groceriesapp.data.recipes

import android.content.Context
import com.example.groceriesapp.data.OpenAiKeyStore
import com.example.groceriesapp.data.network.OpenAiChatClient
import com.example.groceriesapp.data.network.OpenAiImageClient
import com.example.groceriesapp.data.network.RecipeUrlClient

/**
 * The network-facing collaborators [RecipesRepository] needs for its AI and link-import features,
 * bundled so the repository itself can be built from a plain [java.io.File] root under test.
 * Everything is lazy: the key store opens encrypted preferences, which has no business running
 * at app start just to construct the repository.
 */
class RecipeAiDependencies(
    keyStoreFactory: () -> OpenAiKeyStore,
    chatClientFactory: (OpenAiKeyStore) -> OpenAiChatClient,
    imageClientFactory: (OpenAiKeyStore) -> OpenAiImageClient,
    urlClientFactory: () -> RecipeUrlClient,
) {
    val keyStore: OpenAiKeyStore by lazy(keyStoreFactory)
    val chatClient: OpenAiChatClient by lazy { chatClientFactory(keyStore) }
    val imageClient: OpenAiImageClient by lazy { imageClientFactory(keyStore) }
    val urlClient: RecipeUrlClient by lazy(urlClientFactory)

    /** The stored API key, or null when none is configured or the store cannot be read. */
    fun apiKeyOrNull(): String? = runCatching { keyStore.apiKey() }.getOrNull()

    companion object {
        fun fromContext(context: Context): RecipeAiDependencies {
            val appContext = context.applicationContext
            return RecipeAiDependencies(
                keyStoreFactory = { OpenAiKeyStore(appContext) },
                chatClientFactory = { OpenAiChatClient(endpoint = "${it.apiBase()}/chat/completions") },
                imageClientFactory = { OpenAiImageClient(endpoint = "${it.apiBase()}/images/generations") },
                urlClientFactory = { RecipeUrlClient() },
            )
        }
    }
}
