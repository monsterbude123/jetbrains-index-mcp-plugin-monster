package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Managed-process control: enumerate/stop processes owned by IDEA's Run panel
 * (started via execute_run_configuration or by the user). Closes the "agent forgets to
 * stop processes" loop — the agent lists and terminates; the IDE still owns the lifecycle.
 *
 * execution-impl is NOT on the plugin compile classpath → reflective against the project's
 * loader chain (ExecutionManager lives in the platform execution module, visible from there).
 */
class RunControlTool : AbstractMcpTool() {

    override val name = "ide_run_control"

    override val description = """
        Enumerate or stop processes managed by IDEA's Run panel (run configurations started via
        execute_run_configuration or by the user). The IDE owns the lifecycle — this tool only
        lists and terminates; nothing is orphaned by the agent.

        Actions:
        - {"action":"status"}                     → list running descriptors (name, alive)
        - {"action":"stop","displayName":"..."}   → graceful stop of matching descriptor(s) (substring, case-insensitive)
        - {"action":"stop","stopAll":true}        → stop all running descriptors

        Graceful stop first (ProcessHandler.destroyProcess); re-run status to confirm exit.
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .enumProperty("action", "status | stop", listOf("status", "stop"), required = true)
        .stringProperty("displayName", "For stop: descriptor name substring match (case-insensitive).")
        .booleanProperty("stopAll", "For stop: stop all running descriptors (overrides displayName).")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val action = arguments["action"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: action")
        val stopAll = arguments["stopAll"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val displayName = arguments["displayName"]?.jsonPrimitive?.content
        if (action == "stop" && !stopAll && displayName.isNullOrBlank()) {
            return createErrorResult("stop requires displayName or stopAll:true")
        }

        val descriptors = suspendingReadAction {
            // 262: ExecutionManager lost getRunningDescriptors; RunContentManager.getAllDescriptors
            // is the live list of run-panel descriptors.
            val rcmClass = Class.forName(
                "com.intellij.execution.ui.RunContentManager", true,
                project.javaClass.classLoader
            )
            val rcm = rcmClass.methods.first { it.name == "getInstance" && it.parameterCount == 1 }
                .invoke(null, project)
            @Suppress("UNCHECKED_CAST")
            rcmClass.methods.first { it.name == "getAllDescriptors" && it.parameterCount == 0 }
                .invoke(rcm) as List<Any>
        }

        val name = fun(d: Any): String = d.javaClass.methods
            .firstOrNull { it.name == "getDisplayName" && it.parameterCount == 0 }
            ?.invoke(d)?.toString() ?: "?"
        val alive = fun(d: Any): Boolean = d.javaClass.methods
            .firstOrNull { it.name == "getProcessHandler" && it.parameterCount == 0 }
            ?.invoke(d)?.let { ph ->
                ph.javaClass.methods.firstOrNull { it.name == "isProcessTerminated" && it.parameterCount == 0 }
                    ?.invoke(ph)?.toString() != "true"
            } ?: false
        val destroy = fun(d: Any) {
            d.javaClass.methods.firstOrNull { it.name == "getProcessHandler" && it.parameterCount == 0 }
                ?.invoke(d)?.let { ph ->
                    ph.javaClass.methods.firstOrNull { it.name == "destroyProcess" && it.parameterCount == 0 }
                        ?.invoke(ph)
                }
        }

        if (action == "status") {
            if (descriptors.isEmpty()) return createSuccessResult("""{"running":0,"processes":[]}""")
            val items = descriptors.joinToString(",") { d ->
                """"${escape(name(d))}":{"alive":${alive(d)}}"""
            }
            return createSuccessResult("""{"running":${descriptors.size},"processes":{$items}}""")
        }

        val targets = descriptors.filter { d ->
            stopAll || (displayName != null && name(d).contains(displayName, ignoreCase = true))
        }
        if (targets.isEmpty()) {
            return createErrorResult(
                "No matching running descriptor. " + if (descriptors.isEmpty()) "Nothing is running." else
                    "Running: " + descriptors.joinToString(", ") { name(it) }
            )
        }
        var stopped = 0
        for (d in targets) {
            if (alive(d)) { destroy(d); stopped++ }
        }
        return createSuccessResult("""{"stopRequested":$stopped,"of":${targets.size}}""")
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
