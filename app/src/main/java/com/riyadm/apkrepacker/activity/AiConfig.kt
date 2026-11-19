package com.riyadm.apkrepacker.activity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal object AiConfig {
    private const val PREF = "meghraj_ai_config"
    private const val KEY_ALIAS = "meghraj_ai_openrouter_key_v1"
    private const val KEY = "api_key"
    private const val MODEL = "model_id"
    private const val PROVIDER = "provider"
    private const val BASE_URL = "base_url"

    private fun secretKey(): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.getKey(KEY_ALIAS, null)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    fun save(context: Context, apiKey: String, model: String, provider: String = getProvider(context), baseUrl: String = getBaseUrl(context)) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(KEY, Base64.encodeToString(packed, Base64.NO_WRAP))
            .putString(MODEL, model)
            .putString(PROVIDER, provider)
            .putString(BASE_URL, baseUrl.trim().trimEnd('/'))
            .apply()
    }

    fun getKey(context: Context): String {
        val packed = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return ""
        return try {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { "" }
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

    fun defaultBaseUrl(provider: String): String = when (provider) {
        "OpenAI" -> "https://api.openai.com/v1"
        "DeepSeek" -> "https://api.deepseek.com"
        "NVIDIA NIM" -> "https://integrate.api.nvidia.com/v1"
        "OpenRouter" -> "https://openrouter.ai/api/v1"
        "AgentRouter" -> "https://agentrouter.org/v1"
        "Custom" -> "https://example.com/v1"
        else -> "https://openrouter.ai/api/v1"
    }
}
