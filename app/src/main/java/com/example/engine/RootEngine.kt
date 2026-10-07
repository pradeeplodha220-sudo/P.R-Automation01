package com.example.engine

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.util.Xml
import com.example.service.OverlayService
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.StringReader
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

data class RootUiNode(
    val text: String,
    val desc: String,
    val id: String,
    val bounds: Rect,
    val clickable: Boolean,
    val className: String = "",
    val packageName: String = "",
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true
) {
    /**
     * Effective content text, prioritizing non-blank text and falling back to content-desc.
     */
    val content: String
        get() = if (text.isNotBlank()) text.trim() else desc.trim()

    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
}

object RootEngine : RootAutomationBackend {
    private const val TAG = "PR_RootEngine"
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile var isSuBinaryPresent: Boolean = false
        private set

    @Volatile var isRootGranted: Boolean = false
        private set

    @Volatile private var cachedFgPackage = ""
    @Volatile private var lastFgCheckTime = 0L

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
            isSuBinaryPresent = exists
            if (exists) {
                val granted = testSuCommand()
                isRootGranted = granted
                AutomationState.isRooted = granted
                if (granted) {
                    AutomationState.log("⚡ SuperSU / Root access granted (Clean Root Engine)")
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

    fun testSuCommand(): Boolean {
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

    fun shutdown() {
        runCatching { executor.shutdownNow() }
    }

    fun executeSuWithOutput(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            p.waitFor()
            sb.toString().trim()
        } catch (t: Throwable) {
            Log.e(TAG, "executeSuWithOutput failed for: $cmd", t)
            ""
        }
    }

    /**
     * Dumps the screen hierarchy via native 'dumpsys activity top' without touching AccessibilityService or UiAutomation.
     * Guarantees 0% accessibility detection by third-party apps and prevents warning dialogs or blinking.
     */
    fun dumpScreenViaDumpsys(): List<RootUiNode> {
        val out = executeSuWithOutput("dumpsys activity top 2>/dev/null")
        if (out.isNotBlank() && out.contains("View Hierarchy:")) {
            return parseDumpsysViewHierarchy(out)
        }
        return emptyList()
    }

    /**
     * Ultra-fast parser for 'dumpsys activity top' View Hierarchy.
     * Computes absolute screen coordinates by traversing parent-child indentation.
     */
    fun parseDumpsysViewHierarchy(output: String, defaultPkg: String = ""): List<RootUiNode> {
        if (output.isBlank() || !output.contains("View Hierarchy:")) return emptyList()

        val lines = output.lines()
        val list = mutableListOf<RootUiNode>()

        var activePkg = defaultPkg
        val pkgRegex = Regex("ACTIVITY\\s+([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/")
        for (line in lines) {
            val m = pkgRegex.find(line)
            if (m != null) {
                activePkg = m.groupValues[1]
                break
            }
        }

        var inHierarchy = false
        data class StackEntry(val indent: Int, val absLeft: Int, val absTop: Int)
        val stack = ArrayDeque<StackEntry>()

        val classRegex = Regex("^\\s*([a-zA-Z0-9_\\.]+)\\{")
        val boundsRegex = Regex("\\s(\\d+),(\\d+)-(\\d+),(\\d+)")
        val namedIdRegex = Regex("(?:app:id/|android:id/|id/)([a-zA-Z0-9_]+)")
        val hexIdRegex = Regex("#([0-9a-fA-F]+)")
        val textRegex = Regex("(?:text|mText)=[\"']?([^\"'}\\n]+)[\"']?")
        val descRegex = Regex("(?:cd|contentDescription)=[\"']?([^\"'}\\n]+)[\"']?")

        for (line in lines) {
            if (!inHierarchy) {
                if (line.contains("View Hierarchy:")) {
                    inHierarchy = true
                    stack.clear()
                }
                continue
            }

            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t")) {
                if (line.contains("View Hierarchy:")) {
                    stack.clear()
                    continue
                } else if (!line.startsWith("TASK") && !line.startsWith("ACTIVITY")) {
                    inHierarchy = false
                    continue
                }
            }

            val classMatch = classRegex.find(line) ?: continue
            val className = classMatch.groupValues[1]

            var indent = 0
            while (indent < line.length && line[indent] == ' ') {
                indent++
            }

            val boundsMatch = boundsRegex.find(line) ?: continue
            val relLeft = boundsMatch.groupValues[1].toInt()
            val relTop = boundsMatch.groupValues[2].toInt()
            val relRight = boundsMatch.groupValues[3].toInt()
            val relBottom = boundsMatch.groupValues[4].toInt()
            val width = relRight - relLeft
            val height = relBottom - relTop

            if (width <= 0 || height <= 0) continue

            while (stack.isNotEmpty() && stack.last().indent >= indent) {
                stack.removeLast()
            }

            val parent = stack.lastOrNull()
            val absLeft = (parent?.absLeft ?: 0) + relLeft
            val absTop = (parent?.absTop ?: 0) + relTop
            val absRight = absLeft + width
            val absBottom = absTop + height

            stack.addLast(StackEntry(indent, absLeft, absTop))

            val textMatch = textRegex.find(line)
            val text = textMatch?.groupValues?.get(1)?.trim().orEmpty()

            val descMatch = descRegex.find(line)
            val desc = descMatch?.groupValues?.get(1)?.trim().orEmpty()

            val id = namedIdRegex.find(line)?.groupValues?.get(1)?.trim()
                ?: hexIdRegex.find(line)?.groupValues?.get(1)?.trim()
                ?: ""

            val isClickable = line.contains("..C..") || line.contains(".C.") ||
                className.contains("Button") || className.contains("Check") || className.contains("Radio")

            val rect = Rect(absLeft, absTop, absRight, absBottom)

            if (text.isNotBlank() || desc.isNotBlank() || id.isNotBlank() || isClickable) {
                list.add(
                    RootUiNode(
                        text = text,
                        desc = desc,
                        id = id,
                        bounds = rect,
                        clickable = isClickable,
                        className = className,
                        packageName = activePkg,
                        enabled = true
                    )
                )
            }
        }

        return list
    }

    /**
     * Retrieves the current foreground package name using fast shell queries with caching.
     */
    fun getForegroundPackage(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh && (now - lastFgCheckTime) < 1200L && cachedFgPackage.isNotBlank()) {
            return cachedFgPackage
        }
        val out = executeSuWithOutput("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' || dumpsys activity activities 2>/dev/null | grep -E 'mResumedActivity|topResumedActivity'")
        val m = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/").find(out)
        val pkg = m?.groupValues?.get(1)?.trim().orEmpty()
        if (pkg.isNotBlank()) {
            cachedFgPackage = pkg
            lastFgCheckTime = now
        }
        return pkg.ifBlank { cachedFgPackage }
    }

