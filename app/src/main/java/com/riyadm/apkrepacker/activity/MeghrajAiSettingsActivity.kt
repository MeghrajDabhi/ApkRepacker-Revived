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
    private lateinit var key: EditText
    private lateinit var models: Spinner
    private lateinit var status: TextView
    private val modelIds = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = 1; setPadding(22, 22, 22, 22) }
        root.addView(TextView(this).apply { text = "Meghraj AI Settings"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "OpenRouter credentials are encrypted with an Android Keystore key. Fetch your available models, select one, and save."
            setPadding(0, 12, 0, 16)
        })
        key = EditText(this).apply { hint = "OpenRouter API key"; inputType = 129 }
        key.setText(AiConfig.getKey(this))
        root.addView(key, ViewGroup.LayoutParams(-1, -2))
        val fetch = Button(this).apply { text = "Fetch models from OpenRouter"; setOnClickListener { fetchModels() } }
        root.addView(fetch)
        models = Spinner(this)
        root.addView(models)
        status = TextView(this).apply { text = "Add your API key and fetch models."; setPadding(0, 12, 0, 12) }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Save AI settings"
            setOnClickListener {
                val selected = models.selectedItemPosition.takeIf { it in modelIds.indices }?.let { modelIds[it] }
                    ?: AiConfig.getModel(this@MeghrajAiSettingsActivity)
                AiConfig.save(this@MeghrajAiSettingsActivity, key.text.toString().trim(), selected)
                status.text = "Settings saved securely. Selected model: " + selected
            }
        })
        setContentView(root)
        val saved = AiConfig.getModel(this)
        if (saved.isNotBlank()) { modelIds.add(saved); models.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(saved)) }
    }

    private fun fetchModels() {
        val token = key.text.toString().trim()
        if (token.isBlank()) { status.text = "Enter your OpenRouter API key first."; return }
        status.text = "Fetching models…"
        lifecycleScope.launch {
            try {
                val ids = withContext(Dispatchers.IO) {
                    val c = URL("https://openrouter.ai/api/v1/models/user").openConnection() as HttpURLConnection
                    c.requestMethod = "GET"; c.connectTimeout = 15000; c.readTimeout = 30000
                    c.setRequestProperty("Authorization", "Bearer " + token)
                    val code = c.responseCode
                    val body = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
                    c.disconnect()
                    if (code !in 200..299) error("OpenRouter HTTP " + code + ": " + body.take(300))
                    val data = JSONObject(body).optJSONArray("data") ?: error("No model list returned.")
                    (0 until data.length()).mapNotNull { i ->
                        val o = data.getJSONObject(i); o.optString("id").takeIf { it.isNotBlank() }
                    }.distinct().sorted()
                }
                modelIds.clear(); modelIds.addAll(ids)
                models.adapter = ArrayAdapter(this@MeghrajAiSettingsActivity, android.R.layout.simple_spinner_dropdown_item, modelIds)
                val saved = AiConfig.getModel(this@MeghrajAiSettingsActivity)
                val index = modelIds.indexOf(saved)
                if (index >= 0) models.setSelection(index)
                status.text = "Fetched " + ids.size + " models. Select one and save."
            } catch (e: Exception) { status.text = "Model fetch failed: " + (e.message ?: "unknown error") }
        }
    }
}
