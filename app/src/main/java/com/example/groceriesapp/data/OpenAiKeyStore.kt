package com.example.groceriesapp.data

import android.content.Context
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Holds the user-supplied OpenAI API key, encrypted at rest. */
class OpenAiKeyStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "openai_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun apiKey(): String? = encryptedPrefs.getString(KEY_API_KEY, null)?.takeIf(String::isNotBlank)

    fun setApiKey(value: String?) {
        encryptedPrefs.edit {
            val trimmed = value?.trim()
            if (trimmed.isNullOrEmpty()) {
                remove(KEY_API_KEY)
            } else {
                putString(KEY_API_KEY, trimmed)
            }
        }
    }

    fun hasKey(): Boolean = apiKey() != null

    /** The OpenAI API base URL (no trailing slash, no path suffix). Defaults to the EU region. */
    fun apiBase(): String =
        encryptedPrefs.getString(KEY_API_BASE, null)?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank)
            ?: DEFAULT_API_BASE

    fun setApiBase(value: String?) {
        encryptedPrefs.edit {
            val trimmed = value?.trim()?.trimEnd('/')
            if (trimmed.isNullOrEmpty() || trimmed == DEFAULT_API_BASE) {
                remove(KEY_API_BASE)
            } else {
                putString(KEY_API_BASE, trimmed)
            }
        }
    }

    /** Model used for receipt item extraction and recipe suggestions (Chat Completions). */
    fun textModel(): String =
        encryptedPrefs.getString(KEY_TEXT_MODEL, null)?.trim()?.takeIf(String::isNotBlank)
            ?: DEFAULT_TEXT_MODEL

    fun setTextModel(value: String?) {
        encryptedPrefs.edit {
            val trimmed = value?.trim()
            if (trimmed.isNullOrEmpty() || trimmed == DEFAULT_TEXT_MODEL) {
                remove(KEY_TEXT_MODEL)
            } else {
                putString(KEY_TEXT_MODEL, trimmed)
            }
        }
    }

    /** Model used for recipe photo generation (Images API). */
    fun imageModel(): String =
        encryptedPrefs.getString(KEY_IMAGE_MODEL, null)?.trim()?.takeIf(String::isNotBlank)
            ?: DEFAULT_IMAGE_MODEL

    fun setImageModel(value: String?) {
        encryptedPrefs.edit {
            val trimmed = value?.trim()
            if (trimmed.isNullOrEmpty() || trimmed == DEFAULT_IMAGE_MODEL) {
                remove(KEY_IMAGE_MODEL)
            } else {
                putString(KEY_IMAGE_MODEL, trimmed)
            }
        }
    }

    companion object {
        /** OpenAI's EU data-residency endpoint. Default so receipt/recipe data stays in-region. */
        const val EU_API_BASE = "https://eu.api.openai.com/v1"
        const val US_API_BASE = "https://api.openai.com/v1"
        const val DEFAULT_API_BASE = EU_API_BASE

        const val DEFAULT_TEXT_MODEL = "gpt-5.6-luna"
        const val DEFAULT_IMAGE_MODEL = "gpt-image-1"

        private const val KEY_API_KEY = "api_key"
        private const val KEY_API_BASE = "api_base"
        private const val KEY_TEXT_MODEL = "text_model"
        private const val KEY_IMAGE_MODEL = "image_model"
    }
}
