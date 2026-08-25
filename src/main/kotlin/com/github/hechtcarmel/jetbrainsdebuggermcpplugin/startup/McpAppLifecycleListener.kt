package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.startup

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.McpServerService
import com.intellij.ide.AppLifecycleListener

/**
 * Eagerly triggers MCP server startup when the IDE application frame is created or started.
 *
 * Ensures that the MCP server socket is bound as early as possible during IDE boot, preventing
 * connection timeouts from agent harnesses (such as Antigravity IDE and CLI) that connect
 * immediately on launch.
 */
class McpAppLifecycleListener : AppLifecycleListener {

    override fun appFrameCreated(commandLineArgs: MutableList<String>) {
        McpServerService.getInstance().ensureStarted()
    }

    override fun appStarted() {
        McpServerService.getInstance().ensureStarted()
    }
}
