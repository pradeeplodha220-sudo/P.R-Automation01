package com.example.engine

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

object AutomationState {
    const val PREFS = "pr_automation"
    const val KEY_TARGET = "target_package"

    @Volatile var running = false
    @Volatile var paused = false
    @Volatile var isRooted = false
    @Volatile var solved = 0
    @Volatile var failed = 0
    @Volatile var lastQuestion = ""
    @Volatile var lastAnswer = ""
    @Volatile var status = "STOPPED"
    @Volatile var action = "Idle"
    @Volatile var logAutoScroll = true

    val logs = CopyOnWriteArrayList<String>()

    // Flow for real-time Compose observers
    private val _stateUpdateTick = MutableStateFlow(0L)
    val stateUpdateTick = _stateUpdateTick.asStateFlow()

    private val _logsFlow = MutableStateFlow<List<String>>(emptyList())
    val logsFlow = _logsFlow.asStateFlow()

    fun log(s: String) {
        val line = "• $s"
        logs.add(line)
        while (logs.size > 1000) {
            logs.removeAt(0)
        }
        _logsFlow.value = logs.toList()
        notifyChange()
    }

    fun clearLogs() {
        logs.clear()
        _logsFlow.value = emptyList()
        notifyChange()
    }

    fun start() {
        running = true
        paused = false
        status = "RUNNING"
        action = "Started"
        log("Automation started")
        notifyChange()
    }

    fun pause() {
        if (running) {
            paused = true
            status = "PAUSED"
            action = "Paused"
            log("Paused")
            notifyChange()
        }
    }

    fun resume() {
        if (running) {
            paused = false
            status = "RUNNING"
            action = "Resumed"
            log("Resumed")
            notifyChange()
        }
    }

    fun stop() {
        running = false
        paused = false
        status = "STOPPED"
        action = "Stopped"
        log("Stopped")
        notifyChange()
    }

    fun notifyChange() {
        _stateUpdateTick.value = System.currentTimeMillis()
    }

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun target(c: Context): String =
        prefs(c).getString(KEY_TARGET, "")?.trim() ?: ""

    fun setTarget(c: Context, pkg: String) {
        prefs(c).edit().putString(KEY_TARGET, pkg.trim()).apply()
        log("Target saved: ${pkg.trim()}")
        notifyChange()
    }
}
