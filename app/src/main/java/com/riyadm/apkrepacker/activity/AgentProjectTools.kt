package com.riyadm.apkrepacker.activity

import android.content.Context
import com.riyadm.apkrepacker.apktool.ApktoolEngine
import com.riyadm.apkrepacker.apktool.ApktoolLogListener
import com.riyadm.apkrepacker.apktool.BuildOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.logging.Level

/** Local capability-limited tools for a selected decoded APK project. */
internal class AgentProjectTools(context: Context, projectDirectory: File) {
    private val appContext = context.applicationContext
    val project: File = projectDirectory.canonicalFile
    val sessionId = UUID.randomUUID().toString()
    private val projectKey = MessageDigest.getInstance("SHA-256").digest(project.absolutePath.toByteArray())
        .take(12).joinToString("") { "%02x".format(it) }
    private val backupRoot = File(appContext.filesDir, "agent-backups/$projectKey/$sessionId")
    private val backedUp = linkedSetOf<String>()
    private val ignoredDirs = setOf(".git", ".gradle", "build", "tmp", "temp", "node_modules", ".idea")
    private val textExtensions = setOf("xml","smali","json","txt","properties","yml","yaml","html","htm","css","js","java","kt","gradle","pro","cfg","ini","md")

    init { require(project.isDirectory && project.canRead()) { "Selected APK project is not readable." } }

    fun definitions(): JSONArray {
        val tools = JSONArray()
        tools.put(function("inspect_project","REQUIRED FIRST STEP. Inspect the real selected APK project root, actual folders, manifest, resources, file counts and likely GUI entry points. Never guess folder names.",
            objSchema(JSONObject(),JSONArray())))
        tools.put(function("find_gui_files","Find actual GUI files: layout XML, themes, colors, dimensions, drawables, menus, Compose sources, WebView assets and Activity/Smali entry points. Returns ranked exact paths.",
            objSchema(JSONObject().put("task_hint",str("Optional task description such as main screen, settings screen or button colors.")),JSONArray())))
        tools.put(function("list_project_files","List source/resource files under a project folder. Empty path means project root. If a guessed folder is missing, use returned real folders and suggestions.",
            schema("path","Relative folder path; empty string means project root.")))
        tools.put(function("search_project_files","Search text content across decoded APK files. Returns bounded matching paths and line excerpts.",
            schema("query","Text, resource name, class, method, or identifier to search for.")))
        tools.put(function("read_project_file","Read a specific project text file with line numbers. Read only files needed for the task.",
            objSchema(JSONObject().put("path",str("Project-relative file path."))
                .put("start_line",JSONObject().put("type","integer").put("description","1-based start line, defaults to 1."))
                .put("end_line",JSONObject().put("type","integer").put("description","1-based end line, defaults to 250.")), JSONArray().put("path"))))
        tools.put(function("apply_file_patch","Replace one exact unique text range in an existing file. Read it first. Repeat this tool for multi-file edits.",
            objSchema(JSONObject().put("path",str("Project-relative existing text file path."))
                .put("old_text",str("Exact existing text to replace; it must occur exactly once."))
                .put("new_text",str("Replacement text.")), JSONArray().put("path").put("old_text").put("new_text"))))
        tools.put(function("apply_multi_file_patches","Apply exact patches to multiple existing files in one transaction. All paths and old text are validated before any file changes; if validation fails, nothing is changed.",
            objSchema(JSONObject().put("patches",JSONObject().put("type","array").put("items",
                JSONObject().put("type","object").put("properties",
                    JSONObject().put("path",str("Project-relative file path."))
                        .put("old_text",str("Exact unique text to replace."))
                        .put("new_text",str("Replacement text."))
                ).put("required",JSONArray().put("path").put("old_text").put("new_text")))),
                JSONArray().put("patches"))))
        tools.put(function("create_project_file","Create a new text file. Existing files are never overwritten.",
            objSchema(JSONObject().put("path",str("New project-relative file path."))
                .put("content",str("Complete new file content.")), JSONArray().put("path").put("content"))))
        tools.put(function("delete_project_file","Delete a project text file. This is destructive and requires user approval; a backup is created first.",
            schema("path","Project-relative file path to delete.")))
        tools.put(function("build_project","Build the selected decoded APK with apktool into an unsigned APK. Does not sign or install.",
            objSchema(JSONObject().put("force",JSONObject().put("type","boolean").put("description","Force a full rebuild; normally false.")), JSONArray().put("force"))))
        tools.put(function("rollback_last_changes","Restore files changed in this Agent session from their pre-edit backups and remove files created by this session.",
            objSchema(JSONObject(), JSONArray())))
        return tools
    }

