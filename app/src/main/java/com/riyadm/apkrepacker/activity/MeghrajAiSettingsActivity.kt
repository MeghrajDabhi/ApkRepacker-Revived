package com.riyadm.apkrepacker.activity

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MeghrajAiSettingsActivity : BaseActivity() {
    private lateinit var provider: Spinner
    private lateinit var key: TextInputEditText
    private lateinit var baseUrl: TextInputEditText
    private lateinit var models: Spinner
    private lateinit var status: TextView
    private val modelIds = mutableListOf<String>()
    private val providers = listOf("OpenRouter", "AgentRouter", "Gemini", "DeepSeek", "OpenAI", "Anthropic", "Groq", "Mistral", "Together AI", "Fireworks AI", "Cerebras", "NVIDIA NIM", "Custom")
    private var lastAutoBase = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this@MeghrajAiSettingsActivity, com.google.android.material.R.attr.colorSurface, 0))
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Agent settings"
            setNavigationIcon(com.riyadm.apkrepacker.R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(28))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "Connect your AI provider"
            textSize = 23f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this@MeghrajAiSettingsActivity, com.google.android.material.R.attr.colorOnSurface, 0))
        })
        content.addView(TextView(this).apply {
            text = "Choose a provider, add its API key, fetch available models and save your selection."
            textSize = 14f
            setPadding(0, dp(6), 0, dp(16))
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this@MeghrajAiSettingsActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
        })

        val providerCard = card()
        val providerBox = LinearLayout(this).apply { orientation = 1; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        providerBox.addView(sectionTitle("Provider"))
        provider = Spinner(this)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        providerBox.addView(provider)
        providerCard.addView(providerBox)
        content.addView(providerCard, cardParams())

        val credentialsCard = card()
        val credentialsBox = LinearLayout(this).apply { orientation = 1; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        credentialsBox.addView(sectionTitle("Credentials"))
        key = field("API key", true).also { it.setText(AiConfig.getKey(this)) }
        credentialsBox.addView(key.parent as View)
        baseUrl = field("API base URL", false).also { it.setText(AiConfig.getBaseUrl(this)) }
        credentialsBox.addView(baseUrl.parent as View)
        credentialsCard.addView(credentialsBox)
        content.addView(credentialsCard, cardParams())

        val modelCard = card()
        val modelBox = LinearLayout(this).apply { orientation = 1; setPadding(dp(16), dp(14), dp(16), dp(14)) }
        modelBox.addView(sectionTitle("Model"))
        models = Spinner(this)
        modelBox.addView(models)
        modelCard.addView(modelBox)
        content.addView(modelCard, cardParams())

        status = TextView(this).apply {
            text = "Select a provider and fetch its models."
            textSize = 13f
            setPadding(dp(4), dp(12), dp(4), dp(12))
        }
        content.addView(status)
        content.addView(MaterialButton(this).apply {
            text = "Fetch models"
            setOnClickListener { fetchModels() }
        }, LinearLayout.LayoutParams(-1, dp(52)))
        content.addView(MaterialButton(this).apply {
            text = "Save provider settings"
            setIconResource(com.riyadm.apkrepacker.R.drawable.ic_meghraj_ai)
            setOnClickListener {
                val selected = models.selectedItemPosition.takeIf { it in modelIds.indices }?.let { modelIds[it] }
                    ?: AiConfig.getModel(this@MeghrajAiSettingsActivity)
                AiConfig.save(this@MeghrajAiSettingsActivity, key.text?.toString().orEmpty().trim(), selected,
                    providers[provider.selectedItemPosition], baseUrl.text?.toString().orEmpty().trim())
                status.text = "Saved. Provider: ${providers[provider.selectedItemPosition]} · Model: $selected"
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))

        val savedProvider = AiConfig.getProvider(this)
        provider.setSelection(providers.indexOf(savedProvider).coerceAtLeast(0))
        lastAutoBase = AiConfig.defaultBaseUrl(savedProvider)
        provider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val current = baseUrl.text?.toString()?.trim().orEmpty()
                if (current.isBlank() || current == lastAutoBase) {
                    val next = AiConfig.defaultBaseUrl(providers[position])
                    baseUrl.setText(next)
                    lastAutoBase = next
                }
            }
        }
        setContentView(page)
        val savedModel = AiConfig.getModel(this)
        if (savedModel.isNotBlank()) {
            modelIds.add(savedModel)
            models.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelIds)
        }
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = dp(1).toFloat()
        strokeWidth = dp(1)
        setCardBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this@MeghrajAiSettingsActivity, com.google.android.material.R.attr.colorSurfaceContainerLow, com.google.android.material.color.MaterialColors.getColor(this@MeghrajAiSettingsActivity, com.google.android.material.R.attr.colorSurface, 0)))
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    private fun sectionTitle(value: String) = TextView(this).apply { text = value; textSize = 15f; setPadding(0, 0, 0, dp(8)) }

    private fun field(label: String, secret: Boolean): TextInputEditText {
        val wrap = TextInputLayout(this).apply {
            hint = label
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = if (secret) TextInputLayout.END_ICON_PASSWORD_TOGGLE else TextInputLayout.END_ICON_CLEAR_TEXT
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
        }
        val input = TextInputEditText(wrap.context).apply {
            if (secret) inputType = 129
            if (!secret) maxLines = 1
        }
        wrap.addView(input, ViewGroup.LayoutParams(-1, -2))
        input.tag = wrap
        return input
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fetchModels() {
        val token = key.text?.toString().orEmpty().trim()
        val selectedProvider = providers[provider.selectedItemPosition]
        val base = baseUrl.text?.toString().orEmpty().trim().trimEnd('/')
        if (token.isBlank()) { status.text = "Enter the API key for $selectedProvider."; return }
        if (base.isBlank() || !base.startsWith("https://")) { status.text = "Enter a valid HTTPS API base URL."; return }
        status.text = "Fetching models from $selectedProvider…"
        lifecycleScope.launch {
            try {
                val ids = withContext(Dispatchers.IO) {
                    val endpoint = if (selectedProvider == "Gemini") "$base/v1beta/models?key=$token" else "$base/models"
                    val conn = URL(endpoint).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 30000
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.setRequestProperty("Accept", "application/json")
                    if (selectedProvider == "OpenRouter") conn.setRequestProperty("HTTP-Referer", "https://github.com/MeghrajDabhi/ApkRepacker-Revived")
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    conn.disconnect()
                    if (code !in 200..299) error("HTTP $code from $endpoint: ${body.take(400)}")
                    val root = JSONObject(body)
                    val found = if (selectedProvider == "Gemini") {
                        val data = root.optJSONArray("models") ?: error("Gemini response has no models list.")
                        (0 until data.length()).mapNotNull { i ->
                            val item = data.optJSONObject(i) ?: return@mapNotNull null
                            val methods = item.optJSONArray("supportedGenerationMethods")
                            val supportsChat = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                            item.optString("name").removePrefix("models/").takeIf { it.isNotBlank() && supportsChat }
                        }
                    } else {
                        val data = root.optJSONArray("data") ?: error("Response has no OpenAI-compatible data[] model list.")
                        (0 until data.length()).mapNotNull { i -> data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() } }
                    }
                    found.distinct().sorted()
                }
                if (ids.isEmpty()) error("No models were returned for this key.")
                modelIds.clear()
                modelIds.addAll(ids)
                models.adapter = ArrayAdapter(this@MeghrajAiSettingsActivity, android.R.layout.simple_spinner_dropdown_item, modelIds)
                val saved = AiConfig.getModel(this@MeghrajAiSettingsActivity)
                models.setSelection(modelIds.indexOf(saved).coerceAtLeast(0))
                status.text = "Fetched ${ids.size} models. Select one and save."
            } catch (e: Exception) {
                status.text = "Model fetch failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
}
