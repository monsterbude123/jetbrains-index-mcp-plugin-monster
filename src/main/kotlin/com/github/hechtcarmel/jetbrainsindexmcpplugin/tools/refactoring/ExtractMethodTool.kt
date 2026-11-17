package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import java.lang.reflect.InvocationTargetException

/**
 * Headless Extract Method — the same refactoring behind Refactor | Extract | Method.
 *
 * Implementation notes (262 facts, see skill references/ide-inspect-extension.md):
 * - java-impl-refactorings is NOT on the plugin compile classpath → all engine calls via reflection.
 * - Chain: ExtractMethodHandler.getElements(project, psiFile, TextRange) →
 *   ExtractMethodHandler.getProcessor(project, elements, psiFile, false) →
 *   processor.setShowErrorDialogs(false) → prepare() → prepareVariablesAndName() →
 *   reflectively pin myMethodName → ExtractMethodHandler.extractMethod(project, processor).
 * - extractMethod runs CommandProcessor + formatting postprocess + WriteCommandAction →
 *   the whole chain must execute on EDT (we are invoked from a background coroutine).
 * - PrepareFailedException carries the human-readable reason (incomplete statements etc).
 */
class ExtractMethodTool : AbstractMcpTool() {

    override val name = "ide_refactor_extract_method"

