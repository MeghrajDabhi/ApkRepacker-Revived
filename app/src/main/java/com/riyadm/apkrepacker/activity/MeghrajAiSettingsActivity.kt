package com.riyadm.apkrepacker.activity

import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MeghrajAiSettingsActivity : BaseActivity() {
    private lateinit var provider: Spinner
    private lateinit var key: EditText
    private lateinit var baseUrl: EditText
    private lateinit var models: Spinner
    private lateinit var status: TextView
    private val modelIds = mutableListOf<String>()
    private val providers = listOf("OpenRouter", "OpenAI", "DeepSeek", "NVIDIA NIM", "Custom")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = 1; setPadding(22, 22, 22, 22) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = 1 }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        content.addView(TextView(this).apply { text = "Meghraj AI Settings"; textSize = 24f })
        content.addView(TextView(this).apply {
            text = "Choose an OpenAI-compatible API provider. API keys are encrypted with Android Keystore. Provider API terms and model availability still apply."
            setPadding(0, 12, 0, 16)
        })
        content.addView(TextView(this).apply { text = "API provider"; textSize = 14f })
        provider = Spinner(this)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        content.addView(provider)
        key = EditText(this).apply { hint = "Provider API key"; inputType = 129; setText(AiConfig.getKey(this@MeghrajAiSettingsActivity)) }
        content.addView(key, ViewGroup.LayoutParams(-1, -2))
        baseUrl = EditText(this).apply { hint = "API base URL"; setText(AiConfig.getBaseUrl(this@MeghrajAiSettingsActivity)) }
        content.addView(baseUrl, ViewGroup.LayoutParams(-1, -2))
        provider.setSelection(providers.indexOf(AiConfig.getProvider(this)).coerceAtLeast(0))
        provider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selected = providers[position]
                val oldDefault = AiConfig.defaultBaseUrl(AiConfig.getProvider(this@MeghrajAiSettingsActivity))
                if (baseUrl.text.toString().trim().isBlank() || baseUrl.text.toString().trim() == oldDefault) {
                    baseUrl.setText(AiConfig.defaultBaseUrl(selected))
                }
            }
        }
        content.addView(TextView(this).apply { text = "Model"; textSize = 14f; setPadding(0, 10, 0, 0) })
        models = Spinner(this)
        content.addView(models)
        status = TextView(this).apply { text = "Select a provider, enter its key, then fetch models."; setPadding(0, 12, 0, 12) }
        content.addView(status)
        content.addView(Button(this).apply { text = "Fetch models"; setOnClickListener { fetchModels() } })
        content.addView(Button(this).apply {
            text = "Save AI settings"
            setOnClickListener {
                val selected = models.selectedItemPosition.takeIf { it in modelIds.indices }?.let { modelIds[it] }
                    ?: AiConfig.getModel(this@MeghrajAiSettingsActivity)
                AiConfig.save(this@MeghrajAiSettingsActivity, key.text.toString().trim(), selected,
                    providers[provider.selectedItemPosition], baseUrl.text.toString().trim())
                status.text = "Saved " + providers[provider.selectedItemPosition] + " settings. Model: " + selected
            }
        })
        setContentView(root)
        val saved = AiConfig.getModel(this)
        if (saved.isNotBlank()) {
            modelIds.add(saved)
            models.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelIds)
        }
    }

    private fun fetchModels() {
        val token = key.text.toString().trim()
        val selectedProvider = providers[provider.selectedItemPosition]
        val base = baseUrl.text.toString().trim().trimEnd('/')
        if (token.isBlank()) { status.text = "Enter the API key for " + selectedProvider + "."; return }
        if (base.isBlank() || !base.startsWith("https://")) { status.text = "Enter a valid HTTPS API base URL."; return }
        status.text = "Fetching models from " + selectedProvider + "…"
        lifecycleScope.launch {
            try {
                val ids = withContext(Dispatchers.IO) {
                    val endpoint = when (selectedProvider) {
                        "OpenRouter" -> "$base/models"
                        else -> "$base/models"
                    }
                    val c = URL(endpoint).openConnection() as HttpURLConnection
                    c.requestMethod = "GET"; c.connectTimeout = 15000; c.readTimeout = 30000
                    c.setRequestProperty("Authorization", "Bearer " + token)
                    c.setRequestProperty("Accept", "application/json")
                    val code = c.responseCode
                    val body = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
                    c.disconnect()
                    if (code !in 200..299) error(selectedProvider + " model API returned HTTP " + code + ": " + body.take(350))
                    val data = JSONObject(body).optJSONArray("data") ?: error("Provider response has no 'data' model array.")
                    (0 until data.length()).mapNotNull { i ->
                        val o = data.optJSONObject(i) ?: return@mapNotNull null
                        o.optString("id").takeIf { it.isNotBlank() }
                    }.distinct().sorted()
                }
                if (ids.isEmpty()) error("No models were returned for this API key.")
                modelIds.clear(); modelIds.addAll(ids)
                models.adapter = ArrayAdapter(this@MeghrajAiSettingsActivity, android.R.layout.simple_spinner_dropdown_item, modelIds)
                val saved = AiConfig.getModel(this@MeghrajAiSettingsActivity)
                val index = modelIds.indexOf(saved)
                if (index >= 0) models.setSelection(index)
                status.text = "Fetched " + ids.size + " models from " + selectedProvider + ". Select one and save."
            } catch (e: Exception) { status.text = "Model fetch failed: " + (e.message ?: "unknown error") }
        }
    }
}