    private fun str(description: String) = JSONObject().put("type","string").put("description",description)
    private fun schema(key: String, description: String) =
        objSchema(JSONObject().put(key,str(description)), JSONArray().put(key))
    private fun objSchema(properties: JSONObject, required: JSONArray) =
        JSONObject().put("type","object").put("properties",properties).put("required",required).put("additionalProperties",false)
    private fun function(name: String, description: String, parameters: JSONObject) =
        JSONObject().put("type","function").put("function",JSONObject()
            .put("name",name).put("description",description).put("parameters",parameters))

    fun requiresApproval(name: String, args: JSONObject): String? {
        val path = args.optString("path","")
        return when {
            name == "delete_project_file" -> "Delete '$path'? A backup will be retained for rollback."
            name == "apply_multi_file_patches" -> {
                val patches = args.optJSONArray("patches") ?: JSONArray()
                val manifestPath = (0 until patches.length()).mapNotNull { patches.optJSONObject(it)?.optString("path") }
                    .firstOrNull { it.equals("AndroidManifest.xml",true) || it.contains("manifest",true) }
                if (manifestPath != null) "The Agent wants to change manifest-related file '$manifestPath'. Review before applying." else null
            }
            (name == "apply_file_patch" || name == "create_project_file") &&
                path.equals("AndroidManifest.xml",true) ->
                "The Agent wants to change AndroidManifest.xml. Review this change before applying it."
            (name == "apply_file_patch" || name == "create_project_file") &&
                path.contains("manifest",true) ->
                "The Agent wants to change a manifest-related file. Review this change before applying it."
            else -> null
        }
    }

    fun execute(name: String, args: JSONObject): String = try {
        when(name) {
            "inspect_project" -> inspectProject()
            "find_gui_files" -> findGuiFiles(args.optString("task_hint",""))
            "list_project_files" -> listFiles(args.optString("path",""))
            "search_project_files" -> searchFiles(args.optString("query",""))
            "read_project_file" -> readFile(args.optString("path",""),args.optInt("start_line",1),args.optInt("end_line",250))
            "apply_file_patch" -> applyPatch(args.optString("path",""),args.optString("old_text",""),args.optString("new_text",""))
        "apply_multi_file_patches" -> applyMultiFilePatches(args.optJSONArray("patches") ?: JSONArray())
            "create_project_file" -> createFile(args.optString("path",""),args.optString("content",""))
            "delete_project_file" -> deleteFile(args.optString("path",""))
            "build_project" -> buildProject(args.optBoolean("force",false))
            "rollback_last_changes" -> rollback()
            else -> error("Unknown Agent tool: $name")
        }
    } catch (e: Exception) {
        val requested = args.optString("path","")
        val rootDirs = project.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
        JSONObject().put("ok",false).put("error",e.message ?: e.javaClass.simpleName)
            .put("requested_path",requested).put("actual_project_root",project.absolutePath)
            .put("actual_root_directories",JSONArray(rootDirs))
            .put("recovery","Do not stop. Call inspect_project or list_project_files with empty path, then retry using exact returned paths.")
            .toString()
    }

    private fun target(relative: String): File {
        require(relative.isNotBlank()) { "A project-relative path is required." }
        require(!relative.startsWith("/") && !relative.startsWith("\\") && !Regex("^[A-Za-z]:").containsMatchIn(relative)) {
            "Absolute paths are not allowed."
        }
        val file = File(project,relative).canonicalFile
        require(file.toPath().startsWith(project.toPath()) && file != project) { "Rejected path outside the selected project." }
        return file
    }

