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
import kotlin.coroutines.resume

private data class AgentIdea(val category: String, val title: String)

class MeghrajAiActivity : BaseActivity() {
    private lateinit var project: File
    private lateinit var task: TextInputEditText
    private lateinit var status: TextView
    private lateinit var run: MaterialButton
    private lateinit var conversation: LinearLayout
    private lateinit var providerSummary: TextView
    private lateinit var profileSwitcher: Spinner
    private lateinit var pageScroll: ScrollView
    private var proposed: JSONObject? = null
    private var projectTools: AgentProjectTools? = null
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
            text = "Describe a change in plain language. Agent explores and edits the selected APK project, then builds it. Risky actions require approval."
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
        content.addView(actions, marginParams(bottom = 8))
        content.addView(MaterialButton(this).apply {
            text = "Undo Agent changes"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { rollbackAgentChanges() }
        }, marginParams(bottom = 12))

        val switchCard = card(color(com.google.android.material.R.attr.colorSurfaceContainerLow, surface))
        val switchBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        switchBody.addView(TextView(this).apply {
            text = "QUICK SWITCH · SAVED API PROFILES"
            textSize = 10f
            letterSpacing = 0.09f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, onSurface))
        })
        profileSwitcher = Spinner(this)
        switchBody.addView(profileSwitcher, LinearLayout.LayoutParams(-1, dp(48)))
        profileSwitcher.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position <= 0) return
                val profile = AiConfig.getProfiles(this@MeghrajAiActivity).getOrNull(position - 1) ?: return
                if (AiConfig.getActiveProfileId(this@MeghrajAiActivity) != profile.id) {
                    AiConfig.activateProfile(this@MeghrajAiActivity, profile.id)
                    refreshProviderSummary()
                    status.text = "Switched to " + profile.name + " · " + profile.model
                }
            }
        }
        switchCard.addView(switchBody)
        content.addView(switchCard, marginParams(bottom = 12))

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
        if (::profileSwitcher.isInitialized) {
            val profiles = AiConfig.getProfiles(this)
            val labels = mutableListOf("Choose a saved API profile…")
            labels.addAll(profiles.map { it.name + "  ·  " + it.provider + " / " + it.model })
            profileSwitcher.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val selected = profiles.indexOfFirst { it.id == activeId }
            profileSwitcher.setSelection(if (selected >= 0) selected + 1 else 0)
        }
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
        status.text = if (editRequest) "Starting coding agent · exploring selected APK project..." else "Thinking · no project files are sent for normal chat..."
        lifecycleScope.launch {
            try {
                if (editRequest) {
                    val answer = runCodingAgent(token, modelId, provider, baseUrl, instruction)
                    addMessage("agent", answer)
                    status.text = "Task finished · review the activity log and rebuild result."
                } else {
                    val answer = withContext(Dispatchers.IO) {
                        requestAi(token, modelId, provider, baseUrl, instruction, "", false)
                    }
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

    /**
     * Tool-calling loop: model -> local project tool -> tool result -> model.
     * The model never receives the whole project. It discovers and reads files on demand.
     */
    private suspend fun runCodingAgent(
        token: String, modelId: String, provider: String, baseUrl: String, instruction: String
    ): String {
        val supportedProviders = setOf(
            "OpenAI", "OpenRouter", "DeepSeek", "NVIDIA NIM", "Groq", "Mistral",
            "Together AI", "Fireworks AI", "Cerebras", "AgentRouter", "Custom", "Anthropic", "Gemini"
        )
        require(provider in supportedProviders) { "Tool calling is not configured for provider " + provider + "." }
        val runtime = projectTools ?: AgentProjectTools(this, project).also { projectTools = it }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content",
            """You are a coding agent operating on the user's selected, decoded Android APK project.
You have local tools that inspect and edit the actual files. Do not return code for the user to copy.
For every requested project change, FIRST call inspect_project. Then call find_gui_files with the user's task description. Use only exact paths returned by those tools; never assume a MainActivity.kt, res/layout or any folder exists. If any tool returns ok=false or a path error, do not stop or tell the user you lack access: inspect the returned root_directories/path suggestions, list the real root with an empty path, and continue. Search for visible labels, resource IDs, launcher Activity declarations and layout references; read the exact candidate files in 300-line chunks, continuing with start_line/end_line when has_more is true. For a GUI color change, inspect layout XML plus values/colors.xml, styles.xml/themes.xml and relevant drawable resources before choosing the source of the color. Then apply focused edits directly to the decoded project, across all required files, and rebuild. Never return code for the user to copy.
After edits, inspect relevant changed files and call build_project to compile the APK. If the build fails, analyze the actual errors, make a focused fix, and retry (maximum 2 build attempts). Never claim a build succeeded unless the build tool confirms it.
This is HYBRID mode: safe source/resource edits may be applied automatically. The app asks the user before manifest edits or file deletion. Do not add permissions, change package identity, weaken app security, or alter signing settings unless the user explicitly requested it. Never read or expose API keys, private signing keys, credentials, or unrelated personal files.
Only work inside the selected project. Use rollback_last_changes if you need to undo your own changes. Keep tool calls focused and final report concise. The APK is rebuilt unsigned; do not claim it is installed or signed."""
        ))
        messages.put(JSONObject().put("role","user").put("content",
            "Selected project: " + project.name + "\nTask: " + instruction +
            "\nBegin by exploring the project and then execute the requested change."))
        val toolDefinitions = runtime.definitions()
        var completedToolCalls = 0
        var buildAttempts = 0
        while (completedToolCalls < 24) {
            status.text = "Agent thinking · step " + (completedToolCalls + 1) + " · " + provider
            val assistant = withContext(Dispatchers.IO) {
                requestToolTurn(token, modelId, provider, baseUrl, messages, toolDefinitions)
            }
            messages.put(assistant)
            val calls = assistant.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                val answer = assistant.optString("content","").trim()
                return answer.ifBlank { "The model ended the task without a final summary." }
            }
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val callId = call.optString("id")
                val fn = call.optJSONObject("function") ?: error("The model returned an invalid tool call.")
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments","{}")) }
                    .getOrElse { error("Invalid arguments for $name: ${it.message}") }
                val summary = when(name) {
                    "inspect_project" -> "Mapping the selected APK project"
                    "find_gui_files" -> "Finding the actual GUI and Activity files"
                    "list_project_files" -> "Exploring project files"
                    "search_project_files" -> "Searching source and resources"
                    "read_project_file" -> "Reading " + args.optString("path")
                    "apply_file_patch" -> "Editing " + args.optString("path")
                    "apply_multi_file_patches" -> "Applying a multi-file change"
                    "create_project_file" -> "Creating " + args.optString("path")
                    "delete_project_file" -> "Deleting " + args.optString("path")
                    "build_project" -> "Building decoded APK"
                    "rollback_last_changes" -> "Rolling back Agent edits"
                    else -> "Running " + name
                }
                status.text = summary + "..."
                val approvalText = runtime.requiresApproval(name,args)
                val approvalDetails = if (approvalText != null) {
                    val preview = when (name) {
                        "apply_file_patch" -> "\n\nFile: " + args.optString("path") +
                            "\n\nBEFORE:\n" + args.optString("old_text").take(900) +
                            "\n\nAFTER:\n" + args.optString("new_text").take(900)
                        "apply_multi_file_patches" -> {
                            val patches = args.optJSONArray("patches") ?: JSONArray()
                            val names = (0 until patches.length()).mapNotNull { patches.optJSONObject(it)?.optString("path") }
                            "\n\nFiles in this transaction:\n" + names.joinToString("\n")
                        }
                        else -> "\n\nFile: " + args.optString("path")
                    }
                    approvalText + preview
                } else null
                val approved = if (approvalDetails != null) confirmAgentAction(approvalDetails) else true
                val result = if (!approved) {
                    JSONObject().put("approved",false).put("message","User declined this action. Do not retry the same action; continue safely or explain the limitation.").toString()
                } else if (name == "build_project" && buildAttempts >= 3) {
                    JSONObject().put("success",false).put("error","Agent build retry limit reached (3 attempts). Stop building and report the remaining errors.").toString()
                } else {
                    if (name == "build_project") buildAttempts++
                    withContext(Dispatchers.IO) { runtime.execute(name,args) }
                }
                completedToolCalls++
                if (name == "apply_file_patch" && approved) {
                    val oldText = args.optString("old_text").take(500)
                    val newText = args.optString("new_text").take(500)
                    addMessage("agent", "FILE DIFF · " + args.optString("path") +
                        "\n- " + oldText.replace("\n","\n- ") +
                        "\n+ " + newText.replace("\n","\n+ "))
                } else if (name == "apply_multi_file_patches" && approved) {
                    val paths = runCatching { JSONObject(result).optJSONArray("paths") }.getOrNull()
                    val names = paths?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
                    addMessage("agent", "MULTI-FILE PATCH APPLIED · " + names.size + " file(s)\n" + names.joinToString("\n"))
                } else if (runCatching { JSONObject(result).optBoolean("ok") }.getOrDefault(true).not()) {
                    val recovery = runCatching { JSONObject(result).optString("recovery") }.getOrDefault("")
                    addMessage("agent", "TOOL RECOVERY · " + runCatching { JSONObject(result).optString("error") }.getOrDefault("Tool error") +
                        if (recovery.isNotBlank()) "\n" + recovery else "")
                } else if (name == "inspect_project") {
                    val info = runCatching { JSONObject(result) }.getOrNull()
                    addMessage("agent", "PROJECT SCANNED · " + (info?.optInt("readable_text_files") ?: 0) +
                        " readable text files indexed. Root: " + (info?.optString("project_root") ?: project.name))
                } else if (name == "find_gui_files") {
                    val candidates = runCatching { JSONObject(result).optJSONArray("candidates") }.getOrNull()
                    val paths = candidates?.let { arr -> (0 until minOf(8,arr.length())).mapNotNull { arr.optJSONObject(it)?.optString("path") } }.orEmpty()
                    addMessage("agent", "GUI FILE DISCOVERY · " + paths.size + " top candidates\n" + paths.joinToString("\n"))
                } else if (name == "build_project") {
                    val buildResult = runCatching { JSONObject(result) }.getOrNull()
                    if (buildResult?.optBoolean("success") == true) {
                        addMessage("agent", "BUILD PASSED · unsigned APK created (" +
                            buildResult.optLong("apk_bytes") + " bytes).")
                    } else if (buildResult != null) {
                        addMessage("agent", "BUILD FAILED · " + buildResult.optString("error").take(700))
                    }
                } else if (name == "rollback_last_changes" && approved) {
                    addMessage("agent", "ROLLBACK · " + result)
                } else if (name == "delete_project_file" && approved) {
                    addMessage("agent", "FILE DELETED · " + args.optString("path") + " (backup retained).")
                }
                messages.put(JSONObject().put("role","tool").put("tool_call_id",callId).put("name",name).put("content",result))
                if (completedToolCalls >= 24) break
            }
            if (buildAttempts >= 3) {
                messages.put(JSONObject().put("role","system").put("content",
                    "Build retry limit reached. Stop building and report the remaining errors accurately."))
            }
        }
        return "The Agent reached its 24-tool safety limit. Review the activity log; any applied changes have session backups and can be rolled back."
    }

    private suspend fun confirmAgentAction(message: String): Boolean =
        kotlin.coroutines.suspendCoroutine { continuation ->
            MaterialAlertDialogBuilder(this)
                .setTitle("Agent approval required")
                .setMessage(message)
                .setNegativeButton("Cancel") { _, _ -> continuation.resume(false) }
                .setPositiveButton("Approve") { _, _ -> continuation.resume(true) }
                .setOnCancelListener { continuation.resume(false) }
                .show()
        }

    private fun requestToolTurn(
        token: String, modelId: String, provider: String, baseUrl: String,
        messages: JSONArray, tools: JSONArray
    ): JSONObject = when (provider) {
        "Anthropic" -> requestAnthropicToolTurn(token, modelId, baseUrl, messages, tools)
        "Gemini" -> requestGeminiToolTurn(token, modelId, baseUrl, messages, tools)
        else -> requestOpenAiToolTurn(token, modelId, provider, baseUrl, messages, tools)
    }

    private fun requestOpenAiToolTurn(
        token: String,
        modelId: String,
        provider: String,
        baseUrl: String,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val payload = JSONObject().put("model",modelId).put("messages",messages)
            .put("tools",tools).put("tool_choice","auto")
            .put("temperature",0.1).put("max_tokens",4096)
        val endpoint = baseUrl.trim().trimEnd('/') + "/chat/completions"
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.doOutput = true
        conn.setRequestProperty("Authorization","Bearer " + token)
        conn.setRequestProperty("Content-Type","application/json")
        conn.setRequestProperty("Accept","application/json")
        if (provider == "OpenRouter") {
            conn.setRequestProperty("HTTP-Referer","https://github.com/MeghrajDabhi/ApkRepacker-Revived")
            conn.setRequestProperty("X-Title","Agent APK Editor")
        }
        conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val response = (if(code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if(code !in 200..299) throw IllegalStateException(explainApiError(provider,code,response))
        val root = JSONObject(response)
        val choices = root.optJSONArray("choices") ?: error(provider + " returned no choices.")
        val choice = choices.optJSONObject(0) ?: error(provider + " returned an empty choices list.")
        val message = choice.optJSONObject("message") ?: error(provider + " returned no assistant message.")
        val calls = message.optJSONArray("tool_calls")
        if (calls != null && calls.length() > 0) return message
        val content = message.opt("content")
        if (content is String && content.isNotBlank()) return message
        val finish = choice.optString("finish_reason")
        if (finish == "length") error("The model hit its output-token limit during tool execution. Select a model with a larger output allowance.")
        error(provider + " returned neither a final answer nor tool calls. The selected model may not support function calling.")
    }
    
    private fun requestAnthropicToolTurn(
        token: String, modelId: String, baseUrl: String, messages: JSONArray, tools: JSONArray
    ): JSONObject {
        val system = messages.optJSONObject(0)?.optString("content").orEmpty()
        val apiMessages = JSONArray()
        for (i in 1 until messages.length()) {
            val message = messages.getJSONObject(i)
            when (message.optString("role")) {
                "user" -> apiMessages.put(JSONObject().put("role", "user").put("content", message.optString("content")))
                "assistant" -> {
                    val blocks = JSONArray()
                    message.optString("content").takeIf { it.isNotBlank() }?.let {
                        blocks.put(JSONObject().put("type", "text").put("text", it))
                    }
                    val calls = message.optJSONArray("tool_calls")
                    if (calls != null) for (j in 0 until calls.length()) {
                        val call = calls.getJSONObject(j)
                        val fn = call.getJSONObject("function")
                        blocks.put(JSONObject().put("type", "tool_use").put("id", call.optString("id"))
                            .put("name", fn.optString("name")).put("input", JSONObject(fn.optString("arguments", "{}"))))
                    }
                    apiMessages.put(JSONObject().put("role", "assistant").put("content", blocks))
                }
                "tool" -> apiMessages.put(JSONObject().put("role", "user").put("content", JSONArray().put(
                    JSONObject().put("type", "tool_result").put("tool_use_id", message.optString("tool_call_id"))
                        .put("content", message.optString("content")))))
            }
        }
        val anthropicTools = JSONArray()
        for (i in 0 until tools.length()) {
            val fn = tools.getJSONObject(i).getJSONObject("function")
            anthropicTools.put(JSONObject().put("name", fn.optString("name"))
                .put("description", fn.optString("description"))
                .put("input_schema", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object")))
        }
        val payload = JSONObject().put("model", modelId).put("max_tokens", 4096).put("system", system)
            .put("messages", apiMessages).put("tools", anthropicTools)
            .put("tool_choice", JSONObject().put("type", "auto"))
        val conn = URL(baseUrl.trim().trimEnd('/') + "/messages").openConnection() as HttpURLConnection
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
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException(explainApiError("Anthropic", code, response))
        val root = JSONObject(response)
        val blocks = root.optJSONArray("content") ?: error("Anthropic returned no content.")
        val calls = JSONArray()
        val text = StringBuilder()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> calls.put(JSONObject().put("id", block.optString("id")).put("type", "function")
                    .put("function", JSONObject().put("name", block.optString("name"))
                        .put("arguments", block.optJSONObject("input")?.toString() ?: "{}")))
            }
        }
        if (calls.length() == 0 && text.isBlank()) error("Anthropic returned neither text nor tool calls.")
        return JSONObject().put("role", "assistant").put("content", text.toString())
            .put("tool_calls", if (calls.length() > 0) calls else JSONObject.NULL)
    }

    private fun geminiSchema(source: JSONObject): JSONObject {
        val result = JSONObject()
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "additionalProperties") continue
            val value = source.opt(key)
            when (value) {
                is JSONObject -> {
                    if (key == "properties") {
                        val props = JSONObject()
                        val names = value.keys()
                        while (names.hasNext()) {
                            val name = names.next()
                            value.optJSONObject(name)?.let { props.put(name, geminiSchema(it)) }
                        }
                        result.put(key, props)
                    } else result.put(key, geminiSchema(value))
                }
                is JSONArray -> result.put(key, value)
                is String -> result.put(key, if (key == "type") value.uppercase() else value)
                else -> if (value != null) result.put(key, value)
            }
        }
        return result
    }

    private fun requestGeminiToolTurn(
        token: String, modelId: String, baseUrl: String, messages: JSONArray, tools: JSONArray
    ): JSONObject {
        val contents = JSONArray()
        var system = ""
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            when (message.optString("role")) {
                "system" -> system = message.optString("content")
                "user" -> contents.put(JSONObject().put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", message.optString("content")))))
                "assistant" -> {
                    val parts = JSONArray()
                    message.optString("content").takeIf { it.isNotBlank() }?.let {
                        parts.put(JSONObject().put("text", it))
                    }
                    val calls = message.optJSONArray("tool_calls")
                    if (calls != null) for (j in 0 until calls.length()) {
                        val fn = calls.getJSONObject(j).getJSONObject("function")
                        parts.put(JSONObject().put("functionCall", JSONObject().put("name", fn.optString("name"))
                            .put("args", JSONObject(fn.optString("arguments", "{}")))))
                    }
                    contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                "tool" -> contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(
                    JSONObject().put("functionResponse", JSONObject().put("name", message.optString("name"))
                        .put("response", JSONObject().put("result", message.optString("content")))))))
            }
        }
        val declarations = JSONArray()
        for (i in 0 until tools.length()) {
            val fn = tools.getJSONObject(i).getJSONObject("function")
            declarations.put(JSONObject().put("name", fn.optString("name"))
                .put("description", fn.optString("description"))
                .put("parameters", geminiSchema(fn.optJSONObject("parameters") ?: JSONObject().put("type", "object"))))
        }
        val payload = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 4096))
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
        val response = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException(explainApiError("Gemini", code, response))
        val root = JSONObject(response)
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0) ?: error("Gemini returned no candidate.")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: error("Gemini returned no parts.")
        val calls = JSONArray()
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            part.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it) }
            val call = part.optJSONObject("functionCall")
            if (call != null) calls.put(JSONObject().put("id", "gemini-call-" + i + "-" + System.currentTimeMillis())
                .put("type", "function").put("function", JSONObject().put("name", call.optString("name"))
                    .put("arguments", call.optJSONObject("args")?.toString() ?: "{}")))
        }
        if (calls.length() == 0 && text.isBlank()) error("Gemini returned neither text nor function calls.")
        return JSONObject().put("role", "assistant").put("content", text.toString())
            .put("tool_calls", if (calls.length() > 0) calls else JSONObject.NULL)
    }

    private fun rollbackAgentChanges() {
        val runtime = projectTools
        if (runtime == null) {
            status.text = "No Agent changes have been made in this session."
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Undo Agent changes?")
            .setMessage("Restore project files to their state before this Agent session's edits?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Undo changes") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            JSONObject(runtime.execute("rollback_last_changes", JSONObject()))
                        }
                        val summary = "Rollback complete · restored " + result.optInt("restored_files") +
                            " file(s), removed " + result.optInt("removed_created_files") + " created file(s)."
                        addMessage("agent", summary)
                        status.text = summary
                    } catch (e: Exception) {
                        status.text = "Rollback failed · " + (e.message ?: e.javaClass.simpleName)
                    }
                }
            }.show()
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
                    .put("maxOutputTokens", if (editRequest) 4096 else 256))
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
                .put("max_tokens", if (editRequest) 4096 else 256)
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
            lower.contains("this request requires more credits") ||
                lower.contains("openrouter_key_limit") || lower.contains("workspace budget") ||
                lower.contains("limit_source") ->
                "The API key was recognized, but its spend limit or available credits cannot cover this request. Check OpenRouter credits and the key/workspace usage limit. For a normal chat message, Agent now requests only 256 output tokens."
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