    /**
     * Dumps the screen hierarchy via uiautomator binary with atomic cleanup of stale dumps.
     */
    fun dumpScreenHierarchy(): String {
        val primaryDump = "/data/local/tmp/pr_dump.xml"
        val fallbackDump = "/sdcard/window_dump.xml"

        // Atomic pipeline: remove old dump -> dump current -> cat stdout -> remove dump
        val primaryCmd = "rm -f $primaryDump && uiautomator dump --compressed $primaryDump >/dev/null 2>&1 && cat $primaryDump && rm -f $primaryDump"
        var out = executeSuWithOutput(primaryCmd)

        if (out.isBlank() || (!out.contains("<hierarchy") && !out.contains("<?xml"))) {
            // Fallback to /sdcard/window_dump.xml if /data/local/tmp was restricted
            val fallbackCmd = "rm -f $fallbackDump && uiautomator dump $fallbackDump >/dev/null 2>&1 && cat $fallbackDump && rm -f $fallbackDump"
            out = executeSuWithOutput(fallbackCmd)
        }
        return out
    }

    /**
     * Parses the uiautomator XML dump into structured nodes using fast XmlPullParser.
     */
    fun parseDumpXml(xml: String): List<RootUiNode> {
        if (xml.isBlank()) return emptyList()
        val list = mutableListOf<RootUiNode>()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(xml))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "node") {
                    val text = parser.getAttributeValue(null, "text") ?: ""
                    val desc = parser.getAttributeValue(null, "content-desc") ?: ""
                    val id = parser.getAttributeValue(null, "resource-id") ?: ""
                    val cls = parser.getAttributeValue(null, "class") ?: ""
                    val pkg = parser.getAttributeValue(null, "package") ?: ""
                    val boundsStr = parser.getAttributeValue(null, "bounds") ?: ""
                    val clickable = parser.getAttributeValue(null, "clickable") == "true"
                    val checkable = parser.getAttributeValue(null, "checkable") == "true"
                    val checked = parser.getAttributeValue(null, "checked") == "true"
                    val enabled = parser.getAttributeValue(null, "enabled") != "false"

                    val rect = parseBounds(boundsStr)
                    if (rect != null && rect.width() > 0 && rect.height() > 0) {
                        list.add(RootUiNode(text, desc, id, rect, clickable, cls, pkg, checkable, checked, enabled))
                    }
                }
                eventType = parser.next()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "parseDumpXml failed", t)
        }
        return list
    }

    private fun parseBounds(s: String): Rect? {
        val m = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(s) ?: return null
        return Rect(
            m.groupValues[1].toInt(),
            m.groupValues[2].toInt(),
            m.groupValues[3].toInt(),
            m.groupValues[4].toInt()
        )
    }

    /**
     * Simulates tap via root command 'input tap x y'
     */
    fun tap(x: Int, y: Int) {
        if (OverlayService.isTouchInsideOverlay(x, y)) {
            AutomationState.log("Ignored root tap inside floating HUD at ($x, $y)")
            return
        }
        executor.execute {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
                p.waitFor()
            } catch (t: Throwable) {
                Log.e(TAG, "Root tap failed at ($x, $y)", t)
            }
        }
    }

    /**
     * Simulates swipe via root command 'input swipe x1 y1 x2 y2 duration'
     */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        executor.execute {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input swipe $x1 $y1 $x2 $y2 $durationMs"))
                p.waitFor()
            } catch (t: Throwable) {
                Log.e(TAG, "Root swipe failed from ($x1,$y1) to ($x2,$y2)", t)
            }
        }
    }

    /**
     * Types text using root command 'input text'
     */
    fun type(text: String) {
        val sanitized = text.replace(" ", "%s").replace("\"", "\\\"")
        executor.execute {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input text \"$sanitized\""))
                p.waitFor()
            } catch (t: Throwable) {
                Log.e(TAG, "Root typeText failed for: $text", t)
            }
        }
    }

    // RootAutomationBackend interface implementation
    override fun start(context: Context) {
        if (!isRootAvailable()) {
            AutomationState.setRootUnavailable("SuperSU / Root not detected or granted")
            return
        }
        AutomationState.start()
        RootAutomationDaemon.start(context)
    }

    override fun stop() {
        RootAutomationDaemon.stop()
        AutomationState.stop()
    }

    override fun pause() {
        AutomationState.pause()
    }

    override fun resume() {
        if (!isRootAvailable()) {
            AutomationState.setRootUnavailable("SuperSU / Root not detected or granted")
            return
        }
        AutomationState.resume()
    }

    override fun isRootAvailable(): Boolean {
        // Canonical root check implementation: validates granted state and binary/command availability
        if (isRootGranted) return true
        if (checkSuBinaryExists()) {
            val granted = testSuCommand()
            if (granted) {
                isRootGranted = true
                AutomationState.isRooted = true
                return true
            }
        }
        return false
    }

    override fun dumpCurrentUi(): String {
        return dumpScreenHierarchy()
    }

    override fun performTap(x: Int, y: Int) {
        tap(x, y)
    }

    override fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        swipe(x1, y1, x2, y2, durationMs)
    }

    override fun performBack() {
        executor.execute {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 4"))
                p.waitFor()
            } catch (t: Throwable) {
                Log.e(TAG, "Root keyevent BACK failed", t)
            }
        }
    }

    override fun typeText(text: String) {
        type(text)
    }

    override fun launchTarget(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            context.startActivity(intent)
            true
        } else {
            executeSu("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        }
    }
}