    private fun isText(file: File) = file.isFile && file.length() <= 2*1024*1024 &&
        file.extension.lowercase() in textExtensions && !file.name.endsWith(".apk",true) && !file.name.endsWith(".dex",true)

    private fun allTextFiles(): Sequence<File> = project.walkTopDown()
        .onEnter { d -> d == project || (!d.name.startsWith(".") && d.name !in ignoredDirs) }
        .filter { isText(it) }

    private fun inspectProject(): String {
        val directories = JSONArray()
        project.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val children = dir.listFiles().orEmpty()
            directories.put(JSONObject().put("name",dir.name)
                .put("direct_files",children.count { it.isFile })
                .put("subdirectories",JSONArray(children.filter { it.isDirectory }.map { it.name }.sorted())))
        }
        val important = listOf("AndroidManifest.xml","apktool.yml","res/values/strings.xml",
            "res/values/styles.xml","res/values/themes.xml").filter { File(project,it).isFile }
        val count = allTextFiles().take(6001).count()
        return JSONObject().put("ok",true).put("project_name",project.name)
            .put("project_root",project.absolutePath).put("readable",project.canRead())
            .put("root_directories",directories).put("important_files",JSONArray(important))
            .put("readable_text_files",count).put("count_capped",count > 6000)
            .put("gui_candidates",guiCandidates(""))
            .put("note","Decoded APKs commonly use res/layout XML, res/values theme/color XML, drawable assets and smali classes. Original Kotlin/Java source may not exist.")
            .toString()
    }

    private fun findGuiFiles(hint: String): String =
        JSONObject().put("ok",true).put("task_hint",hint).put("project_root",project.absolutePath)
            .put("candidates",guiCandidates(hint))
            .put("instruction","Read exact candidate files before editing. Use returned paths; do not invent MainActivity.kt.")
            .toString()

    private fun guiCandidates(hint: String): JSONArray {
        val tokens = hint.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        val ranked = allTextFiles().take(6000).mapNotNull { file ->
            val path = file.relativeTo(project).invariantSeparatorsPath
            val lower = path.lowercase()
            var score = 0
            val reasons = mutableListOf<String>()
            when {
                lower == "androidmanifest.xml" -> { score += 80; reasons.add("Manifest declares app activities") }
                lower.startsWith("res/layout") && lower.endsWith(".xml") -> { score += 100; reasons.add("Android view layout") }
                lower.startsWith("res/values") && lower.endsWith(".xml") &&
                    listOf("theme","style","color","dimens","attr").any { lower.contains(it) } ->
                    { score += 90; reasons.add("Theme, color, style or dimension resources") }
                lower.startsWith("res/drawable") || lower.startsWith("res/mipmap") ->
                    { score += 45; reasons.add("Drawable or app icon resources") }
                lower.startsWith("res/menu") -> { score += 60; reasons.add("Menu/navigation resources") }
                lower.startsWith("assets/") && listOf(".html",".css",".js").any { lower.endsWith(it) } ->
                    { score += 70; reasons.add("WebView interface asset") }
                lower.contains("mainactivity") || lower.contains("launcheractivity") ->
                    { score += 110; reasons.add("Likely main/launcher Activity") }
                lower.endsWith(".kt") || lower.endsWith(".java") ->
                    { score += 65; reasons.add("Original source code") }
                lower.endsWith(".smali") -> { score += 30; reasons.add("Decoded Smali class") }
            }
            tokens.forEach { token -> if (lower.contains(token)) { score += 20; reasons.add("Matches task keyword " + token) } }
            if (score == 0) null else Triple(path,score,reasons.distinct())
        }.sortedByDescending { it.second }.take(100)
        val result = JSONArray()
        ranked.forEach { (path,score,reasons) ->
            result.put(JSONObject().put("path",path).put("score",score).put("reasons",JSONArray(reasons)))
        }
        return result
    }

    private fun listFiles(relative: String): String {
        val folder = if (relative.isBlank()) project else target(relative)
        require(folder.isDirectory && folder.canRead()) { "Folder not found or unreadable: $relative" }
        val found = folder.walkTopDown().onEnter { d -> d == folder || (!d.name.startsWith(".") && d.name !in ignoredDirs) }
            .filter { isText(it) }.take(250).map { it.relativeTo(project).invariantSeparatorsPath }.toList()
        return JSONObject().put("folder",if(relative.isBlank()) "." else relative)
            .put("files",JSONArray(found)).put("truncated",found.size >= 250).toString()
    }

    private fun searchFiles(query: String): String {
        require(query.isNotBlank()) { "Enter a search query." }
        val matches = JSONArray()
        var count = 0
        for (file in allTextFiles().take(1200)) {
            if (count >= 40) break
            val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: continue
            for ((index,line) in lines.withIndex()) if (line.contains(query,true)) {
                matches.put(JSONObject().put("path",file.relativeTo(project).invariantSeparatorsPath)
                    .put("line",index+1).put("text",line.take(360)))
                count++
                if (count >= 40) break
            }
        }
        return JSONObject().put("query",query).put("matches",matches).put("truncated",count>=40).toString()
    }

    private fun readFile(relative: String, start: Int, end: Int): String {
        val file = target(relative)
        require(isText(file)) { "File is missing, binary, or larger than 2 MB: $relative" }
        val lines = file.readLines(Charsets.UTF_8)
        val from = (start.coerceAtLeast(1)-1).coerceAtMost(lines.size)
        val until = end.coerceAtLeast(start).coerceAtMost(from+300).coerceAtMost(lines.size)
        val body = (from until until).joinToString("\n") { i -> "${i+1}: ${lines[i]}" }
        return JSONObject().put("path",relative).put("total_lines",lines.size)
            .put("start_line",from+1).put("content",body).toString()
    }

    private fun backup(file: File) {
        val relative = file.relativeTo(project).invariantSeparatorsPath
        if (!backedUp.add(relative)) return
        val copy = File(backupRoot,relative)
        copy.parentFile?.mkdirs()
        file.copyTo(copy,true)
        File(backupRoot,".changed").apply { parentFile?.mkdirs(); appendText(relative+"\n") }
    }

    private fun atomicWrite(file: File, content: String) {
        val temp = File(file.parentFile,file.name+".agent-tmp")
        temp.writeText(content,Charsets.UTF_8)
        if (!temp.renameTo(file)) { file.writeText(content,Charsets.UTF_8); temp.delete() }
    }

    private fun applyPatch(relative: String, oldText: String, newText: String): String {
        require(oldText.isNotEmpty() && oldText != newText) { "Patch must contain a non-empty old_text and a different new_text." }
        val file = target(relative)
        require(isText(file)) { "Target must be an existing readable text file under 2 MB." }
        val before = file.readText(Charsets.UTF_8)
        val at = before.indexOf(oldText)
        require(at >= 0) { "Patch context was not found in $relative. Read the latest file and retry." }
        require(before.indexOf(oldText,at+1) < 0) { "Patch context is not unique in $relative. Include more surrounding lines." }
        backup(file)
        val after = before.replaceRange(at,at+oldText.length,newText)
        atomicWrite(file,after)
        return JSONObject().put("ok",true).put("path",relative).put("changed_chars",kotlin.math.abs(after.length-before.length)).toString()
    }

    private data class AgentPatchPlan(val path: String, val file: File, val before: String, val after: String)

    private fun applyMultiFilePatches(patches: JSONArray): String {
        require(patches.length() in 1..20) { "Provide between 1 and 20 file patches." }
        val plans = mutableListOf<AgentPatchPlan>()
        val seen = hashSetOf<String>()
        for (i in 0 until patches.length()) {
            val item = patches.optJSONObject(i) ?: error("Patch item " + i + " is invalid.")
            val relative = item.optString("path")
            val oldText = item.optString("old_text")
            val newText = item.optString("new_text")
            require(oldText.isNotEmpty() && oldText != newText) { "Patch for " + relative + " must contain different non-empty old_text and new_text." }
            val file = target(relative)
            require(isText(file)) { "Patch target is not a readable text file: " + relative }
            val normalized = file.relativeTo(project).invariantSeparatorsPath
            require(seen.add(normalized)) { "Only one patch per file is allowed in one transaction. Combine patches for " + normalized + "." }
            val before = file.readText(Charsets.UTF_8)
            val at = before.indexOf(oldText)
            require(at >= 0) { "Patch context not found in " + normalized + ". Read the latest file and retry; no files have been changed." }
            require(before.indexOf(oldText, at + 1) < 0) { "Patch context is not unique in " + normalized + ". Add more surrounding lines; no files have been changed." }
            plans.add(AgentPatchPlan(normalized, file, before, before.replaceRange(at, at + oldText.length, newText)))
        }
        plans.forEach { backup(it.file) }
        try {
            plans.forEach { atomicWrite(it.file, it.after) }
        } catch (e: Exception) {
            plans.forEach { runCatching { atomicWrite(it.file, it.before) } }
            throw IllegalStateException("Multi-file patch failed; transaction restored. " + (e.message ?: ""), e)
        }
        return JSONObject().put("ok", true).put("files_changed", plans.size)
            .put("paths", JSONArray(plans.map { it.path })).toString()
    }

    private fun createFile(relative: String, content: String): String {
        require(content.length <= 512*1024) { "New file exceeds 512 KB." }
        val file = target(relative)
        require(!file.exists()) { "File already exists; read it and use apply_file_patch." }
        file.parentFile?.mkdirs()
        atomicWrite(file,content)
        File(backupRoot,".created").apply { parentFile?.mkdirs(); appendText(relative+"\n") }
        return JSONObject().put("ok",true).put("created",relative).toString()
    }

    private fun deleteFile(relative: String): String {
        val file = target(relative)
        require(isText(file)) { "Only readable text files can be deleted." }
        backup(file)
        require(file.delete()) { "Could not delete $relative" }
        return JSONObject().put("ok",true).put("deleted",relative).toString()
    }

    private fun buildProject(force: Boolean): String {
        val output = File(appContext.cacheDir,"agent-build-$projectKey-${System.currentTimeMillis()}.apk")
        val log = StringBuilder()
        val listener = ApktoolLogListener { level: Level, message: String ->
            if (log.length < 18000) log.append(ApktoolLogListener.format(level,message)).append('\n')
        }
        return try {
            ApktoolEngine.build(appContext,project,output,BuildOptions(force=force,sign=false,removeSplitRequirement=false),listener)
            JSONObject().put("success",true).put("signed",false).put("apk_path",output.absolutePath)
                .put("apk_bytes",output.length()).put("log",log.toString()).toString()
        } catch(e: Exception) {
            JSONObject().put("success",false).put("error",e.message ?: e.javaClass.simpleName).put("log",log.toString()).toString()
        }
    }

    private fun rollback(): String {
        val changedFile = File(backupRoot,".changed")
        val changed = if(changedFile.isFile) changedFile.readLines().filter{it.isNotBlank()}.distinct() else emptyList()
        var restored = 0
        for(relative in changed) {
            val copy = File(backupRoot,relative).canonicalFile
            require(copy.toPath().startsWith(backupRoot.canonicalFile.toPath())) { "Invalid backup entry." }
            if(!copy.isFile) continue
            val file = target(relative)
            file.parentFile?.mkdirs()
            copy.copyTo(file,true)
            restored++
        }
        val createdFile = File(backupRoot,".created")
        val created = if(createdFile.isFile) createdFile.readLines().filter{it.isNotBlank()}.distinct() else emptyList()
        var removed = 0
        for(relative in created) { val file=target(relative); if(file.isFile && file.delete()) removed++ }
        return JSONObject().put("success",true).put("restored_files",restored).put("removed_created_files",removed).toString()
    }
}
