package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolAnnotationPresets
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ConsoleOutputResult
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ConsoleOutputReader
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.ToolArguments
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Reads the IDE console itself: debugger-generated logpoint output need not reach ProcessHandler. */
class GetConsoleOutputTool : AbstractMcpTool() {
    override val name = "get_console_output"

    override val description = """
        Reads retained text from a debug session's console, including logpoint results and stdout/stderr rendered there. Works while running or paused, without evaluating code or changing execution.
        Returns the last lines by default; offset skips that many newest lines to page backward. Line numbers are 1-based within the console's currently retained buffer, not a durable cursor: clearing or the IDE's cyclic buffer can remove older output. Output is also capped by max_chars, keeping the end of the selected page when truncated.
        hasDeferredOutput means the IDE still has text waiting to render; read again later for that text. Reading does not unpause console output. Language consoles use output history, never the unsubmitted input editor; duplex consoles use their primary console. Consoles without an accessible text history return an error. Only debug sessions are supported, not plain Run configurations or terminals.
    """.trimIndent()

    override val annotations = ToolAnnotationPresets.readOnly("Get Console Output")

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            val (projectName, projectSchema) = projectPathProperty()
            put(projectName, projectSchema)
            val (sessionName, sessionSchema) = sessionIdProperty()
            put(sessionName, sessionSchema)
            put("lines", integerProperty("Maximum number of console lines to return, selecting the newest lines by default.", 100, 1, 1000))
            put("offset", integerProperty("Number of newest lines to skip before selecting the page. Relative to the retained buffer at the time of this call.", 0, 0))
            put("max_chars", integerProperty("Maximum UTF-16 characters returned. Oversized pages keep their end and report truncated=true.", 20000, 1, 100000))
        }
        put("required", buildJsonArray { })
        put("additionalProperties", false)
    }

    override val outputSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("sessionId", stringProperty("Debug session ID."))
            put("output", stringProperty("Retained console text in its original order, including line separators."))
            put("totalLines", integerProperty("Number of retained lines; a trailing newline does not add an empty line."))
            put("startLine", integerProperty("First returned line, 1-based; 0 when no text is returned. It may be partial when truncated."))
            put("endLine", integerProperty("Last returned line, 1-based; 0 when no text is returned."))
            put("hasMore", booleanProperty("More text exists before this page, including a truncated line prefix."))
            put("truncated", booleanProperty("The character limit removed a prefix of the selected page."))
            put("hasDeferredOutput", booleanProperty("The console has additional output waiting to render."))
            put("outputPaused", booleanProperty("The console's output rendering is paused by the IDE/user."))
        }
        put("required", buildJsonArray {
            listOf("sessionId", "output", "totalLines", "startLine", "endLine", "hasMore", "truncated", "hasDeferredOutput", "outputPaused")
                .forEach { add(JsonPrimitive(it)) }
        })
    }

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val sessionId = ToolArguments.optionalString(arguments, "session_id")
        val lines = ToolArguments.optionalInt(arguments, "lines", default = 100, min = 1, max = 1000)
        val offset = ToolArguments.optionalInt(arguments, "offset", default = 0, min = 0)
        val maxChars = ToolArguments.optionalInt(arguments, "max_chars", default = 20000, min = 1, max = 100000)

        return try {
            // Resolve and read together on the EDT: the session, console and its document must
            // belong to the same snapshot, even when focus changes or a process exits meanwhile.
            val result: ConsoleOutputResult = onEdt {
                val session = requireSession(project, sessionId)
                val console = session.consoleView
                    ?: throw ToolExecutionError("No console available for this debug session")
                ConsoleOutputReader.read(console, getSessionId(session), lines, offset, maxChars)
            }
            createJsonResult(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolExecutionError) {
            throw e
        } catch (e: Exception) {
            createErrorResult("Failed to read debug console output: ${e.message}")
        }
    }
}
