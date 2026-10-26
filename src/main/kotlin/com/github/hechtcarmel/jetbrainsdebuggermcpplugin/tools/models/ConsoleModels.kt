package com.github.hechtcarmel.jetbrainsdebuggermcpplugin.tools.models

import kotlinx.serialization.Serializable

/** A page of currently retained debug-console output, not an independent persistent log. */
@Serializable
data class ConsoleOutputResult(
    val sessionId: String,
    val output: String,
    val totalLines: Int,
    val startLine: Int,
    val endLine: Int,
    val hasMore: Boolean,
    val truncated: Boolean,
    val hasDeferredOutput: Boolean,
    val outputPaused: Boolean
)
