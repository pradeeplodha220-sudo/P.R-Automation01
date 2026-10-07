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
    fun performBack()
    fun launchTarget(context: Context, packageName: String): Boolean
}
