package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.session

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.transport.McpHttpTestCase
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util.StableObjectIds
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The real registry, HTTP boundary, session selection and console document, without a debuggee. */
class ConsoleOutputToolBehaviorTest : McpHttpTestCase() {
    private val sessions = mutableListOf<XDebugSession>()

    override fun tearDown() {
        try {
            sessions.forEach { it.stop() }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        } finally {
            super.tearDown()
        }
    }

    private fun session(name: String, text: String): XDebugSession {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(testRootDisposable, console)
        console.component
        console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (console.hasDeferredOutput() && System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertFalse("Console output never rendered", console.hasDeferredOutput())
        return XDebuggerManager.getInstance(project).startSessionAndShowTab(name, null, true, object : XDebugProcessStarter() {
            override fun start(session: XDebugSession): XDebugProcess = object : XDebugProcess(session) {
                override fun getEditorsProvider(): XDebuggerEditorsProvider = object : XDebuggerEditorsProvider() {
                    override fun getFileType() = PlainTextFileType.INSTANCE
                }

                override fun createConsole(): ConsoleView = console

                // There is no real debuggee to stop. XDebugProcess's default stop() throws
                // AbstractMethodError, preventing the manager from removing this test session.
                override fun stop() {}
            }
        }).also { sessions.add(it) }
    }

    private fun call(arguments: String = "{}") =
        pumpingEdt { post(McpConstants.MCP_ENDPOINT_PATH, toolCall("get_console_output", arguments)) }
            .jsonBody()["result"]!!.jsonObject

    fun `test a running session returns its retained console text over HTTP`() {
        val session = session("Console output", "one\ntwo\nthree\n")
        assertFalse(session.isPaused)
        val result = call("""{"lines":2}""")
        assertFalse("Tool reported an error: $result", result["isError"]!!.jsonPrimitive.boolean)
        val body = result["structuredContent"]!!.jsonObject
        assertEquals(StableObjectIds.idFor(session), body["sessionId"]!!.jsonPrimitive.content)
        assertEquals("two\nthree\n", body["output"]!!.jsonPrimitive.content)
        assertEquals(3, body["totalLines"]!!.jsonPrimitive.int)
        assertEquals(2, body["startLine"]!!.jsonPrimitive.int)
        assertTrue(body["hasMore"]!!.jsonPrimitive.boolean)
        assertEquals(
            json.parseToJsonElement(result["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content),
            body
        )
        assertFalse("Reading must not pause a running session", session.isPaused)
    }

    fun `test explicit session selection does not read another sessions output`() {
        val first = session("First console", "first only\n")
        session("Second console", "second only\n")
        val body = call("""{"session_id":"${StableObjectIds.idFor(first)}"}""")["structuredContent"]!!.jsonObject
        assertEquals("first only\n", body["output"]!!.jsonPrimitive.content)
    }
}
