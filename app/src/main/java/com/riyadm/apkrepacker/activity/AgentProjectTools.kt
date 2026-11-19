package com.riyadm.apkrepacker.activity

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
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
    // The selected project is the access boundary. Do not silently hide .git, hidden resource
    // folders, generated directories, or unfamiliar extensions from the agent's inventory.
    private val ignoredDirs = emptySet<String>()
    private val textExtensions = setOf(
        "xml","smali","json","txt","properties","yml","yaml","html","htm","css","js","java","kt",
        "gradle","pro","cfg","ini","md","kts","groovy","toml","sh","bat","cmd","ps1","py","rb",
        "php","ts","tsx","jsx","c","h","cpp","hpp","rs","go","swift","sql","csv","tsv","log",
        "conf","config","xmls","aidl","proto","svg","plist","entitlements","gitignore","gitattributes",
        "editorconfig","makefile","dockerfile","lock","properties"
    )

    init { require(project.isDirectory && project.canRead()) { "Selected APK project is not readable." } }

    fun definitions(): JSONArray {
        val tools = JSONArray()
        tools.put(function("inspect_project","REQUIRED FIRST STEP. Inspect the real selected APK project root, actual folders, manifest, resources, file counts and likely GUI entry points. Never guess folder names.",
            objSchema(JSONObject(),JSONArray())))
        tools.put(function("find_gui_files","Find actual GUI files: layout XML, themes, colors, dimensions, drawables, menus, Compose sources, WebView assets and Activity/Smali entry points. Returns ranked exact paths.",
            objSchema(JSONObject().put("task_hint",str("Optional task description such as main screen, settings screen or button colors.")),JSONArray())))
        tools.put(function("list_project_files","List actual directories and files under a project folder in small pages. Empty path means project root. Use next_offset until has_more=false. If a folder is missing, inspect the real root and use exact paths.",
            objSchema(JSONObject().put("path",str("Relative folder path; empty string means project root."))
                .put("offset",JSONObject().put("type","integer").put("description","Zero-based page offset; defaults to 0."))
                .put("limit",JSONObject().put("type","integer").put("description","Page size from 20 to 120; defaults to 80.")),JSONArray())))
        tools.put(function("search_project_files","Search text content across the selected project in bounded file batches. Use next_offset to continue the search through the entire project; one page is never the full result.",
            objSchema(JSONObject().put("query",str("Exact text, resource name, class, method or identifier to search for."))
                .put("offset",JSONObject().put("type","integer").put("description","Zero-based text-file scan offset from the project inventory; defaults to 0."))
                .put("limit",JSONObject().put("type","integer").put("description","Number of files to scan in this batch, 100 to 1000; defaults to 400.")),JSONArray().put("query"))))
        tools.put(function("lookup_android_docs","Fetch a relevant official Android developer reference page for Activity, Fragment, layout XML, resources, manifest, Compose or WebView guidance. This explains Android conventions; project-specific file locations must still be verified with local tools.",
            objSchema(JSONObject().put("topic",str("One of: activity, fragment, layout, resources, manifest, compose, webview, smali."))
                .put("query",str("Specific Android concept to look up in the documentation.")),JSONArray().put("topic"))))
        tools.put(function("read_project_file","Read a supported text file with line numbers in small chunks. The result includes has_more and next_start_line.",
            objSchema(JSONObject().put("path",str("Project-relative file path."))
                .put("start_line",JSONObject().put("type","integer").put("description","1-based start line, defaults to 1."))
                .put("end_line",JSONObject().put("type","integer").put("description","1-based end line, defaults to 80.")), JSONArray().put("path"))))
        tools.put(function("inspect_project_file","Inspect ANY file type, including binary APK resources, images, fonts, DEX, ARSC, archives and unknown extensions. Returns exact path, size, SHA-256 and a safe hex header.",
            objSchema(JSONObject().put("path",str("Exact project-relative file path from the inventory.")),JSONArray().put("path"))))
        tools.put(function("read_project_binary_chunk","Read the raw bytes of ANY binary file in a bounded Base64 chunk. First call inspect_project_file to get its size. Repeat with next_offset until complete. Never decode binary bytes as UTF-8.",
            objSchema(JSONObject().put("path",str("Exact project-relative file path."))
                .put("offset",JSONObject().put("type","integer").put("description","Zero-based byte offset."))
                .put("length",JSONObject().put("type","integer").put("description","Number of bytes, 1 to 8192; defaults to 4096.")),
                JSONArray().put("path").put("offset"))))
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
            "list_project_files" -> listFiles(args.optString("path",""),args.optInt("offset",0),args.optInt("limit",80))
            "search_project_files" -> searchFiles(args.optString("query",""),args.optInt("offset",0),args.optInt("limit",400))
            "lookup_android_docs" -> lookupAndroidDocs(args.optString("topic",""),args.optString("query",""))
            "read_project_file" -> readFile(args.optString("path",""),args.optInt("start_line",1),args.optInt("end_line",80))
            "inspect_project_file" -> inspectProjectFile(args.optString("path",""))
            "read_project_binary_chunk" -> readBinaryChunk(args.optString("path",""),args.optLong("offset",0L),args.optInt("length",4096))
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

    /**
     * Resolve relative paths and absolute paths already inside the selected workspace.
     * The model often repeats project_root from inspect_project; rejecting that safe
     * absolute path caused a misleading "folder not found" error. Canonical containment
     * still blocks ../ traversal and symlink escapes.
     */
    private fun target(rawPath: String): File {
        require(rawPath.isNotBlank()) { "A project path is required." }
        val cleaned = rawPath.trim().trim('`','"').replace('\\', '/').removePrefix("./")
        val rootPath = project.canonicalFile.toPath()
        val absolute = File(cleaned).isAbsolute || Regex("^[A-Za-z]:/").containsMatchIn(cleaned)
        if (absolute) {
            val file = File(cleaned).canonicalFile
            require(file.toPath() != rootPath && file.toPath().startsWith(rootPath)) {
                "Rejected path outside the selected project: $rawPath"
            }
            return file
        }
        val rawParts = cleaned.trim('/').split('/').filter { it.isNotBlank() && it != "." }
        require(rawParts.none { it == ".." }) { "Parent-directory traversal is not allowed: $rawPath" }
        val parts = if (rawParts.firstOrNull()?.equals(project.name,true) == true) rawParts.drop(1) else rawParts
        require(parts.isNotEmpty()) { "The project root is not a file. Use an exact child path." }
        var current = project
        parts.forEachIndexed { index, part ->
            val children = current.listFiles()
            val exact = children?.firstOrNull { it.name == part }
            val matched = exact ?: children?.filter { it.name.equals(part,true) }?.let { options ->
                when (options.size) {
                    0 -> null
                    1 -> options.first()
                    else -> error("Path segment '$part' is ambiguous. Use exact spelling: " + options.joinToString { it.name })
                }
            }
            if (matched != null) {
                require(!java.nio.file.Files.isSymbolicLink(matched.toPath())) {
                    "Symbolic-link paths are not followed."
                }
                current = matched
            } else if (index == parts.lastIndex) {
                // A missing final component is allowed for create_project_file; all its parents
                // must exist and be enumerable. Other tools validate existence/type afterwards.
                current = File(current,part)
            } else {
                val actual = children?.sortedBy { it.name.lowercase() }?.take(20)
                    ?.joinToString { (if (it.isDirectory) "[DIR] " else "") + it.name }
                    ?: "Android could not enumerate this parent directory."
                error("Folder/path '$part' was not found under '${current.relativeTo(project).invariantSeparatorsPath}'. " +
                    "Actual entries: $actual. Retry with the exact directory and file names.")
            }
        }
        val file = current.canonicalFile
        require(file.toPath() != rootPath && file.toPath().startsWith(rootPath)) {
            "Rejected path outside the selected project: $rawPath"
        }
        return file
    }

    private fun isText(file: File): Boolean {
        if (!file.isFile || file.length() > 8L*1024*1024) return false
        if (file.extension.lowercase() in textExtensions || file.name in setOf("Makefile","Dockerfile","Gemfile","Rakefile","LICENSE","NOTICE")) {
            val sample = ByteArray(4096)
            val count = runCatching { FileInputStream(file).use { it.read(sample) } }.getOrDefault(-1)
            return count >= 0 && (0 until count).none { sample[it] == 0.toByte() }
        }
        return false
    }

    private fun allFiles(): Sequence<File> = project.walkTopDown()
        .onEnter { d -> d == project || !java.nio.file.Files.isSymbolicLink(d.toPath()) }
        .filter { it.isFile }

    private fun allTextFiles(): Sequence<File> = allFiles().filter { isText(it) }

    private fun inspectProjectFile(relative: String): String {
        val file = target(relative)
        require(file.isFile && file.canRead()) { "File is missing or unreadable: $relative" }
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(64)
        var headerSize = 0
        FileInputStream(file).use { input ->
            val read = input.read(header)
            headerSize = if (read < 0) 0 else read
            digest.update(header,0,headerSize)
            val buffer = ByteArray(64*1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer,0,count)
            }
        }
        val hex = (0 until headerSize).joinToString(" ") { "%02X".format(header[it]) }
        val printable = (0 until headerSize).map { b ->
            val v = header[b].toInt() and 0xff
            if (v in 32..126) v.toChar() else '.'
        }.joinToString("")
        val imageBounds = if (file.extension.lowercase() in setOf("png","jpg","jpeg","webp","gif","bmp")) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { BitmapFactory.decodeFile(file.absolutePath,options) }
            JSONObject().put("width",options.outWidth).put("height",options.outHeight)
                .put("mime_type",options.outMimeType ?: JSONObject.NULL)
        } else JSONObject.NULL
        return JSONObject().put("ok",true).put("path",relative)
            .put("name",file.name).put("extension",file.extension.lowercase())
            .put("bytes",file.length()).put("readable",file.canRead())
            .put("text_candidate",isText(file)).put("sha256",digest.digest().joinToString("") { "%02x".format(it) })
            .put("header_hex",hex).put("header_ascii",printable).put("image",imageBounds)
            .put("note","Binary files are inventoried and fingerprinted without corrupting their bytes. Use a type-specific viewer or editor for binary modifications.")
            .toString()
    }

    private fun readBinaryChunk(relative: String, requestedOffset: Long, requestedLength: Int): String {
        val file = target(relative)
        require(file.isFile && file.canRead()) { "File is missing or unreadable: $relative" }
        val offset = requestedOffset.coerceAtLeast(0L)
        require(offset < file.length() || (offset == 0L && file.length() == 0L)) { "Byte offset is past end of file." }
        val length = minOf(requestedLength.coerceIn(1,8192).toLong(),(file.length()-offset).coerceAtLeast(0L)).toInt()
        val bytes = ByteArray(length)
        RandomAccessFile(file,"r").use { input ->
            input.seek(offset)
            input.readFully(bytes)
        }
        val next = offset + length
        return JSONObject().put("ok",true).put("path",relative).put("file_bytes",file.length())
            .put("offset",offset).put("bytes_returned",length).put("has_more",next < file.length())
            .put("next_offset",if(next < file.length()) next else JSONObject.NULL)
            .put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP))
            .put("note","Raw binary bytes encoded as Base64. Continue from next_offset; do not interpret this as text.")
            .toString()
    }

    private fun inspectProject(): String {
        val directories = JSONArray()
        val rootFiles = JSONArray()
        project.listFiles()?.sortedBy { it.name.lowercase() }?.forEach { child ->
            if (child.isDirectory) {
                val direct = child.listFiles()
                directories.put(JSONObject().put("name",child.name)
                    .put("readable",child.canRead() && direct != null)
                    .put("direct_files",direct?.count { it.isFile } ?: -1)
                    .put("subdirectories",JSONArray(direct?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty())))
            } else {
                rootFiles.put(JSONObject().put("name",child.name).put("bytes",child.length())
                    .put("text_candidate",isText(child)))
            }
        }
        val directoryPaths = JSONArray()
        project.walkTopDown().onEnter { d -> d == project || !java.nio.file.Files.isSymbolicLink(d.toPath()) }
            .filter { it.isDirectory && it != project }.take(150).forEach {
                directoryPaths.put(it.relativeTo(project).invariantSeparatorsPath)
            }
        val important = listOf("AndroidManifest.xml","apktool.yml","res/values/strings.xml",
            "res/values/styles.xml","res/values/themes.xml").filter { File(project,it).isFile }
        val count = allTextFiles().take(10001).count()
        val allEntries = allFiles().take(50001).count()
        val textSample = JSONArray()
        allTextFiles().take(80).forEach { textSample.put(it.relativeTo(project).invariantSeparatorsPath) }
        return JSONObject().put("ok",true).put("project_name",project.name)
            .put("project_root",project.absolutePath).put("project_exists",project.exists())
            .put("is_directory",project.isDirectory).put("readable",project.canRead())
            .put("root_directories",directories).put("root_files",rootFiles)
            .put("directory_paths",directoryPaths).put("important_files",JSONArray(important))
            .put("readable_text_files",count).put("count_capped",count > 10000)
            .put("total_project_files",allEntries).put("total_files_capped",allEntries > 50000)
            .put("hidden_files_included",true).put("generated_and_git_directories_included",true)
            .put("symlinks_followed",false)
            .put("has_android_manifest",File(project,"AndroidManifest.xml").isFile)
            .put("has_apktool_metadata",File(project,"apktool.yml").isFile)
            .put("has_resource_directory",File(project,"res").isDirectory)
            .put("has_smali_directory",project.listFiles()?.any { it.isDirectory && it.name.startsWith("smali") } == true)
            .put("text_file_sample",textSample).put("gui_candidates",guiCandidates(""))
            .put("note","Inventory includes hidden folders, .git, generated folders, every readable regular file type and binary assets. Symbolic links are not followed to prevent escaping the selected project. Use inspect_project_file for binary metadata and read_project_file for supported text files. File contents are only sent on demand in small chunks.")
            .toString()
    }

    private fun findGuiFiles(hint: String): String =
        JSONObject().put("ok",true).put("task_hint",hint).put("project_root",project.absolutePath)
            .put("android_ui_map",buildAndroidUiMap(hint))
            .put("candidates",guiCandidates(hint))
            .put("instruction","The android_ui_map traces manifest-declared Activities to exact source/Smali files and their referenced layout XML where detectable. Read only task-relevant paths. Do not guess MainActivity or any directory.")
            .toString()

    private fun buildAndroidUiMap(hint: String): JSONObject {
        val manifestFile = File(project,"AndroidManifest.xml")
        val manifestText = runCatching { if (manifestFile.isFile && manifestFile.length() <= 2*1024*1024) manifestFile.readText(Charsets.UTF_8) else "" }.getOrDefault("")
        val packageName = Regex("<manifest[^>]*\\bpackage=[\\\"']([^\\\"']+)",RegexOption.IGNORE_CASE)
            .find(manifestText)?.groupValues?.getOrNull(1).orEmpty()
        val activityBlocks = Regex("<activity(?:-alias)?\\b[\\s\\S]*?</activity(?:-alias)?>",RegexOption.IGNORE_CASE)
            .findAll(manifestText).map { it.value }.toList()
        val declarations = JSONArray()
        val launcherClassNames = linkedSetOf<String>()
        val activityNames = linkedSetOf<String>()
        for (block in activityBlocks) {
            val rawName = Regex("android:name=[\\\"']([^\\\"']+)[\\\"']",RegexOption.IGNORE_CASE)
                .find(block)?.groupValues?.getOrNull(1) ?: continue
            val fullName = when {
                rawName.startsWith(".") -> packageName + rawName
                !rawName.contains(".") && packageName.isNotBlank() -> "$packageName.$rawName"
                else -> rawName
            }
            val launcher = block.contains("android.intent.action.MAIN") &&
                block.contains("android.intent.category.LAUNCHER")
            activityNames.add(fullName)
            if (launcher) launcherClassNames.add(fullName)
            declarations.put(JSONObject().put("name",fullName).put("launcher",launcher))
        }
        val codeFiles = allFiles().filter {
            it.extension.lowercase() in setOf("smali","java","kt") && it.length() <= 2*1024*1024
        }.toList()
        val resolved = JSONArray()
        val referencedLayoutNames = linkedSetOf<String>()
        val importantCodePaths = linkedSetOf<String>()
        val namesToResolve = (launcherClassNames + activityNames).take(20)
        for (className in namesToResolve) {
            val classPath = className.replace('.','/').trimStart('/')
            val matches = codeFiles.filter { file ->
                val relative = file.relativeTo(project).invariantSeparatorsPath.removeSuffix("." + file.extension)
                relative.endsWith(classPath,true) ||
                    (file.extension.equals("smali",true) && relative.endsWith(classPath.replace('/','$'),true))
            }.take(3)
            for (file in matches) {
                val relative = file.relativeTo(project).invariantSeparatorsPath
                importantCodePaths.add(relative)
                val source = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                val refs = Regex("R\\.layout\\.([A-Za-z0-9_]+)|R\\$layout;->([A-Za-z0-9_]+):I")
                    .findAll(source).mapNotNull { it.groupValues.drop(1).firstOrNull { v -> v.isNotBlank() } }.toList()
                refs.forEach { referencedLayoutNames.add(it) }
                resolved.put(JSONObject().put("activity",className).put("path",relative)
                    .put("launcher",launcherClassNames.contains(className))
                    .put("referenced_layout_names",JSONArray(refs.distinct())))
            }
        }
        // If Activity source has R.layout references, resolve those symbols to exact res/layout[-qualifier] XML files.
        val layoutFiles = allTextFiles().filter { file ->
            val path = file.relativeTo(project).invariantSeparatorsPath.lowercase()
            path.startsWith("res/layout") && path.endsWith(".xml")
        }.toList()
        val layoutMatches = JSONArray()
        for (file in layoutFiles) {
            val base = file.nameWithoutExtension
            val related = referencedLayoutNames.contains(base)
            val hintMatch = hint.isNotBlank() && hint.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length > 2 }.any { base.contains(it,true) }
            if (related || hintMatch) {
                layoutMatches.put(JSONObject().put("path",file.relativeTo(project).invariantSeparatorsPath)
                    .put("resource_name",base).put("referenced_by_activity",related))
            }
        }
        val themeFiles = allTextFiles().filter {
            val path = it.relativeTo(project).invariantSeparatorsPath.lowercase()
            path.startsWith("res/values") && path.endsWith(".xml") &&
                listOf("color","style","theme","dimens","attr").any { word -> path.contains(word) }
        }.take(100).map { it.relativeTo(project).invariantSeparatorsPath }
        return JSONObject().put("manifest_found",manifestText.isNotBlank())
            .put("package_name",packageName).put("declared_activities",declarations)
            .put("resolved_activity_code",resolved).put("related_layout_files",layoutMatches)
            .put("important_activity_paths",JSONArray(importantCodePaths))
            .put("theme_resource_paths",JSONArray(themeFiles.toList()))
            .put("note","Activity names came from the decoded manifest. Code paths and layout XML are included only when the exact name/reference could be resolved. For Smali layouts hidden behind numeric IDs, use search_project_files for setContentView, R$layout or resource IDs and inspect res/values/public.xml.")
    }

    private fun guiCandidates(hint: String): JSONArray {
        val tokens = hint.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        val manifestText = runCatching { File(project,"AndroidManifest.xml").readText(Charsets.UTF_8) }.getOrDefault("")
        val launcherClasses = Regex("<activity\\b[\\s\\S]*?</activity>", RegexOption.IGNORE_CASE)
            .findAll(manifestText).filter { block ->
                block.value.contains("android.intent.action.MAIN") && block.value.contains("android.intent.category.LAUNCHER")
            }.mapNotNull { block ->
                Regex("android:name=[\\\"']([^\\\"']+)[\\\"']").find(block.value)?.groupValues?.getOrNull(1)
                    ?.substringAfterLast('.')
            }.toSet()
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
                lower.endsWith(".smali") && launcherClasses.any { lower.endsWith("/" + it.lowercase() + ".smali") || lower.endsWith(it.lowercase() + ".smali") } ->
                    { score += 140; reasons.add("Smali class declared as the MAIN/LAUNCHER Activity in AndroidManifest.xml") }
                lower.endsWith(".kt") || lower.endsWith(".java") ->
                    { score += 65; reasons.add("Original source code") }
                lower.endsWith(".smali") -> { score += 30; reasons.add("Decoded Smali class") }
            }
            tokens.forEach { token -> if (lower.contains(token)) { score += 20; reasons.add("Matches task keyword " + token) } }
            if (score == 0) null else Triple(path,score,reasons.distinct())
        }.sortedByDescending { it.second }.take(60)
        val result = JSONArray()
        ranked.forEach { (path,score,reasons) ->
            result.put(JSONObject().put("path",path).put("score",score).put("reasons",JSONArray(reasons)))
        }
        return result
    }

    private fun listFiles(relative: String, requestedOffset: Int, requestedLimit: Int): String {
        val folder = if (relative.isBlank() || relative == ".") project else target(relative)
        require(folder.exists() && folder.isDirectory && folder.canRead()) {
            "Folder not found or unreadable: '" + relative + "'. The selected project root is '" + project.absolutePath + "'. Call inspect_project and use one of its exact directory_paths."
        }
        val offset = requestedOffset.coerceAtLeast(0)
        val limit = requestedLimit.coerceIn(20,80)
        val rawChildren = folder.listFiles()
        require(rawChildren != null) {
            "Android could not enumerate this directory (permission denied or storage error): " + folder.absolutePath
        }
        val children = rawChildren.filter { child ->
            !java.nio.file.Files.isSymbolicLink(child.toPath())
        }.sortedWith(compareBy<File>({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase() }))
        val page = children.drop(offset).take(limit)
        val dirs = JSONArray()
        val files = JSONArray()
        val entries = JSONArray()
        page.forEach { child ->
            val path = child.relativeTo(project).invariantSeparatorsPath
            if (child.isDirectory) {
                val item = JSONObject().put("type","directory").put("name",child.name).put("path",path)
                dirs.put(item)
                entries.put(item)
            } else {
                val item = JSONObject().put("type","file").put("name",child.name).put("path",path)
                    .put("bytes",child.length()).put("extension",child.extension.lowercase())
                    .put("readable",child.canRead()).put("text_candidate",isText(child))
                    .put("kind",if (isText(child)) "text" else "binary_or_unknown")
                files.put(item)
                entries.put(item)
            }
        }
        val next = offset + page.size
        return JSONObject().put("ok",true).put("project_root",project.absolutePath)
            .put("folder",if(relative.isBlank() || relative == ".") "." else relative)
            .put("offset",offset).put("limit",limit).put("total_entries",children.size)
            .put("has_more",next < children.size).put("next_offset",if(next < children.size) next else JSONObject.NULL)
            .put("entries",entries).put("directories",dirs).put("files",files)
            .put("note","This is one page only. Continue with next_offset until has_more=false. Directory entries include non-text assets; read only text_candidate=true.")
            .toString()
    }

    private fun searchFiles(query: String, requestedOffset: Int, requestedLimit: Int): String {
        require(query.isNotBlank()) { "Enter a search query." }
        val offset = requestedOffset.coerceAtLeast(0)
        val limit = requestedLimit.coerceIn(100,1000)
        val matches = JSONArray()
        var scanned = 0
        for (file in allTextFiles().drop(offset).take(limit)) {
            scanned++
            runCatching {
                file.useLines(Charsets.UTF_8) { lines ->
                    var lineNumber = 0
                    lines.forEach { line ->
                        lineNumber++
                        if (matches.length() < 35 && line.contains(query,true)) {
                            matches.put(JSONObject().put("path",file.relativeTo(project).invariantSeparatorsPath)
                                .put("line",lineNumber).put("text",line.take(260)))
                        }
                    }
                }
            }
        }
        val next = offset + scanned
        val hasMore = scanned == limit
        return JSONObject().put("query",query).put("matches",matches)
            .put("files_scanned",scanned).put("scan_offset",offset).put("has_more",hasMore)
            .put("next_offset",if (hasMore) next else JSONObject.NULL)
            .put("note","The search is paginated over all readable text files. Continue with next_offset until has_more=false; do not treat one page as a complete search.")
            .toString()
    }

    private fun lookupAndroidDocs(topic: String, query: String): String {
        val key = topic.trim().lowercase()
        val pages = mapOf(
            "activity" to ("Activities" to "https://developer.android.com/guide/components/activities"),
            "fragment" to ("Fragments" to "https://developer.android.com/guide/fragments"),
            "layout" to ("Layout resources" to "https://developer.android.com/guide/topics/resources/layout-resource"),
            "resources" to ("App resources overview" to "https://developer.android.com/guide/topics/resources/providing-resources"),
            "manifest" to ("App manifest overview" to "https://developer.android.com/guide/topics/manifest/manifest-intro"),
            "compose" to ("Jetpack Compose documentation" to "https://developer.android.com/develop/ui/compose/documentation"),
            "webview" to ("Build web apps in WebView" to "https://developer.android.com/develop/ui/views/layout/webapps/webview"),
            "smali" to ("Apktool documentation" to "https://apktool.org/docs/")
        )
        val page = pages[key] ?: pages["resources"]!!
        var fetched = false
        var text = ""
        runCatching {
            val connection = URL(page.second).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 7000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent","ApkRepacker-Android-Agent/1.0")
            connection.setRequestProperty("Accept","text/html")
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText().take(900000) }
            connection.disconnect()
            val cleaned = html
                .replace(Regex("(?is)<script[^>]*>.*?</script>")," ")
                .replace(Regex("(?is)<style[^>]*>.*?</style>")," ")
                .replace(Regex("(?i)</(p|li|h[1-6]|tr|section|div)>"),"\\n")
                .replace(Regex("<[^>]+>")," ")
                .replace("&nbsp;"," ").replace("&amp;","&").replace("&lt;","<")
                .replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'")
                .replace(Regex("[ \\t]+")," ").replace(Regex("\\n{3,}"),"\\n\\n")
            val terms = (query.lowercase().split(Regex("[^a-z0-9]+")) + key)
                .filter { it.length > 2 }.distinct()
            val paragraphs = cleaned.split(Regex("\\n+")).map { it.trim() }.filter { it.length > 50 }
            val ranked = paragraphs.map { paragraph ->
                paragraph to terms.count { paragraph.contains(it,true) }
            }.sortedByDescending { it.second }
            text = ranked.filter { it.second > 0 }.take(7).joinToString("\\n\\n") { it.first.take(900) }
                .ifBlank { paragraphs.take(5).joinToString("\\n\\n") { it.take(900) } }.take(5200)
            fetched = text.isNotBlank()
        }
        val fallback = when(key) {
            "activity" -> "An Activity is an app component that provides a window for a user interface. Find the declared activity in AndroidManifest.xml, resolve its class to a Java/Kotlin or decoded Smali path, then trace setContentView, layout inflation or Compose setContent."
            "fragment" -> "A Fragment owns a portion of a UI. Trace the host Activity and FragmentTransaction/NavHost, then inspect the Fragment's onCreateView/layout inflater or ComposeView. Do not assume a corresponding layout XML exists."
            "layout" -> "Classic View layouts are XML files under res/layout and qualified variants such as res/layout-land. Their basename is the layout resource name. Trace setContentView(R.layout.name), inflate(R.layout.name) or Smali R$layout references from the actual Activity/Fragment."
            "resources" -> "Android resources live under typed res subdirectories: layout, drawable, mipmap, values, menu, xml, raw and font. Values XML can contain strings, colors, dimensions, styles and themes. Assets are separate from res and keep their original directory paths."
            "manifest" -> "AndroidManifest.xml declares app components, intent filters, themes and metadata. Resolve fully qualified component names and then find their corresponding code or Smali files."
            "compose" -> "Jetpack Compose UI is generally declared in Kotlin source through @Composable functions and setContent; decoded APKs may expose only DEX/Smali, not original Kotlin source."
            "webview" -> "A WebView UI can load local HTML/CSS/JavaScript from assets or a URL. Inspect WebView.loadUrl/loadData references and trace the actual asset paths."
            else -> "Apktool decodes DEX bytecode into Smali and resources into decoded folders. Original Java/Kotlin source is not guaranteed to be present. Trace manifest component names, Smali class references and resource identifiers."
        }
        JSONObject().put("topic",key).put("title",page.first).put("url",page.second)
            .put("fetched_from_internet",fetched).put("query",query)
            .put("documentation_excerpt",if (fetched) text else fallback)
            .put("fallback_explanation",fallback)
            .put("important","Official docs describe Android conventions, not this APK's exact files. Use local project tools to verify exact paths and references.")
            .toString()
    }

    private fun readFile(relative: String, start: Int, end: Int): String {
        val file = target(relative)
        require(isText(file)) { "File is missing, binary, or larger than 2 MB: $relative" }
        val lines = file.readLines(Charsets.UTF_8)
        val from = (start.coerceAtLeast(1)-1).coerceAtMost(lines.size)
        val until = end.coerceAtLeast(start).coerceAtMost(from+80).coerceAtMost(lines.size)
        var usedChars = 0
        val bodyLines = mutableListOf<String>()
        var actualUntil = from
        for (i in from until until) {
            val line = lines[i].take(1200)
            if (usedChars + line.length > 12000 && bodyLines.isNotEmpty()) break
            bodyLines.add("${i+1}: ${line}")
            usedChars += line.length
            actualUntil = i + 1
        }
        val body = bodyLines.joinToString("\n")
        val next = if (actualUntil < lines.size) actualUntil + 1 else JSONObject.NULL
        return JSONObject().put("ok",true).put("path",relative).put("total_lines",lines.size)
            .put("start_line",from+1).put("end_line",actualUntil).put("has_more",actualUntil < lines.size)
            .put("next_start_line",next).put("content",body)
            .put("note","Chunks are limited to 80 lines and about 12 KB. Continue from next_start_line.")
            .toString()
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
        require(isText(file)) { "Target must be an existing readable text file under 8 MB." }
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
