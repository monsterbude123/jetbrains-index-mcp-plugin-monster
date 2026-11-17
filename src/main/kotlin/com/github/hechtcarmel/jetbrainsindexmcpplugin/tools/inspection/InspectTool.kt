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
        Run the project profile's batch code analysis (the IDE's pre-commit inspection engine, including third-party inspection plugins) over a scope, inside the running IDE — no detached JVM, shared hot indexes.

        Scopes: 'uncommitted' (files with uncommitted VCS changes — the agent's working set; requires a VCS plugin), 'files' (explicit list via 'files', max $MAX_FILES), 'whole' (entire project — can be slow on large projects).

        Returns: problems with file, 1-based line, severity and description, plus stats. Parameters: scope (required), files (array, scope='files' only), min_severity ('error' keeps errors only, default: all), max_problems (default $DEFAULT_MAX_PROBLEMS, max $MAX_MAX_PROBLEMS).

        Example: {"scope": "uncommitted"} or {"scope": "files", "files": ["src/A.java"], "min_severity": "error"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .enumProperty(
            "scope",
            "Analysis scope. 'uncommitted' = files with uncommitted VCS changes; 'files' = explicit list; 'whole' = entire project.",
            listOf("uncommitted", "files", "whole"),
            required = true
        )
        .stringArrayProperty("files", "Project-relative or in-project absolute file paths (scope='files' only, max $MAX_FILES).")
        .enumProperty("min_severity", "Only report problems at or above this severity. Default: all.", listOf("all", "error"))
        .intProperty("max_problems", "Max problems to return. Default: $DEFAULT_MAX_PROBLEMS, max: $MAX_MAX_PROBLEMS.")
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
}
