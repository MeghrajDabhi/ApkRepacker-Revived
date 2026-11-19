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
        key = edit("OpenRouter API key", true).also { it.setText(AiConfig.getKey(this)); body.addView(it) }
        model = edit("Model ID").also { it.setText(AiConfig.getModel(this)); body.addView(it) }
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
                val context = withContext(Dispatchers.IO) { readContext(instruction) }
                status.text = "Indexed the selected project and preparing relevant source files for OpenRouter…"
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

    /**
     * Build a complete on-device project inventory, then attach relevant source files.
     * The inventory includes every project path (including binary assets); source text is
     * selectively sent to the model under a bounded context budget. APK/binary data is never uploaded.
     */
    private fun readContext(instruction: String): String {
        val ignored = setOf("build", "original", "unknown", ".git", ".gradle", "tmp")
        val allFiles = project.walkTopDown().onEnter { dir ->
            dir == project || (!dir.name.startsWith(".") && dir.name !in ignored)
        }.filter { it.isFile }.toList()

        if (allFiles.isEmpty()) error("The selected project contains no files.")

        val index = StringBuilder()
        index.append("PROJECT ROOT: ").append(project.name).append("\\n")
        index.append("TOTAL FILES: ").append(allFiles.size).append("\\n")
        index.append("COMPLETE PROJECT FILE INDEX (path | size bytes | type):\\n")
        for (file in allFiles) {
            val relative = file.relativeTo(project).invariantSeparatorsPath
            index.append(relative).append(" | ").append(file.length()).append(" | ")
                .append(if (file.extension.isBlank()) "binary/unknown" else file.extension.lowercase())
                .append("\\n")
        }

        val textExtensions = setOf(
            "xml", "smali", "json", "txt", "properties", "yml", "yaml",
            "html", "htm", "css", "js", "java", "kt", "gradle", "pro",
            "cfg", "ini", "md", "xml"
        )
        val taskWords = instruction.lowercase().split(Regex("[^a-z0-9_]+"))
            .filter { it.length >= 3 }.toSet()
        val candidates = allFiles.filter { it.extension.lowercase() in textExtensions }
        fun priority(file: File): Int {
            val path = file.relativeTo(project).invariantSeparatorsPath.lowercase()
            return when {
                file.name.equals("AndroidManifest.xml", true) -> 0
                path.contains("/res/values") && (path.contains("color") || path.contains("style") || path.contains("theme")) -> 1
                path.contains("/res/layout") || path.contains("/res/drawable") || path.contains("/res/color") || path.contains("/res/menu") -> 2
                taskWords.any { word -> path.contains(word) } -> 3
                path.contains("theme") || path.contains("color") || path.contains("style") || path.contains("layout") -> 4
                else -> 5
            }
        }
        val ordered = candidates.sortedWith(compareBy<File> { priority(it) }.thenBy { it.path })
        val source = StringBuilder()
        var budget = 480 * 1024
        var included = 0
        for (file in ordered) {
            if (budget <= 0) break
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            if (bytes.any { it == 0.toByte() }) continue
            val raw = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: continue
            if (raw.contains('\\u0000')) continue
            val relative = file.relativeTo(project).invariantSeparatorsPath
            val header = "\\n\\n===== SOURCE FILE: " + relative + " (" + bytes.size + " bytes) =====\\n"
            val remaining = budget - header.length
            if (remaining <= 0) break
            val content = raw.take(minOf(raw.length, remaining, 48 * 1024))
            source.append(header).append(content)
            if (content.length < raw.length) source.append("\\n[FILE CONTENT TRUNCATED BY CONTEXT LIMIT]")
            budget -= header.length + content.toByteArray(Charsets.UTF_8).size
            included++
        }
        if (included == 0) error("No readable text source files were found in the selected project.")
        return index.toString() + "\\nTEXT SOURCE FILES INCLUDED: " + included +
            "\\nSOURCE CONTEXT LIMIT: 480 KB. Binary files are indexed but not uploaded." + source
    }

