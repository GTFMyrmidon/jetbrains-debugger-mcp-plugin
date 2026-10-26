package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.util

import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.ToolExecutionError
import com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models.ConsoleOutputResult
import com.intellij.execution.console.ConsoleViewWrapperBase
import com.intellij.execution.console.DuplexConsoleView
import com.intellij.execution.console.LanguageConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.util.TextRange
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A bounded snapshot of the IDE's existing console buffer. No ProcessHandler listener, duplicated
 * history, reflection, focus changes, forced flush, or access to ConsoleViewImpl's internal API.
 * Call on the EDT. Language-console input is deliberately excluded.
 */
internal object ConsoleOutputReader {
    fun read(console: ConsoleView, sessionId: String, lines: Int, offset: Int, maxChars: Int): ConsoleOutputResult {
        val view = unwrap(console)
        val editor = if (view is LanguageConsoleView) {
            view.historyViewer
        } else {
            CommonDataKeys.EDITOR.getData(DataManager.getInstance().getDataContext(view.component))
                ?.takeIf { it.editorKind == EditorKind.CONSOLE }
        }
        if (editor == null || editor.isDisposed) {
            throw ToolExecutionError("Console output is not available as text for this debugger (${view.javaClass.name})")
        }
        return page(editor.document, sessionId, lines, offset, maxChars, view.hasDeferredOutput(), view.isOutputPaused)
    }

    private fun unwrap(console: ConsoleView): ConsoleView {
        val visited = Collections.newSetFromMap(IdentityHashMap<ConsoleView, Boolean>())
        var view = console
        while (visited.add(view)) {
            view = when (view) {
                is ConsoleViewWrapperBase -> view.delegate
                is DuplexConsoleView<*, *> -> view.primaryConsoleView
                else -> return view
            }
        }
        throw ToolExecutionError("Console output is not available: cyclic console wrapper")
    }

    /** Document line indexes avoid splitting/copying the entire history just to return its tail. */
    internal fun page(
        document: Document,
        sessionId: String,
        lines: Int,
        offset: Int,
        maxChars: Int,
        deferred: Boolean = false,
        paused: Boolean = false
    ): ConsoleOutputResult {
        val length = document.textLength
        val chars = document.immutableCharSequence
        val totalLines = if (length == 0) 0 else document.lineCount - if (chars[length - 1] == '\n') 1 else 0
        val endLine = (totalLines - offset).coerceAtLeast(0)
        val firstLine = (endLine - lines).coerceAtLeast(0)
        if (endLine == 0) {
            return ConsoleOutputResult(sessionId, "", totalLines, 0, 0, false, false, deferred, paused)
        }
        val pageStart = document.getLineStartOffset(firstLine)
        val end = if (endLine < document.lineCount) document.getLineStartOffset(endLine) else length
        var start = maxOf(pageStart, end - maxChars)
        // Never return half of a Unicode surrogate pair at the character-cap boundary.
        if (start > pageStart && start < end && chars[start].isLowSurrogate() && chars[start - 1].isHighSurrogate()) start++
        return ConsoleOutputResult(
            sessionId = sessionId,
            output = document.getText(TextRange(start, end)),
            totalLines = totalLines,
            startLine = if (start == end) 0 else document.getLineNumber(start) + 1,
            endLine = if (start == end) 0 else endLine,
            hasMore = start > 0,
            truncated = start > pageStart,
            hasDeferredOutput = deferred,
            outputPaused = paused
        )
    }
}