    override val description = """
        Extract the selected statement(s)/expression into a new method — Refactor | Extract | Method, headless.
        The engine analyzes local variables automatically: used-before values become parameters,
        assigned-after values become the return value, and the original range is replaced by the call.

        Positions are 1-based line/column. start selects the first statement/expression, end selects the last
        (a start==end position inside one statement also works — the engine expands to the enclosing expression).

        Example: {"file": "src/Worker.java", "start_line": 40, "start_column": 9, "end_line": 48, "end_column": 9, "name": "computeTotal"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .stringProperty("file", "Project-relative path of the file to extract from.", required = true)
        .intProperty("start_line", "1-based start line of the range to extract.", required = true)
        .intProperty("start_column", "1-based start column (defaults to line start).")
        .intProperty("end_line", "1-based end line of the range to extract.", required = true)
        .intProperty("end_column", "1-based end column (defaults to end of that line).")
        .stringProperty("name", "Name for the extracted method.", required = true)
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        requireSmartMode(project)
        val fileArg = optionalStringArg(arguments, "file")
            ?: return createErrorResult("Missing required parameter: file")
        val name = optionalStringArg(arguments, "name")
            ?: return createErrorResult("Missing required parameter: name")
        val startLine = intArg(arguments, "start_line")
            ?: return createErrorResult("Missing required parameter: start_line")
        val endLine = intArg(arguments, "end_line")
            ?: return createErrorResult("Missing required parameter: end_line")
        val startColumn = intArg(arguments, "start_column") ?: 1
        val endColumn = intArg(arguments, "end_column") ?: -1

        commitDocuments(project)
        val vFile = resolveFile(project, fileArg)
            ?: return createErrorResult("File not found: $fileArg")
        val failure = arrayOf<String?>(null)
        val outcome = arrayOf<String?>(null)
        val processorRef = arrayOf<Any?>(null)
        val extractMethodImpl = arrayOf<java.lang.reflect.Method?>(null)

        suspendingReadAction<Unit> {
            val psiFile = PsiManager.getInstance(project).findFile(vFile)
            if (psiFile == null) {
                failure[0] = "Not a PSI-supported file: $fileArg"
                return@suspendingReadAction
            }
            // java-impl-refactorings is NOT visible from the platform loader (262 splits java modules
            // into their own loader chain). Resolve via the PsiFile runtime class loader first, then
            // fall back to the ExtractMethod action loader and the platform loader.
            val appCl = ApplicationManager.getApplication().javaClass.classLoader
            val actionCl = com.intellij.openapi.actionSystem.ActionManager.getInstance()
                .getAction("ExtractMethod")?.javaClass?.classLoader
            fun forNameAny(name: String): Class<*>? =
                listOf(psiFile.javaClass.classLoader, actionCl, appCl, this.javaClass.classLoader)
                    .firstNotNullOfOrNull { cl ->
                        cl?.let { runCatching { Class.forName(name, true, it) }.getOrNull() }
                    }
            val handlerClass = forNameAny("com.intellij.refactoring.extractMethod.ExtractMethodHandler")
                ?: run {
                    failure[0] = "Extract engine not reachable from any loader (java refactoring module missing?)"
                    return@suspendingReadAction
                }
            // OVERLOAD HAZARD: ExtractMethodHandler has TWO 3-arg getElements
            // ((Project, Editor, PsiFile) and (Project, PsiFile, TextRange)) — match by exact types
            val getElements = handlerClass.methods.first { m ->
                m.name == "getElements" && m.parameterCount == 3 &&
                    m.parameterTypes[1] == PsiFile::class.java && m.parameterTypes[2] == TextRange::class.java
            }
            val getProcessor = handlerClass.methods.first { m ->
                m.name == "getProcessor" && m.parameterCount == 4 && m.parameterTypes[1] == Array<PsiElement>::class.java
            }
            val extractMethod = handlerClass.methods.first { it.name == "extractMethod" && it.parameterCount == 2 }
            val doc = FileDocumentManager.getInstance().getDocument(vFile)
            if (doc == null) {
                failure[0] = "No document for: $fileArg"
                return@suspendingReadAction
            }
            if (startLine < 1 || endLine > doc.lineCount) {
                failure[0] = "Line range $startLine..$endLine out of bounds (file has ${doc.lineCount} lines)"
                return@suspendingReadAction
            }
            val startOffset = doc.getLineStartOffset(startLine - 1) + (startColumn - 1).coerceAtLeast(0)
            val endOffset = if (endColumn < 0) doc.getLineEndOffset(endLine - 1)
            else doc.getLineStartOffset(endLine - 1) + endColumn - 1
            val range = TextRange(startOffset, endOffset.coerceAtLeast(startOffset))

            var elements: Array<*> = try {
                getElements.invoke(null, project, psiFile, range) as Array<*>
            } catch (e: InvocationTargetException) {
                failure[0] = "getElements invoke fail: " + rootMessage(e)
                return@suspendingReadAction
            } catch (e: Throwable) {
                failure[0] = "getElements invoke fail: ${e.javaClass.simpleName}: ${e.message}" +
                    " argClasses=" + listOf(project, psiFile, range).joinToString(",") { a ->
                        a.javaClass.name + "@" + a.javaClass.classLoader?.javaClass?.name?.substringAfterLast('.')
                    } + " declTypes=" + getElements.parameterTypes.joinToString(",") { it.name + "@" + it.classLoader?.javaClass?.name?.substringAfterLast('.') }
                return@suspendingReadAction
            }
            if (elements.isEmpty()) {
                // 262: CodeInsightUtil.findStatementsInRange can return 0 for a clean multi-statement
                // range — fall back to manual sibling-statement collection.
                // CLASSLOADER LAW: java-module classes (PsiStatement/PsiTreeUtil/this processor) are
                // loaded by a DIFFERENT loader than the plugin's — plugin-side instanceof against
                // java-loader objects always fails. Everything here must be reflective, and the
                // resulting array must be built over the java-loader PsiElement class.
                val jCl = handlerClass.classLoader
                val psiElementClass = jCl.loadClass("com.intellij.psi.PsiElement")
                val psiStatementClass = jCl.loadClass("com.intellij.psi.PsiStatement")
                val treeUtilClass = jCl.loadClass("com.intellij.psi.util.PsiTreeUtil")
                val getParentOfType = treeUtilClass.methods.first { it.name == "getParentOfType" && it.parameterCount == 3 }
                val mGetParent = psiElementClass.methods.first { it.name == "getParent" && it.parameterCount == 0 }
                val mGetChildren = psiElementClass.methods.first { it.name == "getChildren" && it.parameterCount == 0 }
                val mTextOffset = psiElementClass.methods.first { it.name == "getTextOffset" && it.parameterCount == 0 }
                val mTextLength = psiElementClass.methods.first { it.name == "getTextLength" && it.parameterCount == 0 }
                val startLeaf = psiFile.findElementAt(startOffset)
                val endLeaf = psiFile.findElementAt(range.endOffset - 1)
                val startStmt = startLeaf?.let { getParentOfType.invoke(null, it, psiStatementClass, false) }
                val endStmt = endLeaf?.let { getParentOfType.invoke(null, it, psiStatementClass, false) }
                if (startStmt != null && endStmt != null && mGetParent.invoke(startStmt) === mGetParent.invoke(endStmt)) {
                    val block = mGetParent.invoke(startStmt)
                    val children = (mGetChildren.invoke(block) as Array<*>).filterNotNull()
                    val startOff = mTextOffset.invoke(startStmt) as Int
                    val endOff = (mTextOffset.invoke(endStmt) as Int) + (mTextLength.invoke(endStmt) as Int)
                    val selected = children.filter { c ->
                        val off = mTextOffset.invoke(c) as Int
                        val len = mTextLength.invoke(c) as Int
                        off >= startOff && off + len <= endOff &&
                            !c.javaClass.name.contains("WhiteSpace")
                    }
                    if (selected.isNotEmpty()) {
                        val arr = java.lang.reflect.Array.newInstance(psiElementClass, selected.size)
                        selected.forEachIndexed { i, e -> java.lang.reflect.Array.set(arr, i, e) }
                        elements = arr as Array<*>
                    }
                }
            }
            if (elements.isEmpty()) {
                failure[0] = "No extractable statements/expressions in the given range — statements must be " +
                    "contiguous siblings in the same block (an end_line inside a nested block breaks the run)"
                return@suspendingReadAction
            }

            val processor = try {
                getProcessor.invoke(null, project, elements, psiFile, false)
            } catch (e: IllegalArgumentException) {
                val expected = getProcessor.parameterTypes[1]
                failure[0] = "getProcessor arg mismatch: expected=" + expected.name +
                    "@" + expected.classLoader?.javaClass?.name?.substringAfterLast('.') +
                    " actual=" + elements.javaClass.name +
                    "@" + elements.javaClass.classLoader?.javaClass?.name?.substringAfterLast('.') +
                    " elem=" + (elements.firstOrNull()?.javaClass?.name ?: "none") +
                    "@" + elements.firstOrNull()?.javaClass?.classLoader?.javaClass?.name?.substringAfterLast('.')
                return@suspendingReadAction
            }
            if (processor == null) {
                failure[0] = "Cannot build extract-method processor for this range (unsupported selection)"
                return@suspendingReadAction
            }
            processor.javaClass.methods.firstOrNull { it.name == "setShowErrorDialogs" }
                ?.invoke(processor, false)

            // prepare + name pinning are read-only analysis — fine inside the read action.
            // Only the WRITE (extractMethod) goes to EDT, and strictly OUTSIDE the read action
            // (invokeAndWait from within a read action is a documented deadlock).
            try {
                processor.javaClass.methods.first { it.name == "prepare" && it.parameterCount == 0 }
                    .invoke(processor)
                processor.javaClass.methods.first { it.name == "prepareVariablesAndName" }
                    .invoke(processor)
                // myMethodName is protected with package-internal dialog assignment; pin the requested name
                val nameField = processor.javaClass.getDeclaredField("myMethodName")
                nameField.isAccessible = true
                nameField.set(processor, name)
            } catch (e: InvocationTargetException) {
                failure[0] = rootMessage(e)
                return@suspendingReadAction
            } catch (t: Throwable) {
                failure[0] = "${t.javaClass.simpleName}: ${t.message}"
                return@suspendingReadAction
            }
            processorRef[0] = processor
            extractMethodImpl[0] = extractMethod
        }

        if (failure[0] == null && processorRef[0] != null) {
            ApplicationManager.getApplication().invokeAndWait {
                try {
                    extractMethodImpl[0]!!.invoke(null, project, processorRef[0])
                    // flush the refactored document to disk so plain file reads see it
                    FileDocumentManager.getInstance().saveAllDocuments()
                    outcome[0] = name
                } catch (e: InvocationTargetException) {
                    failure[0] = rootMessage(e)
                } catch (t: Throwable) {
                    failure[0] = "${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }

        failure[0]?.let { return createErrorResult("Extract method failed: $it") }
        val extracted = outcome[0] ?: return createErrorResult("Extract method produced no outcome")
        return createSuccessResult(
            """{"ok":true,"extracted":"$extracted","file":"${fileArg.replace("\\", "/")}",""" +
                """"note":"method extracted and original range replaced by the call; verify with ide_diagnostics or scripts/inspect-diff.sh"}"""
        )
    }

    private fun rootMessage(e: InvocationTargetException): String {
        var root: Throwable = e
        while (root.cause != null && root.cause !== root) root = root.cause!!
        return "${root.javaClass.simpleName}: ${root.message}"
    }

    private fun intArg(arguments: JsonObject, key: String): Int? =
        (arguments[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
}
