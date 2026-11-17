package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.inspection

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.analysis.AnalysisScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CodeSmellDetector
import com.intellij.openapi.vfs.VirtualFile
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Batch code analysis over a chosen scope, inside the running IDE process.
 *
 * Engine: the platform's [CodeSmellDetector] — the same public batch analysis the IDE uses for
 * pre-commit checks (local inspections of the project profile, including third-party inspection
 * plugins). Runs on shared hot indexes with no detached JVM.
 *
 * Scope 'uncommitted' follows the VCS working set (requires a VCS plugin such as Git4Idea),
 * mirroring the GUI "Only changes uncommitted to VCS" inspection scope.
 *
 * Known limitation (v1): the engine runs the project profile's local inspections; it does not
 * accept an alternate profile parameter — switch profiles in IDE settings when needed. Global
 * (cross-file) inspections are not part of this engine; for the deepest full sweep use the
 * official headless `inspect.bat` (see the skill's references).
 */
class InspectTool : AbstractMcpTool() {

    companion object {
        private const val MAX_FILES = 200
        private const val DEFAULT_MAX_PROBLEMS = 300
        private const val MAX_MAX_PROBLEMS = 2000
        private const val MAX_ENGINE_ATTEMPTS = 15
        private const val RETRY_SETTLE_MS = 3000L
    }

    @Serializable
    data class InspectProblem(
        val file: String,
        val line: Int,
        val severity: String,
        val description: String
    )

    @Serializable
    data class InspectResult(
        val scope: String,
        val filesInspected: Int,
        val total: Int,
        val truncated: Boolean,
        val problems: List<InspectProblem>
    )

    override val name = "ide_inspect"

    override val description = """
        Run code analysis over a scope, inside the running IDE — no detached JVM, shared hot indexes.

        Engines: 'standard' = platform pre-commit batch engine (fast, native local-inspection subset).
        'full' = the SAME engine behind GUI Analyze | Inspect Code, driven reflectively in-process:
        runs the complete project profile including third-party inspection plugins (e.g. Alibaba P3C)
        and exports per-inspection XML reports to a directory; the result JSON points at that
        directory plus aggregate stats (parse the XMLs for details).

        Scopes: 'uncommitted' (files with uncommitted VCS changes — the agent's working set; requires a VCS plugin), 'files' (explicit list via 'files', max $MAX_FILES), 'whole' (entire project — slow on large projects).

        Parameters: scope (required), engine ('standard' default | 'full'), files (array, scope='files'), min_severity ('error' keeps errors only, default: all), max_problems (standard engine only, default $DEFAULT_MAX_PROBLEMS).

        Example: {"scope": "uncommitted", "engine": "full"} — the GUI-equivalent gate on your working set.
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .enumProperty(
            "scope",
            "Analysis scope. 'uncommitted' = files with uncommitted VCS changes; 'files' = explicit list; 'whole' = entire project.",
            listOf("uncommitted", "files", "whole"),
            required = true
        )
        .enumProperty("engine", "Analysis engine. 'standard' = fast pre-commit batch; 'full' = GUI Inspect Code engine with full profile (P3C included) and XML export.", listOf("standard", "full"))
        .stringArrayProperty("files", "Project-relative or in-project absolute file paths (scope='files' only, max $MAX_FILES).")
        .enumProperty("min_severity", "Only report problems at or above this severity. Default: all.", listOf("all", "error"))
        .intProperty("max_problems", "Standard engine only: max problems to return. Default: $DEFAULT_MAX_PROBLEMS, max: $MAX_MAX_PROBLEMS.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        requireSmartMode(project)

        val scopeArg = optionalStringArg(arguments, "scope")
            ?: return createErrorResult("Missing required parameter: scope")
        val minSeverity = optionalStringArg(arguments, "min_severity") ?: "all"
        val maxProblems = (arguments["max_problems"]?.jsonPrimitive?.int ?: DEFAULT_MAX_PROBLEMS)
            .coerceIn(1, MAX_MAX_PROBLEMS)

        val scopeFiles: List<VirtualFile> = when (scopeArg) {
            "files" -> {
                val raw = arguments["files"] as? JsonArray
                    ?: return createErrorResult("Parameter 'files' is required when scope='files'.")
                val paths = raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                if (paths.isEmpty()) return createErrorResult("Parameter 'files' must contain at least one path.")
                if (paths.size > MAX_FILES) return createErrorResult("Parameter 'files' supports at most $MAX_FILES paths per request.")
                paths.map { p ->
                    resolveFile(project, p) ?: return createErrorResult("File not found in project: $p")
                }.distinctBy { it.path }
            }

            "uncommitted" -> {
                val files = uncommittedFiles(project)
                    ?: return createErrorResult(
                        "Uncommitted scope requires a VCS plugin (e.g. Git) and a project under version control. Use scope='files' instead."
                    )
                if (files.isEmpty()) {
                    return createJsonResult(InspectResult(scopeArg, 0, 0, false, emptyList()))
                }
                files
            }

            "whole" -> emptyList() // resolved below via AnalysisScope.PROJECT

            else -> return createErrorResult("Invalid scope '$scopeArg'. Supported values: uncommitted, files, whole.")
        }

        val engine = optionalStringArg(arguments, "engine") ?: "standard"

        if (engine == "full") {
            return runFullEngineInspect(project, scopeArg, scopeFiles)
        }

        val detector = CodeSmellDetector.getInstance(project)
            ?: return createErrorResult("Code analysis service is not available in this IDE.")

        val filesToAnalyze: List<VirtualFile> = if (scopeArg == "whole") {
            val roots = com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
                .getModuleContentRoots(project)
                .mapNotNull { com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it) }
            val acc = mutableListOf<VirtualFile>()
            fun collect(vf: VirtualFile) {
                if (vf.isDirectory) vf.children.forEach { collect(it) } else acc.add(vf)
            }
            roots.forEach { collect(it) }
            acc.distinctBy { it.path }
        } else {
            scopeFiles
        }

        val smells = edtAction {
            com.intellij.openapi.progress.ProgressManager.getInstance()
                .runProcess(
                    com.intellij.openapi.util.Computable { detector.findCodeSmells(filesToAnalyze) },
                    com.intellij.openapi.progress.EmptyProgressIndicator()
                )
        }

        val problems = mutableListOf<InspectProblem>()
        var truncated = false
        val fileDocumentManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        for (smell in smells) {
            if (problems.size >= maxProblems) {
                truncated = true
                break
            }
            val severity = smell.severity?.name ?: "WARNING"
            if (minSeverity == "error" && !severity.equals("ERROR", ignoreCase = true)) continue
            val document = smell.document
            val virtualFile = fileDocumentManager.getFile(document)
            val line = if (document != null) {
                document.getLineNumber(smell.textRange.startOffset) + 1
            } else {
                1
            }
            problems.add(
                InspectProblem(
                    file = virtualFile?.let { getRelativePath(project, it) } ?: "",
                    line = line,
                    severity = severity,
                    description = smell.description ?: ""
                )
            )
        }

        return createJsonResult(
            InspectResult(
                scope = scopeArg,
                filesInspected = filesToAnalyze.size,
                total = problems.size,
                truncated = truncated,
                problems = problems
            )
        )
    }

    /**
     * Files with uncommitted VCS changes. Returns null when no VCS integration is available,
     * an empty list when the working tree is clean.
     */
    private suspend fun uncommittedFiles(project: Project): List<VirtualFile>? {
        return try {
            edtAction {
                val changeListManager = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
                val files = mutableListOf<VirtualFile>()
                for (change in changeListManager.getAllChanges()) {
                    change.afterRevision?.file?.virtualFile?.let { files.add(it) }
                    change.beforeRevision?.file?.virtualFile?.let { files.add(it) }
                }
                files.distinctBy { it.path }
            }
        } catch (_: NoClassDefFoundError) {
            null // VCS API not on the classpath (no VCS plugin installed)
        } catch (_: IllegalStateException) {
            null // VCS not initialized for this project (not under version control)
        }
    }

    /**
     * Full engine path: drives the GUI Inspect Code engine reflectively (impl classes are
     * runtime-only, not on the plugin compile classpath) and exports per-inspection XML
     * reports — bit-identical data to the GUI run and the headless `inspect.bat`.
     */
    private fun runFullEngineInspect(project: Project, scopeArg: String, scopeFiles: List<VirtualFile>): CallToolResult {
        val done = java.util.concurrent.CompletableFuture<String>()
        com.intellij.openapi.progress.ProgressManager.getInstance()
            .run(object : com.intellij.openapi.progress.Task.Backgroundable(project, "ide_inspect (full)", true) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    // Task.run already executes under this indicator — do NOT re-enter runProcess
                    // with the same indicator (assertNoOtherThreadUnder data-race guard).
                    var lastFailure: Throwable? = null
                    try {
                        for (attempt in 1..MAX_ENGINE_ATTEMPTS) {
                            // The platform cancels background read-based processes whenever a write
                            // action needs the lock (user edits, VFS refresh). The daemon survives the
                            // same mechanism by retrying — mirror that: fresh context + fresh export
                            // dir per attempt, bounded by MAX_ENGINE_ATTEMPTS.
                            val outDir = java.nio.file.Files.createTempDirectory("ide-inspect-full-")
                            try {
                                done.complete(runInspectionAndExport(project, scopeArg, scopeFiles, outDir))
                                return
                            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                                if (indicator.isCanceled) throw e // user cancelled — do not fight them
                                lastFailure = e
                                deleteRecursivelyQuietly(outDir)
                                indicator.checkCanceled() // cooperative yield before retrying
                                Thread.sleep(RETRY_SETTLE_MS)
                            }
                        }
                        done.completeExceptionally(lastFailure ?: IllegalStateException("engine exhausted"))
                    } catch (e: InterruptedException) {
                        done.completeExceptionally(e)
                    } catch (e: Throwable) {
                        done.completeExceptionally(e)
                    }
                }
            })
        return try {
            val text = done.get(10, java.util.concurrent.TimeUnit.MINUTES)
            createSuccessResult(text)
        } catch (e: java.util.concurrent.ExecutionException) {
            var root: Throwable = e
            while (root.cause != null && root.cause !== root) root = root.cause!!
            createErrorResult(
                "Full inspection engine failed after retries: ${root.javaClass.simpleName}: ${root.message}\n" +
                    root.stackTrace.take(4).joinToString("\n") { "  at " + it }
            )
        } catch (e: Throwable) {
            createErrorResult("Full inspection engine unavailable on this build: ${e.message}")
        }
    }

    private fun deleteRecursivelyQuietly(dir: java.nio.file.Path) {
        runCatching {
            java.util.Comparator.comparing { p: java.nio.file.Path -> p.nameCount }.let { cmp ->
                java.nio.file.Files.walk(dir).sorted(cmp.reversed()).forEach { java.nio.file.Files.deleteIfExists(it) }
            }
        }
    }

    /**
     * Engine sequence (called under a background Task progress): create inspection context →
     * pin the project's current profile (includes user-selected P3C tools) → set scope →
     * perform + export per-inspection XML reports in ONE engine pass → aggregate.
     */
    private fun runInspectionAndExport(
        project: Project,
        scopeArg: String,
        scopeFiles: List<VirtualFile>,
        outDir: java.nio.file.Path
    ): String {
        val manager = com.intellij.codeInspection.InspectionManager.getInstance(project)
        val createNew = manager.javaClass.getMethod("createNewGlobalContext", Boolean::class.javaPrimitiveType)
        val context = createNew.invoke(manager, false)

        // Pin the profile EXACTLY like headless InspectCommand: load a FRESH instance from the
        // profile XML via ApplicationInspectionProfileManagerBase.loadProfile(path), NOT the
        // project's in-memory profile instance (the headless path over the same XML produced
        // Ali* findings while the in-memory instance silently did not).
        var profileToSet: Any? = null
        runCatching {
            val profilePath = java.nio.file.Path.of(project.basePath ?: return@runCatching, ".idea", "inspectionProfiles", "Project_Default.xml")
            if (!java.nio.file.Files.exists(profilePath)) return@runCatching
            val baseClass = Class.forName("com.intellij.codeInspection.ex.ApplicationInspectionProfileManagerBase", true,
                com.intellij.openapi.application.ApplicationManager.getApplication().javaClass.classLoader)
            val base = baseClass.methods.first { it.name == "getInstanceBase" && it.parameterCount == 0 }.invoke(null)
            profileToSet = baseClass.methods
                .first { it.name == "loadProfile" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
                .invoke(base, profilePath.toString())
        }
        if (profileToSet == null) {
            profileToSet = context.javaClass.methods.firstOrNull { it.name == "getCurrentProfile" }?.invoke(context)
        }
        context.javaClass.methods.firstOrNull { it.name == "setExternalProfile" }
            ?.invoke(context, profileToSet)

        val scopeClass = Class.forName("com.intellij.analysis.AnalysisScope")
        val scope: Any = when (scopeArg) {
            "whole" -> {
                // 262 declares AnalysisScope(SearchScope, Project) — reflect on the declared type
                val searchScopeClass = Class.forName("com.intellij.psi.search.SearchScope")
                val projectScope = Class.forName("com.intellij.psi.search.GlobalSearchScope")
                    .getMethod("projectScope", Project::class.java)
                    .invoke(null, project)
                scopeClass.getConstructor(searchScopeClass, Project::class.java).newInstance(projectScope, project)
            }
            else -> {
                val collection: MutableCollection<VirtualFile> = ArrayList(scopeFiles)
                scopeClass.getConstructor(Project::class.java, MutableCollection::class.java).newInstance(project, collection)
            }
        }
        context.javaClass.methods.firstOrNull { it.name == "setCurrentScope" }
            ?.invoke(context, scope)

        // Stream collector for LOCAL-tool problems: the engine streams every local problem to
        // the installed InspectionProblemConsumer as a self-produced jdom <problem> Element
        // (DelegatedInspectionToolPresentation); without a consumer they go nowhere. Global
        // tools are still exported to XML by exportResultsSmart.
        val streamed = java.util.concurrent.atomic.AtomicInteger()
        val perTool = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
        var consumerStatus = "error"
        runCatching {
            // jdom2 is platform-internal since 262 — invisible to the plugin classloader;
            // load interface + Element via the platform loader.
            val platformLoader = com.intellij.openapi.application.ApplicationManager.getApplication().javaClass.classLoader
            val consumerItf = Class.forName("com.intellij.codeInspection.ex.InspectionProblemConsumer", true, platformLoader)
            val elementClass = Class.forName("org.jdom.Element", true, platformLoader) // JDOM 1.x — the platform bundles org.jdom, NOT org.jdom2
            val childText = elementClass.methods.first { it.name == "getChildText" }
            val getChild = elementClass.methods.first { it.name == "getChild" }
            val attrValue = elementClass.methods.first { it.name == "getAttributeValue" }
            val consumer = java.lang.reflect.Proxy.newProxyInstance(
                consumerItf.classLoader, arrayOf(consumerItf)
            ) { _, m, args ->
                if (m.name == "consume" && args != null && args.size == 3 && args[0] != null) {
                    try {
                        val el = args[0]
                        val tool = args[2]?.let { w ->
                            w.javaClass.methods.firstOrNull { it.name == "getShortName" }?.invoke(w)
                        } ?: "?"
                        val file = childText.invoke(el, "file") ?: "?"
                        val line = childText.invoke(el, "line") ?: "?"
                        val desc = childText.invoke(el, "description") ?: ""
                        val sev = getChild.invoke(el, "problem_class")?.let { pc ->
                            attrValue.invoke(pc, "severity") ?: "?"
                        } ?: "?"
                        streamed.incrementAndGet()
                        perTool.compute(tool.toString()) { _, v ->
                            (v ?: mutableListOf<String>()).also {
                                if (it.size < 50) it.add("  [$sev] $file:$line ${desc.toString().take(120)}")
                            }
                        }
                    } catch (_: Throwable) {}
                }
                null
            }
            val setter = context.javaClass.methods.firstOrNull { it.name == "setProblemConsumer" }
            if (setter == null) {
                consumerStatus = "setter-missing"
            } else {
                setter.invoke(context, consumer)
                consumerStatus = "installed"
            }
        }.onFailure { consumerStatus = "error: " + (it.message ?: it.javaClass.simpleName) }

        // perform + export in ONE engine pass — flags mirror the official offline InspectCommand
        // (inspect.bat → launchInspectionsOffline): runGlobalToolsOnly=false keeps local tools
        // (P3C etc.) in the run; isOffline=true uses scope entry points like headless runs.
        val perform = context.javaClass.getMethod(
            "performInspectionsWithProgressAndExportResults",
            scopeClass,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            java.nio.file.Path::class.java,
            java.util.List::class.java
        )
        perform.invoke(context, scope, false, true, outDir, java.util.ArrayList<Any>())

        // Aggregate the exported per-inspection XML files
        var xmlCount = 0
        var total = 0
        val bySeverity = mutableMapOf<String, Int>()
        val problemRe = Regex("<problem>[\\s\\S]*?</problem>")
        val sevRe = Regex("severity=\"([^\"]+)\"")
        val samples = StringBuilder()
        val xmlFiles = java.io.File(outDir.toString()).listFiles { f -> f.name.endsWith(".xml") } ?: emptyArray()
        for (xml in xmlFiles) {
            val text = xml.readText()
            if (!text.contains("<problem>")) continue
            xmlCount++
            for (m in problemRe.findAll(text)) {
                val block = m.value
                val sev = sevRe.find(block)?.groupValues?.get(1) ?: "?"
                total++
                bySeverity[sev] = (bySeverity[sev] ?: 0) + 1
                if (samples.length < 1500) {
                    val fileM = Regex("<file>([^<]+)</file>").find(block)?.groupValues?.get(1) ?: "?"
                    val lineM = Regex("<line>(\\d+)</line>").find(block)?.groupValues?.get(1) ?: "?"
                    val descM = Regex("<description>([\\s\\S]*?)</description>").find(block)?.groupValues?.get(1) ?: ""
                    val clsM = Regex("<problem_class[^>]*>([^<]+)<").find(block)?.groupValues?.get(1) ?: "?"
                    samples.appendLine("  [$sev] $clsM — $fileM:$lineM ${descM.take(80)}")
                }
            }
        }

        return buildString {
            appendLine("""{"engine":"full","scope":"$scopeArg","xmlDir":"${outDir.toUri()}",""" +
                """"xmlReports":$xmlCount,"globalProblems":$total,"localProblems":${streamed.get()},"consumer":"$consumerStatus",""" +
                """"total":${total + streamed.get()},"bySeverity":${bySeverity.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }}}""")
            if (samples.isNotEmpty()) appendLine("global (XML) samples:")
            appendLine(samples)
            if (perTool.isNotEmpty()) {
                appendLine("local (streamed) samples by tool:")
                for (entry in perTool.toSortedMap()) {
                    appendLine("  [" + entry.key + "] " + entry.value.size + if (entry.value.size >= 50) "+" else "")
                    entry.value.take(5).forEach { appendLine(it) }
                }
            }
        }
    }
}
