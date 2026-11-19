package com.riyadm.apkrepacker.activity

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MeghrajAiActivity : BaseActivity() {
    private lateinit var project: File
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var task: EditText
    private lateinit var status: TextView
    private lateinit var run: Button
    private var proposed: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        project = File(intent.getStringExtra(EXTRA_PROJECT_PATH).orEmpty()).canonicalFile
        if (!project.isDirectory) { finish(); return }
        val page = LinearLayout(this).apply { orientation = 1; setPadding(20, 20, 20, 20) }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = 1 }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(TextView(this).apply { text = "✦ Meghraj AI"; textSize = 25f })
        body.addView(TextView(this).apply {
            text = "Project: " + project.name + "\nOnly selected text files are sent to OpenRouter when you submit. The APK itself stays on this device. AI edits are previewed before applying."
            textSize = 13f; setPadding(0, 12, 0, 16)
        })
        key = edit("OpenRouter API key", true).also(body::addView)
        model = edit("Model ID").also { it.setText("openrouter/auto"); body.addView(it) }
        task = edit("Describe what you want changed", false, true).also(body::addView)
        run = Button(this).apply { text = "Ask AI to modify this project"; setOnClickListener { ask() } }
        body.addView(run)
        status = TextView(this).apply { text = "Ready"; textSize = 13f; setPadding(0, 18, 0, 12) }
        body.addView(status)
        setContentView(page)
    }

    private fun edit(hintText: String, secret: Boolean = false, multi: Boolean = false) = EditText(this).apply {
        hint = hintText
        if (secret) inputType = 129
        if (multi) { minLines = 4; maxLines = 8; gravity = Gravity.TOP }
    }

    private fun ask() {
        val token = key.text.toString().trim()
        val modelId = model.text.toString().trim().ifBlank { "openrouter/auto" }
        val instruction = task.text.toString().trim()
        if (token.isBlank() || instruction.isBlank()) { status.text = "Enter your API key and a task."; return }
        run.isEnabled = false
        lifecycleScope.launch {
            try {
                status.text = "Reading project files…"
                val context = withContext(Dispatchers.IO) { readContext() }
                status.text = "Sending selected source text to OpenRouter…"
                val result = withContext(Dispatchers.IO) { requestAi(token, modelId, instruction, context) }
                val json = JSONObject(result)
                val changes = json.optJSONArray("changes") ?: error("AI response did not contain a changes array.")
                if (changes.length() == 0) {
                    status.text = json.optString("summary", "No changes proposed.")
                } else {
                    proposed = json
                    val preview = StringBuilder(json.optString("summary", "Proposed changes"))
                    for (i in 0 until changes.length()) preview.append("\n• ").append(changes.getJSONObject(i).optString("path"))
                    MaterialAlertDialogBuilder(this@MeghrajAiActivity)
                        .setTitle("Review AI changes")
                        .setMessage(preview.toString())
                        .setNegativeButton("Cancel") { _, _ -> proposed = null }
                        .setPositiveButton("Apply changes") { _, _ -> applyChanges() }
                        .show()
                    status.text = "Proposed " + changes.length() + " edit(s). No files changed yet."
                }
            } catch (e: Exception) {
                status.text = "AI error: " + (e.message ?: e.javaClass.simpleName)
            } finally { run.isEnabled = true }
        }
    }

    private fun readContext(): String {
        val allowed = setOf("xml", "smali", "json", "txt", "properties", "yml", "yaml")
        val files = project.walkTopDown().onEnter { dir ->
            dir == project || (!dir.name.startsWith(".") && dir.name !in setOf("build", "original", "unknown"))
        }.filter { it.isFile && it.extension.lowercase() in allowed }
            .sortedBy { if (it.name == "AndroidManifest.xml") 0 else if (it.path.contains("/res/values")) 1 else 2 }
        val out = StringBuilder()
        var budget = 32000
        var count = 0
        for (file in files) {
            if (count >= 8 || budget <= 0) break
            val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (text.contains('\u0000')) continue
            val clipped = text.take(minOf(text.length, budget, 6000))
            out.append("\n--- ").append(file.relativeTo(project).invariantSeparatorsPath).append(" ---\n").append(clipped).append("\n")
            budget -= clipped.toByteArray().size
            count++
        }
        if (count == 0) error("No supported text files found.")
        return out.toString()
    }

    private fun requestAi(token: String, modelId: String, instruction: String, context: String): String {
        val system = "You are Meghraj AI, an Android APK project editing agent. Return ONLY JSON: " +
            """{"summary":"short explanation","changes":[{"path":"relative/path","content":"complete replacement file text"}]}""" +
            ". Only edit existing supplied files. Never invent paths. Preserve unrelated code. Do not modify signing keys, certificates, permissions, authentication, payments, licensing, anti-tamper, or security controls. If unsupported, return an empty changes array."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", "TASK:\n" + instruction + "\n\nPROJECT FILES:\n" + context))
        val payload = JSONObject().put("model", modelId).put("temperature", 0.2).put("messages", messages)
        val conn = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.connectTimeout = 20000; conn.readTimeout = 90000; conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("HTTP-Referer", "https://github.com/MeghrajDabhi/ApkRepacker-Revived")
        conn.setRequestProperty("X-Title", "Meghraj AI APK Editor")
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("OpenRouter HTTP " + code + ": " + response.take(400))
        var answer = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        if (answer.startsWith("```")) answer = answer.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return answer
    }

    private fun applyChanges() {
        val changes = proposed?.optJSONArray("changes") ?: return
        try {
            val root = project.canonicalFile
            for (i in 0 until changes.length()) {
                val item = changes.getJSONObject(i)
                val rel = item.getString("path")
                require(rel.isNotBlank() && !rel.startsWith("/") && !rel.contains("\\"))
                val target = File(root, rel).canonicalFile
                require(target.path.startsWith(root.path + File.separator)) { "Path escapes project" }
                require(target.isFile) { "Only existing files can be replaced: " + rel }
                require(target.extension.lowercase() in setOf("xml", "smali", "json", "txt", "properties", "yml", "yaml"))
                val content = item.getString("content")
                require(content.length <= 200000) { "Proposed file too large" }
                val backup = File(target.parentFile, target.name + ".meghraj-ai-backup")
                if (!backup.exists()) target.copyTo(backup)
                val temp = File(target.parentFile, target.name + ".meghraj-ai-tmp")
                temp.writeText(content, Charsets.UTF_8)
                check(temp.renameTo(target)) { "Could not replace " + rel }
            }
            status.text = "Applied " + changes.length() + " edit(s). Backups are saved beside files with .meghraj-ai-backup. Review and rebuild."
        } catch (e: Exception) { status.text = "Apply failed: " + (e.message ?: "unknown error") }
        proposed = null
    }

    companion object { const val EXTRA_PROJECT_PATH = "meghraj_ai_project_path" }
}
