package com.example.engine

import android.content.Context

/**
 * RootAutomationBackend
 *
 * Clean interface isolating all root-based device inspection and interaction operations.
 * Completely independent from Android AccessibilityService.
 */
interface RootAutomationBackend {
    fun start(context: Context)
    fun stop()
    fun pause()
    fun resume()
    fun isRootAvailable(): Boolean
    fun dumpCurrentUi(): String
    fun performTap(x: Int, y: Int)
    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300)
    fun performBack()
    fun typeText(text: String)
    fun launchTarget(context: Context, packageName: String): Boolean
}