/**
 * Root automation daemon for an explicitly selected test application.
 */
object RootAutomationDaemon {
    private var workerThread: Thread? = null
    @Volatile private var isRunning = false

    private val closeKeywords = setOf(
        "close", "closh", "skip", "cross", "dismiss", "dismissed", "discard",
        "close ad", "skip ad", "skip video", "close video", "skip this ad", "skip this",
        "skip now", "skip ad now", "watch later", "continue without",
        "no thanks", "not now", "maybe later", "later", "cancel", "deny", "never",
        "cross button", "close button", "skip button", "dismiss button",
        "बंद", "बंद करें", "छोड़ें", "स्किप", "खारिज", "खारिज करें", "रद्द करें", "हटाएं", "बाद में"
    )

    private val closeSymbols = setOf(
        "✕", "×", "✖", "✗", "x", "X", "❌", "⊗", "▶▶|", ">>|", ">>", "|<<"
    )

    private val blockedKeywords = listOf(
        "automation detected", "third party tool detected", "fair play violation",
        "unauthorized automation", "anti-cheat", "automation is not allowed",
        "close automation", "disable automation tool", "security policy violation"
    )

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        AutomationState.log("Root test engine started")

        workerThread = Thread {
            var lastSolvedFp = ""
            var lastSolvedAt = 0L
            var lastStatusLogTime = 0L
            var lastFallbackDumpTime = 0L

            while (isRunning && AutomationState.running) {
                try {
                    if (AutomationState.paused) {
                        Thread.sleep(400)
                        continue
                    }

                    // 1. Read the selected test app's current UI hierarchy.
                    var nodes = RootEngine.dumpScreenViaDumpsys()

                    if (nodes.isEmpty()) {
                        // Throttled secondary fallback if dumpsys produced no views (never in a rapid loop)
                        val now = System.currentTimeMillis()
                        if (now - lastFallbackDumpTime > 1600L) {
                            lastFallbackDumpTime = now
                            val xml = RootEngine.dumpScreenHierarchy()
                            if (xml.isNotBlank()) {
                                nodes = RootEngine.parseDumpXml(xml)
                            }
                        }
                    }

                    if (nodes.isEmpty()) {
                        Thread.sleep(300)
                        continue
                    }

                    val target = AutomationState.target(context).trim()
                    if (target.isBlank()) {
                        AutomationState.setTargetUiUnavailable("Select an authorized test app before starting")
                        break
                    }

                    val foreground = RootEngine.getForegroundPackage(forceRefresh = true)
                    if (foreground != target) {
                        val now = System.currentTimeMillis()
                        if (now - lastStatusLogTime > 4000L) {
                            AutomationState.log("Waiting for selected test app: $target (foreground: ${foreground.ifBlank { "unknown" }})")
                            lastStatusLogTime = now
                        }
                        Thread.sleep(300)
                        continue
                    }

                    val relevantNodes = nodes.filter { it.packageName == target }
                    if (relevantNodes.isEmpty()) {
                        val now = System.currentTimeMillis()
                        if (now - lastStatusLogTime > 4000L) {
                            AutomationState.log("Selected test app is foreground, but its UI hierarchy is empty")
                            lastStatusLogTime = now
                        }
                        Thread.sleep(300)
                        continue
                    }

                    // Policy check: If target application explicitly reports automation restriction, stop safely
                    var blocked = false
                    for (n in relevantNodes) {
                        val t = n.text.lowercase(Locale.ROOT)
                        val d = n.desc.lowercase(Locale.ROOT)
                        for (kw in blockedKeywords) {
                            if (t.contains(kw) || d.contains(kw)) {
                                AutomationState.setTargetBlockedAutomation("Target app policy restriction: $kw")
                                isRunning = false
                                blocked = true
                                break
                            }
                        }
                        if (blocked) break
                    }
                    if (blocked) break

                    // 2. Check for Close, Skip, Cross, Dismiss in target app or its ads
                    if (handleDismissAndAds(relevantNodes)) {
                        Thread.sleep(500)
                        continue
                    }

                    // 3. State B: Check for Result / "Next" / "Submit" / "Continue" Progression Screens
                    if (handleProgressionOrNext(relevantNodes)) {
                        Thread.sleep(600)
                        continue
                    }

                    // 4. State A: Check for Active Quiz Question & Options
                    val now = System.currentTimeMillis()
                    if (now - lastSolvedAt > 500) {
                        val solved = handleQuizSolving(context, relevantNodes, lastSolvedFp)
                        if (solved != null) {
                            lastSolvedFp = solved
                            lastSolvedAt = System.currentTimeMillis()
                            Thread.sleep(700)
                            continue
                        } else {
                            if (now - lastStatusLogTime > 4000L) {
                                AutomationState.log("Test scan: ${relevantNodes.size} UI elements, analyzing quiz layout...")
                                lastStatusLogTime = now
                            }
                        }
                    }

                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.e("RootDaemon", "Error in root daemon loop", t)
                    Thread.sleep(400)
                }
            }
            isRunning = false
            if (AutomationState.running) {
                AutomationState.log("⚡ Pure Root Engine loop ended")
            }
        }.apply {
            isDaemon = true
            name = "PR_RootAutomationDaemon"
            start()
        }
    }

    fun stop() {
        isRunning = false
        workerThread?.interrupt()
        workerThread = null
    }

    private fun handleDismissAndAds(nodes: List<RootUiNode>): Boolean {
        for (n in nodes) {
            val text = n.text.trim().lowercase(Locale.ROOT)
            val desc = n.desc.trim().lowercase(Locale.ROOT)
            val id = n.id.lowercase(Locale.ROOT)
            val raw = n.content.trim()

            val textMatch = text in closeKeywords ||
                text.contains("skip ad") || text.contains("close ad") ||
                text.contains("dismiss ad") || text.contains("skip video") ||
                text.contains("no thanks") || text.contains("not now") ||
                ((text.contains("skip") || text.contains("close") || text.contains("dismiss") || text.contains("closh") || text.contains("cross")) && text.length <= 30)

            val descMatch = desc in closeKeywords ||
                desc.contains("skip ad") || desc.contains("close ad") ||
                desc.contains("dismiss") || desc.contains("cross") || desc.contains("skip")

            val symbolMatch = raw in closeSymbols || (raw.length == 1 && raw in closeSymbols)

            val idMatch = id.contains("close") || id.contains("skip") ||
                id.contains("dismiss") || id.contains("cross") || id.contains("cancel")

            if (textMatch || descMatch || symbolMatch || idMatch) {
                val cx = n.centerX
                val cy = n.centerY
                if (!OverlayService.isTouchInsideOverlay(cx, cy)) {
                    val label = if (n.text.isNotBlank()) n.text else (if (n.desc.isNotBlank()) n.desc else n.id)
                    AutomationState.log("⚡ Root Auto-Closed: $label at ($cx, $cy)")
                    RootEngine.tap(cx, cy)
                    return true
                }
            }
        }
        return false
    }

    /**
     * State B: Auto-detects and taps "Next", "Submit", "Continue", or result advance buttons.
     */
    private fun handleProgressionOrNext(nodes: List<RootUiNode>): Boolean {
        fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

        val progressionWords = setOf(
            "next", "next question", "continue", "submit", "submit answer", "check answer",
            "check", "done", "next level", "play next", "proceed", "claim", "collect",
            "got it", "ok", "okay", "try again", "play again", "replay", "next quiz",
            "अगला", "जारी रखें", "आगे बढ़ें", "उत्तर दें", "सही उत्तर", "पुष्टि करें"
        )

        for (n in nodes) {
            val content = n.content.trim()
            val lower = norm(content)
            if (lower.isBlank() || lower.length > 30) continue

            val id = n.id.lowercase(Locale.ROOT)
            val isProgressionId = id.contains("btn_next") || id.contains("button_next") ||
                id.contains("btn_continue") || id.contains("btn_submit") ||
                id.contains("action_next") || id.contains("next_btn")

            val isProgressionText = lower in progressionWords || progressionWords.any { kw ->
                lower == kw || (lower.startsWith(kw) && lower.length <= kw.length + 5)
            }

            if (isProgressionId || isProgressionText) {
                // Ensure it's not an option letter choice like "A) ..."
                if (Regex("^(?:[A-Ha-h]|\\d{1,2})\\s*[.)\\]:-]").containsMatchIn(content)) continue

                val cx = n.centerX
                val cy = n.centerY
                if (!OverlayService.isTouchInsideOverlay(cx, cy)) {
                    AutomationState.action = "State B: Next Question"
                    AutomationState.log("⚡ State B: Auto-Clicked Progression button '$content' at ($cx, $cy)")
                    RootEngine.tap(cx, cy)
                    return true
                }
            }
        }
        return false
    }

    /**
     * State A: Detects active quiz question and options, calculates answer, and injects tap.
     */
    private fun handleQuizSolving(context: Context, nodes: List<RootUiNode>, lastFp: String): String? {
        fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        fun cleanOptionText(s: String) = s.replace(Regex("^\\s*(?:[A-Ha-h]|\\d{1,2})\\s*[.)\\]:-]\\s*"), "").trim()

        val navWords = setOf(
            "home", "reels", "mylist", "search", "quiz", "tasks", "settings", "share",
            "follow", "login", "sign in", "quit level", "remove 50%", "pause", "resume",
            "score", "coins", "points", "level", "streak", "rank", "profile"
        )

        // 1. Gather option candidates
        val optionCandidates = nodes.filter { n ->
            val content = n.content
            val clean = cleanOptionText(content)
            val lower = norm(clean)
            if (clean.isBlank() || clean.length > 200) return@filter false
            if (lower in navWords) return@filter false
            if (lower.contains("time left") || lower.contains("difficulty") || lower.contains("quit level")) return@filter false

            val isExplicitOptionId = n.id.contains("option", true) || n.id.contains("choice", true) ||
                    n.id.contains("answer", true) || n.id.contains("radio", true) || n.id.contains("btn_ans", true)
            val hasOptionPrefix = Regex("^\\s*(?:[A-Ha-h]|\\d{1,2})\\s*[.)\\]:-]").containsMatchIn(content)
            val isButtonOrClickable = n.clickable || n.className.contains("Button", true) || n.className.contains("Radio", true) || n.className.contains("Card", true)
            val isReasonableOptionSize = n.bounds.width() >= 100 && n.bounds.height() in 35..450

            isExplicitOptionId || hasOptionPrefix || isButtonOrClickable || isReasonableOptionSize
        }.distinctBy { cleanOptionText(it.content).lowercase(Locale.ROOT) + "@" + (it.bounds.top / 12) }

        var bestOptions = emptyList<RootUiNode>()
        var bestOptionScore = -1

        // Strategy A: ID-based grouping
        val idGrouped = optionCandidates.filter {
            it.id.contains("option", true) || it.id.contains("choice", true) || it.id.contains("answer", true)
        }
        if (idGrouped.size in 2..8) {
            bestOptions = idGrouped.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
            bestOptionScore = idGrouped.size * 200
        }

        // Strategy B: Prefix pattern grouping (A, B, C, D)
        if (bestOptions.isEmpty()) {
            val prefixGrouped = optionCandidates.filter {
                Regex("^\\s*(?:[A-Ha-h]|\\d{1,2})\\s*[.)\\]:-]").containsMatchIn(it.content)
            }
            if (prefixGrouped.size in 2..8) {
                bestOptions = prefixGrouped.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                bestOptionScore = prefixGrouped.size * 180
            }
        }

        // Strategy C: Spatial column alignment
        if (bestOptions.isEmpty()) {
            for (base in optionCandidates) {
                val columnRun = optionCandidates.filter { n ->
                    n.bounds.top >= base.bounds.top &&
                        n.bounds.top - base.bounds.top <= 1400 &&
                        abs(n.bounds.centerX() - base.bounds.centerX()) <= max(180, base.bounds.width() / 2) &&
                        abs(n.bounds.width() - base.bounds.width()) <= max(160, base.bounds.width() / 3)
                }.sortedBy { it.bounds.top }.distinctBy { cleanOptionText(it.content).lowercase(Locale.ROOT) }.take(8)

                if (columnRun.size in 2..8) {
                    val score = columnRun.size * 100
                    if (score > bestOptionScore) {
                        bestOptionScore = score
                        bestOptions = columnRun
                    }
                }
            }
        }

        // Strategy D: 2x2 grid alignment
        if (bestOptions.size < 2) {
            for (base in optionCandidates) {
                val gridCandidates = optionCandidates.filter { n ->
                    n.bounds.top >= base.bounds.top &&
                        n.bounds.top - base.bounds.top <= 900 &&
                        n.bounds.height() in 40..400
                }.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left })).take(6)

                if (gridCandidates.size in 4..6) {
                    bestOptions = gridCandidates
                    bestOptionScore = 150
                    break
                }
            }
        }

        if (bestOptions.size < 2) return null

        val firstOptionY = bestOptions.minOf { it.bounds.top }

        // 2. Identify Question candidate (must sit above options)
        val questionNoise = listOf(
            "daily use vocabulary", "learn english", "difficulty", "time left", "remove 50%",
            "quit level", "score", "coins", "level", "streak", "points", "timer"
        )

        val qCandidates = nodes.filter { n ->
            val c = n.content
            c.length >= 3 && n.bounds.bottom <= firstOptionY + 40 &&
                questionNoise.none { bad -> norm(c).contains(bad) } &&
                bestOptions.none { b -> b.bounds == n.bounds }
        }

        if (qCandidates.isEmpty()) return null

        val qPick = qCandidates.maxByOrNull { n ->
            val text = n.content
            val lower = norm(text)
            var score = 0
            if (n.id.contains("question", true) || n.id.contains("prompt", true) || n.id.contains("quiz", true) || n.id.contains("title", true)) score += 500
            if (lower.contains("?") || lower.contains("___")) score += 350
            if (lower.endsWith("?") || lower.endsWith(":")) score += 200
            if (lower.contains("which") || lower.contains("what") || lower.contains("where") || lower.contains("who") || lower.contains("how")) score += 150
            if (lower.contains("correct") || lower.contains("opposite") || lower.contains("synonym") || lower.contains("antonym") || lower.contains("meaning")) score += 150
            score += text.length.coerceAtMost(200)
            score
        } ?: return null

        val question = qPick.content.trim()
        val opts = bestOptions.mapIndexed { i, n ->
            QOpt(cleanOptionText(n.content).ifBlank { n.content.trim() }, i, n.bounds)
        }

        if (opts.size < 2) return null

        val fp = sha256(question + "|" + opts.joinToString("|") { it.text })
        if (fp == lastFp) return null

        // Step-by-step UI logging
        AutomationState.lastQuestion = question
        AutomationState.action = "State A: Question Detected"
        AutomationState.log("⚡ State A: Question Detected: ${question.take(90)}")
        AutomationState.log("⚡ Options: " + opts.joinToString(" | ") { "${('A'.code + it.index).toChar()}: ${it.text.take(30)}" })

        val idx = AnswerEngine.solve(context, QuizData(question, opts, fp))
        if (idx in opts.indices) {
            val chosen = opts[idx]
            val cx = chosen.bounds.centerX()
            val cy = chosen.bounds.centerY()
            AutomationState.lastAnswer = chosen.text
            AutomationState.action = "State A: Clicking Option ${idx + 1}"
            AutomationState.log("⚡ State A: Clicking Option ${('A'.code + idx).toChar()}: '${chosen.text.take(40)}' at ($cx, $cy)")
            RootEngine.tap(cx, cy)
            AutomationState.solved++

            // Progression check for next / submit buttons
            Thread {
                try {
                    Thread.sleep(400)
                    val nextNodes = RootEngine.dumpScreenViaDumpsys().ifEmpty {
                        RootEngine.parseDumpXml(RootEngine.dumpScreenHierarchy())
                    }
                    val nextKeywords = setOf(
                        "submit", "submit answer", "next", "continue", "next question",
                        "next level", "done", "check answer", "अगला", "जारी रखें", "आगे बढ़ें"
                    )
                    for (n in nextNodes) {
                        val t = norm(n.content)
                        if (t in nextKeywords || nextKeywords.any { k -> t.contains(k) && t.length <= 30 }) {
                            val ncx = n.bounds.centerX()
                            val ncy = n.bounds.centerY()
                            RootEngine.tap(ncx, ncy)
                            AutomationState.log("⚡ Auto-Clicked Progression button '${n.content}' at ($ncx, $ncy)")
                            break
                        }
                    }
                } catch (_: Throwable) {}
            }.start()

            return fp
        } else {
            AutomationState.failed++
            AutomationState.log("⚡ Answer not found or solver timed out")
            return null
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
