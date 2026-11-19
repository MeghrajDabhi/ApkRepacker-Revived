package com.riyadm.apkrepacker.activity

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class AgentIdea(val category: String, val title: String)

class MeghrajAiActivity : BaseActivity() {
    private lateinit var project: File
    private lateinit var task: TextInputEditText
    private lateinit var status: TextView
    private lateinit var run: MaterialButton
    private lateinit var conversation: LinearLayout
    private lateinit var providerSummary: TextView
    private lateinit var pageScroll: ScrollView
    private var proposed: JSONObject? = null
    private var requestInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rawProjectPath = intent.getStringExtra(EXTRA_PROJECT_PATH)?.trim().orEmpty()
        if (rawProjectPath.isBlank()) {
            Toast.makeText(this, "No decoded project was supplied. Reopen Agent from an APK project.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        project = try {
            File(rawProjectPath).canonicalFile
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open project: " + (e.message ?: "invalid path"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (!project.isDirectory || !project.canRead()) {
            Toast.makeText(this, "The decoded project is missing or cannot be read.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        buildWorkspace()
    }

    override fun onResume() {
        super.onResume()
        if (::providerSummary.isInitialized) refreshProviderSummary()
    }

    private fun buildWorkspace() {
        val surface = color(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onSurface = color(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = "Agent workspace"
            setNavigationIcon(com.riyadm.apkrepacker.R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        }
        page.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))

        pageScroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        pageScroll.addView(content)
        page.addView(pageScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val hero = card(color(com.google.android.material.R.attr.colorSurfaceContainerLow, surface))
        val heroBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        heroBody.addView(TextView(this).apply {
            text = "YOUR APK. YOUR INSTRUCTIONS."
            textSize = 11f
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(com.google.android.material.R.attr.colorPrimaryContainer, onSurface))
        })
        heroBody.addView(TextView(this).apply {
            text = project.name
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(onSurface)
            setPadding(0, dp(7), 0, dp(4))
        })
        heroBody.addView(TextView(this).apply {
            text = "Describe a change in plain language. Agent inspects relevant project files and asks before applying edits."
            textSize = 13f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface))
        })
        providerSummary = TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
            setTextColor(color(com.google.android.material.R.attr.colorPrimaryContainer, onSurface))
        }
        heroBody.addView(providerSummary)
        hero.addView(heroBody)
        content.addView(hero, marginParams(bottom = 12))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(MaterialButton(this).apply {
            text = "Provider & models"
            textSize = 12f
            setOnClickListener {
                startActivity(android.content.Intent(this@MeghrajAiActivity, MeghrajAiSettingsActivity::class.java))
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(MaterialButton(this).apply {
            text = "100 ideas"
            textSize = 12f
            setIconResource(com.riyadm.apkrepacker.R.drawable.ic_meghraj_ai)
            setOnClickListener { showIdeas() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        content.addView(actions, marginParams(bottom = 12))

        val chatCard = card(surface)
        val chatBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        chatBody.addView(TextView(this).apply {
            text = "WORKSPACE ACTIVITY"
            textSize = 11f
            letterSpacing = 0.1f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface))
            setPadding(dp(2), 0, 0, dp(10))
        })
        conversation = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatBody.addView(conversation)
        chatCard.addView(chatBody)
        content.addView(chatCard, marginParams(bottom = 12))
        addMessage("agent", "I’m ready. Ask a question, say hello, or describe an APK change. General messages do not upload your project files.")

        val composer = card(color(com.google.android.material.R.attr.colorSurfaceContainerLow, surface))
        val composeBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        composeBody.addView(TextView(this).apply {
            text = "WHAT SHOULD I DO?"
            textSize = 11f
            letterSpacing = 0.1f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(com.google.android.material.R.attr.colorPrimaryContainer, onSurface))
        })
        val taskLayout = TextInputLayout(this).apply {
            hint = "Describe your task..."
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
        }
        task = TextInputEditText(this).apply {
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP
            textSize = 15f
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        taskLayout.addView(task, LinearLayout.LayoutParams(-1, -2))
        composeBody.addView(taskLayout, marginParams(top = 10, bottom = 10))
        run = MaterialButton(this).apply {
            text = "Run task"
            textSize = 14f
            setIconResource(com.riyadm.apkrepacker.R.drawable.ic_meghraj_ai)
            isAllCaps = false
            setOnClickListener { ask() }
        }
        composeBody.addView(run, LinearLayout.LayoutParams(-1, dp(52)))
        status = TextView(this).apply {
            text = "Ready · Project files stay on this device until an edit task needs context."
            textSize = 12f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface))
            setPadding(dp(3), dp(10), dp(3), 0)
        }
        composeBody.addView(status)
        composer.addView(composeBody)
        content.addView(composer)

        setContentView(page)
        refreshProviderSummary()
    }

    private fun refreshProviderSummary() {
        val activeId = AiConfig.getActiveProfileId(this)
        val activeName = AiConfig.getProfiles(this).firstOrNull { it.id == activeId }?.name
        providerSummary.text = (activeName?.let { "$it  ·  " } ?: "") +
            AiConfig.getProvider(this) + "  ·  " + AiConfig.getModel(this)
    }

    private fun addMessage(role: String, message: String) {
        val assistant = role == "agent"
        val bubbleColor = if (assistant) color(com.google.android.material.R.attr.colorSurfaceContainer, Color.LTGRAY)
            else color(com.google.android.material.R.attr.colorPrimaryContainer, Color.LTGRAY)
        val bubble = card(bubbleColor).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
        }
        val text = TextView(this).apply {
            this.text = (if (assistant) "AGENT\n" else "YOU\n") + message
            textSize = 14f
            setTextColor(if (assistant) color(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
                else color(com.google.android.material.R.attr.colorOnPrimaryContainer, Color.BLACK))
            setPadding(dp(13), dp(11), dp(13), dp(11))
        }
        bubble.addView(text)
        val lp = LinearLayout.LayoutParams(
            if (assistant) ViewGroup.LayoutParams.MATCH_PARENT else (resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(9)
        lp.gravity = if (assistant) Gravity.START else Gravity.END
        conversation.addView(bubble, lp)
        if (::pageScroll.isInitialized) pageScroll.post { pageScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun ask() {
        if (requestInProgress) return
        val instruction = task.text?.toString()?.trim().orEmpty()
        if (instruction.isBlank()) {
            status.text = "Write a task first."
            return
        }
        val token = AiConfig.getKey(this)
        if (token.isBlank()) {
            status.text = "Connect an API key in Provider & models first."
            startActivity(android.content.Intent(this, MeghrajAiSettingsActivity::class.java))
            return
        }
        val modelId = AiConfig.getModel(this).trim()
        val provider = AiConfig.getProvider(this)
        val baseUrl = AiConfig.getBaseUrl(this)
        val editRequest = isProjectEditRequest(instruction)

        addMessage("user", instruction)
        task.setText("")
        requestInProgress = true
        run.isEnabled = false
        run.text = "Working..."
        status.text = if (editRequest) "Reading a small set of relevant project files..." else "Thinking · no project files are being uploaded..."
        lifecycleScope.launch {
            try {
                val context = if (editRequest) withContext(Dispatchers.IO) { readContext(instruction) } else ""
                status.text = if (editRequest) "Sending selected source context to " + provider + "..." else "Sending message to " + provider + "..."
                val answer = withContext(Dispatchers.IO) {
                    requestAi(token, modelId, provider, baseUrl, instruction, context, editRequest)
                }
                if (editRequest) {
                    val json = JSONObject(extractJson(answer))
                    val changes = json.optJSONArray("changes") ?: error("The model did not return a valid edit plan. Try again with a more specific task.")
                    if (changes.length() == 0) {
                        val summary = json.optString("summary", "No file changes were proposed.")
                        addMessage("agent", summary)
                        status.text = "No edits proposed."
                    } else {
                        proposed = json
                        val preview = StringBuilder(json.optString("summary", "Proposed changes"))
                        for (i in 0 until changes.length()) {
                            preview.append("\n\n• ").append(changes.getJSONObject(i).optString("path"))
                        }
                        MaterialAlertDialogBuilder(this@MeghrajAiActivity)
                            .setTitle("Review proposed changes")
                            .setMessage(preview.toString())
                            .setNegativeButton("Reject") { _, _ ->
                                proposed = null
                                addMessage("agent", "Changes rejected. Your project files were not changed.")
                            }
                            .setPositiveButton("Apply edits") { _, _ -> applyChanges() }
                            .show()
                        status.text = "Review " + changes.length() + " proposed file change(s). Nothing has changed yet."
                    }
                } else {
                    addMessage("agent", answer.ifBlank { "The provider returned an empty answer." })
                    status.text = "Reply received from " + provider + "."
                }
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                addMessage("agent", message)
                status.text = "Request failed · " + message.take(180)
            } finally {
                requestInProgress = false
                run.isEnabled = true
                run.text = "Run task"
            }
        }
    }

    private fun isProjectEditRequest(instruction: String): Boolean {
        val editWords = listOf(
            "add", "create", "implement", "build", "change", "modify", "edit", "remove",
            "delete", "fix", "repair", "update", "redesign", "replace", "rename", "convert",
            "refactor", "integrate", "insert", "enable", "disable", "make", "install"
        )
        val words = instruction.lowercase().split(Regex("[^a-z0-9]+")).toSet()
        return editWords.any { it in words }
    }

    private fun readContext(instruction: String): String {
        val ignored = setOf("build", "original", "unknown", ".git", ".gradle", "tmp", "node_modules")
        val allFiles = project.walkTopDown().onEnter { dir ->
            dir == project || (!dir.name.startsWith(".") && dir.name !in ignored)
        }.filter { it.isFile }.toList()
        require(allFiles.isNotEmpty()) { "The selected project contains no files." }

        val index = StringBuilder()
        index.append("PROJECT: ").append(project.name).append("\nFILE INDEX (relative path | size | extension):\n")
        for (file in allFiles) {
            if (index.length >= 5000) break
            index.append(file.relativeTo(project).invariantSeparatorsPath)
                .append(" | ").append(file.length()).append(" | ").append(file.extension.lowercase()).append('\n')
        }
        if (allFiles.size > 1 && index.length >= 5000) index.append("[file index shortened]\n")

        val extensions = setOf("xml", "smali", "json", "txt", "properties", "yml", "yaml", "html", "htm", "css", "js", "java", "kt", "gradle", "pro", "cfg", "ini", "md")
        val words = instruction.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 3 }.toSet()
        val candidates = allFiles.filter { it.extension.lowercase() in extensions && it.length() <= 1024 * 1024 }
            .sortedBy { file ->
                val path = file.relativeTo(project).invariantSeparatorsPath.lowercase()
                when {
                    file.name.equals("AndroidManifest.xml", true) -> 0
                    path.contains("/res/values") && listOf("color", "style", "theme").any { path.contains(it) } -> 1
                    listOf("/res/layout", "/res/drawable", "/res/color", "/res/menu").any { path.contains(it) } -> 2
                    words.any { path.contains(it) } -> 3
                    listOf("theme", "color", "style", "layout").any { path.contains(it) } -> 4
                    else -> 5
                }
            }

        val source = StringBuilder()
        var budget = 16 * 1024
        var included = 0
        for (file in candidates) {
            if (budget <= 0 || included >= 16) break
            val raw = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            if (raw.contains('\u0000')) continue
            val relative = file.relativeTo(project).invariantSeparatorsPath
            val header = "\n\n===== FILE: " + relative + " =====\n"
            val available = minOf(budget - header.length, 3800)
            if (available <= 0) break
            val text = raw.take(available)
            if (text.isBlank()) continue
            source.append(header).append(text)
            if (text.length < raw.length) source.append("\n[FILE CONTENT TRUNCATED]")
            budget -= header.length + text.length
            included++
        }
        require(included > 0) { "No readable text files were found in the selected project." }
        return index.toString() + "\nSOURCE FILES INCLUDED: " + included +
            "\nSOURCE CONTEXT IS LIMITED TO ABOUT 16 KB. APK binaries are not uploaded.\n" + source
    }

    private fun requestAi(
        token: String,
        modelId: String,
        provider: String,
        baseUrl: String,
        instruction: String,
        context: String,
        editRequest: Boolean
    ): String {
        val system = if (editRequest) {
            """You are an Android APK project editing agent. Return ONLY valid JSON:
{"summary":"brief explanation","changes":[{"path":"existing/relative/path","content":"complete replacement file text"}]}
Only return files whose full text is included in the supplied source context. Never invent paths. Preserve unrelated code. Never change authentication, licensing, payment checks, anti-tamper or security protections. Keep the response concise. If safe edits cannot be determined, return an empty changes array and explain why in summary."""
        } else {
            "You are the in-app assistant for an Android APK editor. Answer the user's message directly and clearly in normal text. Do not return JSON. Do not claim to have changed files. No project files are included in this request."
        }
        val userPrompt = if (editRequest) {
            "TASK:\n" + instruction + "\n\nPROJECT CONTEXT:\n" + context
        } else instruction

        if (provider == "Gemini") {
            val prompt = system + "\n\n" + userPrompt
            val payload = JSONObject()
                .put("contents", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.15)
                    .put("maxOutputTokens", if (editRequest) 4096 else 1024))
            val endpoint = baseUrl.trim().trimEnd('/') + "/v1beta/models/" + modelId.removePrefix("models/") +
                ":generateContent?key=" + java.net.URLEncoder.encode(token, "UTF-8")
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) throw IllegalStateException(explainApiError(provider, code, response))
            val root = JSONObject(response)
            val candidates = root.optJSONArray("candidates") ?: error("The provider returned no candidates.")
            val parts = candidates.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: error("The provider returned no text parts. Check the selected model.")
            val raw = (0 until parts.length()).mapNotNull { i ->
                parts.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("\n")
            if (raw.isBlank()) error("The provider returned an empty answer. Try a text-generation model.")
            return if (editRequest) extractJson(raw) else raw.trim()
        }

        if (provider == "Anthropic") {
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", userPrompt))
            val payload = JSONObject().put("model", modelId)
                .put("max_tokens", if (editRequest) 4096 else 1024)
                .put("system", system).put("messages", messages)
            val endpoint = baseUrl.trim().trimEnd('/') + "/messages"
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("x-api-key", token)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) throw IllegalStateException(explainApiError(provider, code, response))
            val root = JSONObject(response)
            val blocks = root.optJSONArray("content") ?: error("Anthropic returned no content blocks.")
            val raw = (0 until blocks.length()).mapNotNull { i ->
                blocks.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("\n")
            if (raw.isBlank()) error("Anthropic returned an empty answer.")
            return if (editRequest) extractJson(raw) else raw.trim()
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", userPrompt))
        val payload = JSONObject().put("model", modelId).put("temperature", 0.15)
            .put("max_tokens", if (editRequest) 4096 else 1024).put("messages", messages)
        val endpoint = baseUrl.trim().trimEnd('/') + "/chat/completions"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer " + token)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        if (provider == "OpenRouter") {
            conn.setRequestProperty("HTTP-Referer", "https://github.com/MeghrajDabhi/ApkRepacker-Revived")
            conn.setRequestProperty("X-Title", "Agent APK Editor")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException(explainApiError(provider, code, response))
        val root = JSONObject(response)
        val choices = root.optJSONArray("choices") ?: error(provider + " returned no choices.")
        if (choices.length() == 0) error(provider + " returned an empty choices list.")
        val choice = choices.optJSONObject(0) ?: error(provider + " returned an invalid choice.")
        val message = choice.optJSONObject("message") ?: error(provider + " returned no message.")
        val rawContent = message.opt("content")
        val answer = when (rawContent) {
            is String -> rawContent
            is JSONArray -> (0 until rawContent.length()).mapNotNull { i ->
                rawContent.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }
            }.joinToString("\n")
            else -> ""
        }.trim()
        if (answer.isBlank()) {
            val finish = choice.optString("finish_reason")
            if (finish == "length") error("The model stopped at its output-token limit. Choose a model with a larger output allowance or shorten the requested edit.")
            error(provider + " returned an empty answer. Finish reason: " + finish)
        }
        return if (editRequest) extractJson(answer) else answer
    }

    private fun extractJson(raw: String): String {
        var text = raw.trim()
        val fence = 96.toChar().toString().repeat(3)
        text = text.removePrefix(fence + "json").removePrefix(fence).removeSuffix(fence).trim()
        val first = text.indexOf('{')
        val last = text.lastIndexOf('}')
        if (first >= 0 && last > first) text = text.substring(first, last + 1)
        return text
    }

    private fun explainApiError(provider: String, code: Int, body: String): String {
        val lower = body.lowercase()
        return when {
            lower.contains("context_length_exceeded") || lower.contains("maximum context") ||
                lower.contains("context window") || lower.contains("prompt is too long") ||
                lower.contains("too many tokens") || lower.contains("input token count exceeds") ->
                "This model's context window is too small for the current request. Agent now sends a limited set of project files. Try again, or select a larger-context model."
            lower.contains("token_limit_exceeded") ->
                "The provider rejected this request because its token budget or account limit was reached. This is different from an invalid API key; check provider usage, credits and limits."
            lower.contains("string_too_long") ->
                "The provider has a per-field text limit. Agent reduced project context; try a more focused task."
            code == 401 || lower.contains("invalid_api_key") || lower.contains("authentication_error") ->
                "The provider rejected the API key. Confirm that the key belongs to " + provider + " and is active."
            code == 402 || lower.contains("insufficient_quota") || lower.contains("insufficient credits") || lower.contains("billing") ->
                "The API key may be valid, but the provider reports insufficient credits or billing quota. Check the provider account's API usage and billing."
            code == 429 || lower.contains("rate limit") ->
                "The provider rate limit was reached. Wait briefly or check the account's request limits."
            else -> provider + " returned HTTP " + code + ": " + body.take(320)
        }
    }

    private fun showIdeas() {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val search = EditText(this).apply {
            hint = "Search 100 ideas..."
            maxLines = 1
        }
        val list = ListView(this)
        wrap.addView(search, LinearLayout.LayoutParams(-1, dp(52)))
        wrap.addView(list, LinearLayout.LayoutParams(-1, dp(440)))
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1,
            ideas.map { it.category + "  ·  " + it.title })
        list.adapter = adapter
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("100 premium app ideas")
            .setMessage("Choose an idea to turn it into an Agent task.")
            .setView(wrap)
            .setNegativeButton("Close", null)
            .create()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase().orEmpty()
                adapter.clear()
                adapter.addAll(ideas.filter { it.category.lowercase().contains(q) || it.title.lowercase().contains(q) }
                    .map { it.category + "  ·  " + it.title })
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val selectedLabel = adapter.getItem(position).orEmpty()
            val idea = ideas.firstOrNull { selectedLabel == it.category + "  ·  " + it.title }
            if (idea != null) {
                task.setText("Implement this app upgrade: " + idea.title + ". Category: " + idea.category +
                    ". Inspect the existing project, propose the smallest safe implementation, preserve existing features, and show the files to be changed before applying.")
                task.setSelection(task.text?.length ?: 0)
                dialog.dismiss()
                status.text = "Idea selected · review the task and tap Run task."
            }
        }
        dialog.show()
    }

    private fun applyChanges() {
        val changes = proposed?.optJSONArray("changes") ?: run {
            status.text = "There are no approved changes to apply."
            return
        }
        var applied = 0
        try {
            for (i in 0 until changes.length()) {
                val item = changes.getJSONObject(i)
                val relative = item.getString("path")
                val content = item.getString("content")
                require(relative.isNotBlank()) { "AI returned an empty file path." }
                val target = File(project, relative).canonicalFile
                require(target.toPath().startsWith(project.canonicalFile.toPath())) {
                    "Rejected path outside project: " + relative
                }
                require(target.isFile) { "Only existing files can currently be replaced: " + relative }
                require(!target.name.endsWith(".meghraj-ai-backup")) { "Invalid backup target." }
                val backup = File(target.parentFile, target.name + ".meghraj-ai-backup")
                if (!backup.exists()) target.copyTo(backup, overwrite = false)
                val temp = File(target.parentFile, target.name + ".meghraj-ai.tmp")
                temp.writeText(content, Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    target.writeText(content, Charsets.UTF_8)
                    temp.delete()
                }
                applied++
            }
            proposed = null
            val result = "Applied " + applied + " file change(s). Backups were preserved. Rebuild to test."
            addMessage("agent", result)
            status.text = result
        } catch (e: Exception) {
            status.text = "Could not apply all changes after " + applied + " file(s): " +
                (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun card(backgroundColor: Int) = MaterialCardView(this).apply {
        radius = dp(20).toFloat()
        cardElevation = dp(1).toFloat()
        strokeWidth = dp(1)
        setStrokeColor(color(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY))
        setCardBackgroundColor(backgroundColor)
    }

    private fun marginParams(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(-1, -2).apply {
            if (top > 0) topMargin = dp(top)
            if (bottom > 0) bottomMargin = dp(bottom)
        }

    private fun color(attribute: Int, fallback: Int): Int = MaterialColors.getColor(this, attribute, fallback)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROJECT_PATH = "projectPatch"

        private val ideas = listOf(
            AgentIdea("Luxury interface", "Premium glass-style dashboard"),
            AgentIdea("Luxury interface", "Dynamic wallpaper-based color themes"),
            AgentIdea("Luxury interface", "Animated project cards with app icons"),
            AgentIdea("Luxury interface", "Elegant dark and light theme switcher"),
            AgentIdea("Luxury interface", "Custom accent color studio"),
            AgentIdea("Luxury interface", "Smooth spring page transitions"),
            AgentIdea("Luxury interface", "Premium icon and typography system"),
            AgentIdea("Luxury interface", "Redesigned bottom navigation"),
            AgentIdea("Luxury interface", "Adaptive tablet and desktop layout"),
            AgentIdea("Luxury interface", "Customizable home screen widgets"),
            AgentIdea("Project workspace", "Recent projects command center"),
            AgentIdea("Project workspace", "Project health and file-count overview"),
            AgentIdea("Project workspace", "APK metadata inspection panel"),
            AgentIdea("Project workspace", "Project bookmarks and pinned files"),
            AgentIdea("Project workspace", "Compare two decoded project versions"),
            AgentIdea("Project workspace", "Project snapshots and restore points"),
            AgentIdea("Project workspace", "Duplicate file and resource finder"),
            AgentIdea("Project workspace", "Project-wide activity timeline"),
            AgentIdea("Project workspace", "Smart project search with filters"),
            AgentIdea("Project workspace", "Multi-project workspace switcher"),
            AgentIdea("AI intelligence", "Natural-language multi-file editing"),
            AgentIdea("AI intelligence", "AI explanation for selected Smali code"),
            AgentIdea("AI intelligence", "AI XML layout improvement assistant"),
            AgentIdea("AI intelligence", "AI resource and theme detection"),
            AgentIdea("AI intelligence", "AI patch planning with file previews"),
            AgentIdea("AI intelligence", "AI-generated change summaries"),
            AgentIdea("AI intelligence", "Agent conversation history"),
            AgentIdea("AI intelligence", "Reusable task prompt templates"),
            AgentIdea("AI intelligence", "Model capability and context indicator"),
            AgentIdea("AI intelligence", "Automatic context-size estimation"),
            AgentIdea("Code and resources", "Smali syntax highlighting"),
            AgentIdea("Code and resources", "XML structure tree viewer"),
            AgentIdea("Code and resources", "Manifest permission explainer"),
            AgentIdea("Code and resources", "Resource reference navigator"),
            AgentIdea("Code and resources", "Find usages across project files"),
            AgentIdea("Code and resources", "Side-by-side text diff viewer"),
            AgentIdea("Code and resources", "Smali method and class browser"),
            AgentIdea("Code and resources", "Bulk string resource editor"),
            AgentIdea("Code and resources", "JSON and properties editor"),
            AgentIdea("Code and resources", "Syntax validation before saving"),
            AgentIdea("Visual editing", "Visual XML layout preview"),
            AgentIdea("Visual editing", "Color palette extraction from APK"),
            AgentIdea("Visual editing", "Drawable and vector preview gallery"),
            AgentIdea("Visual editing", "App icon crop and adaptive icon editor"),
            AgentIdea("Visual editing", "Typography and font resource manager"),
            AgentIdea("Visual editing", "Button and component style presets"),
            AgentIdea("Visual editing", "Animation XML preview"),
            AgentIdea("Visual editing", "Screen density and dimension inspector"),
            AgentIdea("Visual editing", "Theme contrast and accessibility checker"),
            AgentIdea("Visual editing", "Before-and-after UI comparison"),
            AgentIdea("Automation", "One-tap decode and open workspace"),
            AgentIdea("Automation", "Batch rename selected resources"),
            AgentIdea("Automation", "Batch image conversion"),
            AgentIdea("Automation", "Saved repeatable patch workflows"),
            AgentIdea("Automation", "Automated manifest audit"),
            AgentIdea("Automation", "Automated XML formatting"),
            AgentIdea("Automation", "Find and replace across selected files"),
            AgentIdea("Automation", "Scheduled local project backups"),
            AgentIdea("Automation", "Bulk APK metadata export"),
            AgentIdea("Automation", "Custom action shortcuts"),
            AgentIdea("Build and signing", "Build progress dashboard"),
            AgentIdea("Build and signing", "Build log search and filtering"),
            AgentIdea("Build and signing", "APK size comparison before and after"),
            AgentIdea("Build and signing", "Signing certificate inspector"),
            AgentIdea("Build and signing", "APK alignment verification"),
            AgentIdea("Build and signing", "Install and launch rebuilt APK"),
            AgentIdea("Build and signing", "Build failure explanation assistant"),
            AgentIdea("Build and signing", "Build profiles for debug and release"),
            AgentIdea("Build and signing", "Export build reports"),
            AgentIdea("Build and signing", "Verify package and version changes"),
            AgentIdea("Security and privacy", "Local API key vault with key masking"),
            AgentIdea("Security and privacy", "Permission risk classification"),
            AgentIdea("Security and privacy", "Suspicious manifest component review"),
            AgentIdea("Security and privacy", "APK certificate and signature history"),
            AgentIdea("Security and privacy", "Network security configuration viewer"),
            AgentIdea("Security and privacy", "Private project file exclusion rules"),
            AgentIdea("Security and privacy", "Review AI data before sending"),
            AgentIdea("Security and privacy", "Local change backups and rollback"),
            AgentIdea("Security and privacy", "Exportable privacy activity report"),
            AgentIdea("Security and privacy", "Unsafe file path protection checks"),
            AgentIdea("Productivity", "Global command palette"),
            AgentIdea("Productivity", "Keyboard shortcuts for common actions"),
            AgentIdea("Productivity", "Recently opened files"),
            AgentIdea("Productivity", "Split view for two source files"),
            AgentIdea("Productivity", "Quick notes attached to projects"),
            AgentIdea("Productivity", "Task checklist for each APK project"),
            AgentIdea("Productivity", "Pin favorite tools to workspace"),
            AgentIdea("Productivity", "Customizable editor panels"),
            AgentIdea("Productivity", "Searchable help and feature guide"),
            AgentIdea("Productivity", "Undo and redo history for edits"),
            AgentIdea("Power tools", "APK and split APK package inspector"),
            AgentIdea("Power tools", "DEX and class reference explorer"),
            AgentIdea("Power tools", "Native library inventory"),
            AgentIdea("Power tools", "Resource size and usage report"),
            AgentIdea("Power tools", "Compare manifest changes between builds"),
            AgentIdea("Power tools", "Export selected project files as ZIP"),
            AgentIdea("Power tools", "Deep link and intent filter viewer"),
            AgentIdea("Power tools", "App component launch tester"),
            AgentIdea("Power tools", "Plugin-ready extension architecture"),
            AgentIdea("Power tools", "Custom dashboard with configurable panels")
        )
    }
}
