package com.riyadm.apkrepacker.activity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal data class AiProfile(
    val id: String,
    val name: String,
    val provider: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String
)

internal object AiConfig {
    private const val PREF = "meghraj_ai_config"
    private const val KEY_ALIAS = "meghraj_ai_openrouter_key_v1"
    private const val KEY = "api_key"
    private const val MODEL = "model_id"
    private const val PROVIDER = "provider"
    private const val BASE_URL = "base_url"
    private const val PROFILES = "saved_api_profiles_v1"
    private const val ACTIVE_PROFILE = "active_api_profile_id"

    private fun secretKey(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEY_ALIAS, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12) { "Saved API key data is incomplete." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    fun save(
        context: Context,
        apiKey: String,
        model: String,
        provider: String = getProvider(context),
        baseUrl: String = getBaseUrl(context)
    ) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, encrypt(apiKey))
            .putString(MODEL, model)
            .putString(PROVIDER, provider)
            .putString(BASE_URL, baseUrl.trim().trimEnd('/'))
            .apply()
    }

    fun getKey(context: Context): String {
        val encrypted = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return ""
        return runCatching { decrypt(encrypted) }.getOrDefault("")
    }

    fun getModel(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(MODEL, "openrouter/auto") ?: "openrouter/auto"

    fun getProvider(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PROVIDER, "OpenRouter") ?: "OpenRouter"

    fun getBaseUrl(context: Context): String {
        val saved = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(BASE_URL, null)
        if (!saved.isNullOrBlank()) return saved
        return defaultBaseUrl(getProvider(context))
    }

    fun getActiveProfileId(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(ACTIVE_PROFILE, "").orEmpty()

    fun getProfiles(context: Context): List<AiProfile> {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val encryptedKey = item.optString("apiKey", "")
                val plainKey = runCatching { decrypt(encryptedKey) }.getOrDefault("")
                AiProfile(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    provider = item.optString("provider"),
                    baseUrl = item.optString("baseUrl"),
                    model = item.optString("model"),
                    apiKey = plainKey
                ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    fun saveApiProfile(
        context: Context,
        name: String,
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        profileId: String? = null
    ): String {
        require(name.isNotBlank()) { "Enter a name for this saved API profile." }
        require(apiKey.isNotBlank()) { "Enter an API key before saving the profile." }
        require(baseUrl.startsWith("https://", ignoreCase = true)) { "The API base URL must use HTTPS." }
        require(model.isNotBlank()) { "Enter or select a model ID." }

        val preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val existing = JSONArray(preferences.getString(PROFILES, "[]") ?: "[]")
        val id = profileId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val saved = JSONArray()
        var replaced = false
        for (i in 0 until existing.length()) {
            val item = existing.optJSONObject(i) ?: continue
            if (item.optString("id") == id) {
                saved.put(profileJson(id, name, provider, baseUrl, model, apiKey))
                replaced = true
            } else {
                saved.put(item)
            }
        }
        if (!replaced) saved.put(profileJson(id, name, provider, baseUrl, model, apiKey))
        preferences.edit()
            .putString(PROFILES, saved.toString())
            .putString(ACTIVE_PROFILE, id)
            .apply()
        save(context, apiKey, model, provider, baseUrl)
        return id
    }

    private fun profileJson(
        id: String,
        name: String,
        provider: String,
        baseUrl: String,
        model: String,
        apiKey: String
    ) = JSONObject()
        .put("id", id)
        .put("name", name.trim())
        .put("provider", provider)
        .put("baseUrl", baseUrl.trim().trimEnd('/'))
        .put("model", model.trim())
        .put("apiKey", encrypt(apiKey))

    fun activateProfile(context: Context, profileId: String): AiProfile? {
        val profile = getProfiles(context).firstOrNull { it.id == profileId } ?: return null
        if (profile.apiKey.isBlank()) return null
        save(context, profile.apiKey, profile.model, profile.provider, profile.baseUrl)
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(ACTIVE_PROFILE, profile.id)
            .apply()
        return profile
    }

    fun deleteProfile(context: Context, profileId: String) {
        val preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val current = JSONArray(preferences.getString(PROFILES, "[]") ?: "[]")
        val updated = JSONArray()
        for (i in 0 until current.length()) {
            val item = current.optJSONObject(i) ?: continue
            if (item.optString("id") != profileId) updated.put(item)
        }
        val editor = preferences.edit().putString(PROFILES, updated.toString())
        if (getActiveProfileId(context) == profileId) editor.remove(ACTIVE_PROFILE)
        editor.apply()
    }

    fun defaultBaseUrl(provider: String): String = when (provider) {
        "OpenAI" -> "https://api.openai.com/v1"
        "DeepSeek" -> "https://api.deepseek.com"
        "NVIDIA NIM" -> "https://integrate.api.nvidia.com/v1"
        "OpenRouter" -> "https://openrouter.ai/api/v1"
        "Anthropic" -> "https://api.anthropic.com/v1"
        "Groq" -> "https://api.groq.com/openai/v1"
        "Mistral" -> "https://api.mistral.ai/v1"
        "Together AI" -> "https://api.together.xyz/v1"
        "Fireworks AI" -> "https://api.fireworks.ai/inference/v1"
        "Cerebras" -> "https://api.cerebras.ai/v1"
        "AgentRouter" -> "https://agentrouter.org/v1"
        "Gemini" -> "https://generativelanguage.googleapis.com"
        "Custom" -> "https://example.com/v1"
        else -> "https://openrouter.ai/api/v1"
    }
}
