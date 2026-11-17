package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.psi.JavaDirectoryService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Create a Java type from IDEA's built-in file templates (Class/Interface/Enum/Record/
 * AnnotationType) via JavaDirectoryService — the same path as IDEA's New | Java Class dialog,
 * headless. Template expansion auto-generates the package statement, any configured file
 * header (copyright etc.) and applies code style, so agents get convention-consistent files
 * instead of hand-assembled boilerplate.
 */
class CreateFromTemplateTool : AbstractMcpTool() {

    override val requiresPsiSync = false

    override val name = "ide_create_from_template"

    override val description = """
        Create a new Java type from IDEA's built-in file templates (same engine as New | Java Class).

        Auto-generates: package statement (derived from the target directory), configured file header,
        correct declaration — then formats, saves to disk and indexes the file immediately.

        kind → IDEA internal template: Class | Interface | Enum | Record | AnnotationType | JUnit4TestClass.
        Or pass a custom templateName (must exist in FileTemplateManager, e.g. a user template under
        configDir/fileTemplates). dir = target package directory relative to project root
        (e.g. src/main/java/com/example/pkg); its package statement is derived from the source root.

        Examples:
        - {"dir": "src/test/java/com/example", "kind": "JUnit4TestClass", "name": "OrderServiceTest"}
        - {"dir": "src/main/java/com/example/pkg", "kind": "Enum", "name": "OrderStatus"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .stringProperty("dir", "Target package directory relative to project root (must be under a source root). REQUIRED.")
        .stringProperty("kind", "Class | Interface | Enum | Record | AnnotationType | JUnit4TestClass (default Class).")
        .stringProperty("templateName", "Custom template name overriding kind (advanced).")
        .stringProperty("name", "Type name. REQUIRED.")
        .build()

    @Serializable
    data class CreateResult(val success: Boolean, val file: String, val message: String)

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val dirArg = arguments["dir"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: dir")
        val name = arguments["name"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: name")
        val templateName = arguments["templateName"]?.jsonPrimitive?.content
            ?: arguments["kind"]?.jsonPrimitive?.content
            ?: "Class"

        val projectPathArg = arguments[ParamNames.PROJECT_PATH]?.jsonPrimitive?.content
        val base = resolveBase(project, arguments, dirArg, projectPathArg)
            ?: return createErrorResult("dir '$dirArg' is not inside any known project/content root")
        val targetDir = File(base, dirArg).canonicalFile
        if (!targetDir.path.startsWith(File(base).canonicalFile.path + File.separator) &&
            targetDir != File(base).canonicalFile) {
            return createErrorResult("dir escapes project root: $dirArg")
        }
        val psiDir = suspendingReadAction {
            val vDir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(targetDir.toPath())
                ?: return@suspendingReadAction null
            PsiManager.getInstance(project).findDirectory(vDir)
        } ?: return createErrorResult("Not a recognized directory: $dirArg (must be under a source root)")

        val failure = arrayOf<String?>(null)
        val createdRel = arrayOf<String?>(null)

        ApplicationManager.getApplication().invokeAndWait {
            try {
                val jds: JavaDirectoryService = JavaDirectoryService.getInstance()
                val createdClass = jds.createClass(psiDir as PsiDirectory, name, templateName)
                FileDocumentManager.getInstance().saveAllDocuments()
                val vf = createdClass.containingFile?.virtualFile
                createdRel[0] = vf?.let { ProjectUtils.getToolFilePath(project, it) } ?: "$dirArg/$name.java"
            } catch (e: Exception) {
                var r: Throwable = e
                while (r.cause != null && r.cause !== r) r = r.cause!!
                failure[0] = "${r.javaClass.simpleName}: ${r.message}"
            }
        }
        failure[0]?.let { return createErrorResult("Create from template failed: $it") }

        val rel = createdRel[0] ?: "$dirArg/$name.java"
        return createJsonResult(CreateResult(true, rel, "Created '$rel' from template '$templateName' (indexed, saved)"))
    }

    private fun resolveBase(project: Project, arguments: JsonObject, dirArg: String, projectPathArg: String?): String? {
        val contentRoots = ProjectUtils.getModuleContentRoots(project)
        if (projectPathArg != null) {
            val canonical = File(projectPathArg).canonicalPath
            contentRoots.firstOrNull { File(it).canonicalPath == canonical }?.let { return it }
            val known = contentRoots + listOfNotNull(project.basePath)
            if (known.any { root -> canonical == File(root).canonicalPath || canonical.startsWith(File(root).canonicalPath + File.separator) }) {
                return canonical
            }
            return null
        }
        val basePath = project.basePath ?: return null
        val first = dirArg.split("/", "\\").firstOrNull() ?: return basePath
        contentRoots.firstOrNull { root -> File(root, first).exists() }?.let { return it }
        return basePath
    }
}
