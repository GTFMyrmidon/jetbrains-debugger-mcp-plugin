package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.startup

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.server.McpServerService
import com.intellij.ide.ApplicationInitializedListener

/**
 * Starts the MCP server immediately when the IntelliJ Application completes initialization,
 * running in a background coroutine before any project window or UI frame is created.
 */
class McpApplicationInitializedListener : ApplicationInitializedListener {

    override suspend fun execute() {
        McpServerService.getInstance().ensureStarted()
    }
}
