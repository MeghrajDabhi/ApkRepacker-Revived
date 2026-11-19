package com.riyadm.apkrepacker.activity

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
            text = "Project: " + project.name + "\nThe project index is read locally. Relevant text files are sent to OpenRouter; APK binaries are not uploaded."
            textSize = 13f; setPadding(0, 12, 0, 16)
        })
        key = edit("OpenRouter API key", true).also { it.setText(AiConfig.getKey(this)); body.addView(it) }
        model = edit("Model ID").also { it.setText(AiConfig.getModel(this)); body.addView(it) }
        task = edit("Describe what you want changed", false, true).also(body::addView)
        run = Button(this).apply { text = "Analyze project and propose edits"; setOnClickListener { ask() } }
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
        val modelId = model.text.toString().trim().ifBlank { AiConfig.getModel(this) }
        val instruction = task.text.toString().trim()
        if (token.isBlank() || instruction.isBlank()) { status.text = "Enter your API key and a task."; return }
        AiConfig.save(this, token, modelId)
        run.isEnabled = false
        lifecycleScope.launch {
            try {
                status.text = "Indexing every file in the selected project…"
                val context = withContext(Dispatchers.IO) { readContext(instruction) }
                status.text = "Sending project index and source context to OpenRouter…"
                val answer = withContext(Dispatchers.IO) { requestAi(token, modelId, instruction, context) }
                val json = JSONObject(answer)
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
                        .setNegativeButton("Reject") { _, _ -> proposed = null }
                        .setPositiveButton("Apply edits") { _, _ -> applyChanges() }
                        .show()
                    status.text = "Proposed " + changes.length() + " edit(s). Nothing changed yet."
                }
            } catch (e: Exception) {
                status.text = "AI error: " + (e.message ?: e.javaClass.simpleName)
            } finally { run.isEnabled = true }
        }
    }

    private fun readContext(instruction: String): String {
        val ignored = setOf("build", "original", "unknown", ".git", ".gradle", "tmp")
        val allFiles = project.walkTopDown().onEnter { dir ->
            dir == project || (!dir.name.startsWith(".") && dir.name !in ignored)
        }.filter { it.isFile }.toList()
        require(allFiles.isNotEmpty()) { "Selected project contains no files." }
        val index = StringBuilder()
        index.append("PROJECT: ").append(project.name).append("\nTOTAL FILES: ").append(allFiles.size)
            .append("\nCOMPLETE FILE INDEX (relative path | bytes | extension):\n")
        allFiles.forEach { f ->
            index.append(f.relativeTo(project).invariantSeparatorsPath).append(" | ").append(f.length())
                .append(" | ").append(f.extension.lowercase()).append("\n")
        }
        val extensions = setOf("xml","smali","json","txt","properties","yml","yaml","html","htm","css","js","java","kt","gradle","pro","cfg","ini","md")
        val words = instruction.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 3 }.toSet()
        val candidates = allFiles.filter { it.extension.lowercase() in extensions }.sortedBy { f ->
            val path = f.relativeTo(project).invariantSeparatorsPath.lowercase()
            when {
                f.name.equals("AndroidManifest.xml", true) -> 0
                path.contains("/res/values") && listOf("color","style","theme").any { path.contains(it) } -> 1
                listOf("/res/layout","/res/drawable","/res/color","/res/menu").any { path.contains(it) } -> 2
                words.any { path.contains(it) } -> 3
                listOf("theme","color","style","layout").any { path.contains(it) } -> 4
                else -> 5
            }
        }
        val source = StringBuilder()
        var budget = 480 * 1024
        var included = 0
        for (f in candidates) {
            if (budget <= 0) break
            val bytes = runCatching { f.readBytes() }.getOrNull() ?: continue
            if (bytes.any { it == 0.toByte() }) continue
            val raw = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: continue
            if (raw.contains('\u0000')) continue
            val rel = f.relativeTo(project).invariantSeparatorsPath
            val header = "\n\n===== FILE: " + rel + " (" + bytes.size + " bytes) =====\n"
            val content = raw.take(minOf(raw.length, budget - header.length, 48 * 1024))
            if (content.isEmpty()) break
            source.append(header).append(content)
            if (content.length < raw.length) source.append("\n[TRUNCATED]")
            budget -= header.toByteArray().size + content.toByteArray().size
            included++
        }
        require(included > 0) { "No readable text source files found." }
        return index.toString() + "\nTEXT FILES INCLUDED: " + included +
            "\nBINARY FILES ARE INDEXED BUT NOT UPLOADED.\n" + source
    }

    private fun requestAi(token: String, modelId: String, instruction: String, context: String): String {
        val system = """You are an Android APK project editing agent. Return ONLY JSON:
{"summary":"brief explanation","changes":[{"path":"existing/relative/path","content":"complete replacement file text"}]}
Use the complete file index to identify correct existing source files. Only return paths whose full text is included below. For GUI color changes inspect color resources, values, themes, styles, layouts, drawable XML and relevant Smali references. Never invent paths. Preserve unrelated code. Do not alter authentication, licensing, payment checks, anti-tamper or security protections. If you cannot safely determine an edit, return an empty changes array and explain why."""
        val messages = JSONArray()
            .put(JSONObject().put("role","system").put("content",system))
            .put(JSONObject().put("role","user").put("content","TASK:\n" + instruction + "\n\nPROJECT:\n" + context))
        val payload = JSONObject().put("model",modelId).put("temperature",0.15).put("messages",messages)
        val conn = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.connectTimeout = 20000; conn.readTimeout = 120000; conn.doOutput = true
        conn.setRequestProperty("Authorization","Bearer " + token)
        conn.setRequestProperty("Content-Type","application/json")
        conn.setRequestProperty("HTTP-Referer","https://github.com/MeghrajDabhi/ApkRepacker-Revived")
        conn.setRequestProperty("X-Title","Meghraj AI APK Editor")
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("OpenRouter HTTP " + code + ": " + response.take(500))
        var content = JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        if (content.startsWith("```")) content = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return content
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
                require(target.path.startsWith(root.path + File.separator)) { "Path escapes project: " + rel }
                require(target.isFile) { "Only existing files can be edited: " + rel }
                require(target.extension.lowercase() in setOf("xml","smali","json","txt","properties","yml","yaml","html","css","js","java","kt","gradle","pro","cfg","ini","md"))
                val content = item.getString("content")
                require(content.length <= 200000) { "Proposed file is too large: " + rel }
                val backup = File(target.parentFile, target.name + ".meghraj-ai-backup")
                if (!backup.exists()) target.copyTo(backup)
                val temp = File(target.parentFile, target.name + ".meghraj-ai-tmp")
                temp.writeText(content, Charsets.UTF_8)
                check(temp.renameTo(target)) { "Could not replace " + rel }
            }
            status.text = "Applied " + changes.length() + " edit(s). Backups are beside files with .meghraj-ai-backup. Review and rebuild."
        } catch (e: Exception) { status.text = "Apply failed: " + (e.message ?: "unknown error") }
        proposed = null
    }

    companion object {
        const val EXTRA_PROJECT_PATH = "projectPatch"
    }
}
