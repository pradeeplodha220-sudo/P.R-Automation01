package com.example.engine

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executors

object RootEngine {
    private const val TAG = "PR_RootEngine"
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile var isRootAvailable: Boolean = false
        private set

    @Volatile var isRootGranted: Boolean = false
        private set

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/data/local/su",
        "/su/bin/su",
        "/magisk/.core/bin/su"
    )

    fun detectRoot(callback: ((Boolean) -> Unit)? = null) {
        executor.execute {
            val exists = checkSuBinaryExists()
            isRootAvailable = exists
            if (exists) {
                val granted = testSuCommand()
                isRootGranted = granted
                AutomationState.isRooted = granted
                if (granted) {
                    AutomationState.log("⚡ SuperSU / Root access granted")
                } else {
                    AutomationState.log("Root binary found but su permission not granted")
                }
                callback?.invoke(granted)
            } else {
                isRootGranted = false
                AutomationState.isRooted = false
                callback?.invoke(false)
            }
        }
    }

    private fun checkSuBinaryExists(): Boolean {
        for (path in SU_PATHS) {
            if (File(path).exists()) return true
        }
        val pathEnv = System.getenv("PATH") ?: return false
        for (dir in pathEnv.split(":")) {
            if (File(dir, "su").exists()) return true
        }
        return false
    }

    private fun testSuCommand(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val line = reader.readLine()
            p.waitFor()
            line != null && line.contains("uid=0")
        } catch (_: Throwable) {
            false
        }
    }

    fun executeSu(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            os.writeBytes("$cmd\n")
            os.writeBytes("exit\n")
            os.flush()
            p.waitFor() == 0
        } catch (t: Throwable) {
            Log.e(TAG, "executeSu failed for: $cmd", t)
            false
        }
    }

    /**
     * Enables Accessibility Service directly via Root without opening system settings
     * and grants overlay permissions automatically.
     */
    fun enableAccessibilityViaRoot(context: Context): Boolean {
        if (!isRootGranted) return false
        val pkg = context.packageName
        val serviceName = "$pkg/com.example.service.QuizAccessibilityService"

        val script = """
            current=$(settings get secure enabled_accessibility_services)
            target="$serviceName"
            if [ -z "${'$'}current" ] || [ "${'$'}current" = "null" ]; then
                settings put secure enabled_accessibility_services "${'$'}target"
            elif [[ "${'$'}current" != *"${'$'}target"* ]]; then
                settings put secure enabled_accessibility_services "${'$'}current:${'$'}target"
            fi
            settings put secure accessibility_enabled 1
            pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>/dev/null
            appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null
        """.trimIndent()

        val success = executeSu(script)
        if (success) {
            AutomationState.log("⚡ Root: Accessibility & Overlay granted automatically")
        } else {
            AutomationState.log("Root command executed for accessibility")
        }
        return success
    }

    /**
     * Simulates tap using root command 'input tap x y'
     */
    fun tap(x: Int, y: Int) {
        executor.execute {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
            } catch (t: Throwable) {
                Log.e(TAG, "Root tap failed at ($x, $y)", t)
            }
        }
    }
}
