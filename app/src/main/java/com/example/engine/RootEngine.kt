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
    val clickable: Boolean
)

object RootEngine : RootAutomationBackend {
    private const val TAG = "PR_RootEngine"
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile var isSuBinaryPresent: Boolean = false
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
     * Retrieves the current foreground package name using root shell dumpsys
     */
    fun getForegroundPackage(): String {
        val out = executeSuWithOutput("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        if (out.isNotBlank()) {
            val m = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/").find(out)
            if (m != null) return m.groupValues[1]
        }
        val out2 = executeSuWithOutput("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
        if (out2.isNotBlank()) {
            val m2 = Regex("([a-zA-Z0-9_]+(?:\\.[a-zA-Z0-9_]+)+)/").find(out2)
            if (m2 != null) return m2.groupValues[1]
        }
        return ""
    }

    /**
     * Dumps the screen hierarchy via uiautomator binary
     */
    fun dumpScreenHierarchy(): String {
        val dumpFile = "/data/local/tmp/pr_dump.xml"
        executeSu("uiautomator dump --compressed $dumpFile")
        return executeSuWithOutput("cat $dumpFile")
    }

    /**
     * Parses the uiautomator XML dump into structured nodes
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
                    val boundsStr = parser.getAttributeValue(null, "bounds") ?: ""
                    val clickable = parser.getAttributeValue(null, "clickable") == "true"

                    val rect = parseBounds(boundsStr)
                    if (rect != null && rect.width() > 0 && rect.height() > 0) {
                        list.add(RootUiNode(text, desc, id, rect, clickable))
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
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap $x $y"))
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
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input swipe $x1 $y1 $x2 $y2 $durationMs"))
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
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input text \"$sanitized\""))
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
        RootAutomationDaemon.start(context)
        AutomationState.start()
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
                Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 4"))
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
 * Pure ROOT Automation Daemon.
 * Operates without turning on, requesting, or checking Android Accessibility Service.
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
        AutomationState.log("⚡ Pure Root Engine started (No Accessibility)")

        workerThread = Thread {
            var lastSolvedFp = ""
            var lastSolvedAt = 0L
            var consecutiveDumpFailures = 0

            while (isRunning && AutomationState.running) {
                try {
                    if (AutomationState.paused) {
                        Thread.sleep(400)
                        continue
                    }
                    val target = AutomationState.target(context)
                    if (target.isBlank()) {
                        Thread.sleep(500)
                        continue
                    }

                    // STRICT: Only operate inside the target app
                    val fg = RootEngine.getForegroundPackage()
                    if (fg != target) {
                        Thread.sleep(500)
                        continue
                    }

                    val xml = RootEngine.dumpScreenHierarchy()
                    if (xml.isBlank()) {
                        consecutiveDumpFailures++
                        if (consecutiveDumpFailures >= 6) {
                            AutomationState.setTargetUiUnavailable("Target UI hierarchy cannot be dumped by root backend")
                            isRunning = false
                            break
                        }
                        Thread.sleep(300)
                        continue
                    }
                    consecutiveDumpFailures = 0

                    val nodes = RootEngine.parseDumpXml(xml)
                    if (nodes.isEmpty()) {
                        Thread.sleep(300)
                        continue
                    }

                    // Policy check: If target application explicitly reports automation restriction,
                    // do NOT attempt to bypass or spoof. Report condition and stop.
                    var blocked = false
                    for (n in nodes) {
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

                    // 1. Check for Close, Skip, Cross, Dismiss in target app or its ads
                    if (handleDismissAndAds(nodes)) {
                        Thread.sleep(500)
                        continue
                    }

                    // 2. Check for Quiz Question & Options
                    val now = System.currentTimeMillis()
                    if (now - lastSolvedAt > 700) {
                        val solved = handleQuizSolving(context, nodes, lastSolvedFp)
                        if (solved != null) {
                            lastSolvedFp = solved
                            lastSolvedAt = System.currentTimeMillis()
                            Thread.sleep(1100)
                            continue
                        }
                    }

                    Thread.sleep(250)
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
            val raw = n.text.trim()

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
                val cx = n.bounds.centerX()
                val cy = n.bounds.centerY()
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

    private fun handleQuizSolving(context: Context, nodes: List<RootUiNode>, lastFp: String): String? {
        fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        fun optionText(s: String) = s.replace(Regex("^\\s*(?:[A-Ha-h]|\\d{1,2})\\s*[.)\\]:-]\\s*"), "").trim()

        val nav = setOf("home", "reels", "mylist", "search", "quiz", "tasks", "settings", "share", "follow", "login", "sign in", "quit level", "remove 50% wrong options")

        val optionCandidates = nodes.filter { n ->
            val t = norm(optionText(n.text))
            t.length in 1..180 && t !in nav && n.clickable &&
                !t.contains("time left") && !t.contains("difficulty")
        }.distinctBy { norm(optionText(it.text)) + "@" + it.bounds.top / 8 }

        var best = emptyList<RootUiNode>()
        var bestScore = -1
        for (base in optionCandidates) {
            val run = optionCandidates.filter { n ->
                n.bounds.top >= base.bounds.top &&
                    n.bounds.top - base.bounds.top <= 1100 &&
                    abs(n.bounds.centerX() - base.bounds.centerX()) <= max(140, base.bounds.width() / 2)
            }.sortedBy { it.bounds.top }.distinctBy { norm(optionText(it.text)) }.take(8)

            if (run.size >= 2) {
                val score = run.size * 100
                if (score > bestScore) {
                    bestScore = score
                    best = run
                }
            }
        }

        if (best.size < 2) return null
        val firstOptionY = best.minOf { it.bounds.top }

        val questionNoise = listOf("daily use vocabulary", "learn english", "difficulty", "time left", "remove 50%", "quit level")
        val qCandidates = nodes.filter { n ->
            n.bounds.bottom <= firstOptionY && firstOptionY - n.bounds.bottom <= 720 && n.text.length >= 3 &&
                questionNoise.none { bad -> norm(n.text).contains(bad) } &&
                best.none { b -> b.bounds == n.bounds }
        }
        if (qCandidates.isEmpty()) return null

        val qPick = qCandidates.maxByOrNull { n ->
            val x = norm(n.text)
            var score = 0
            if (x.contains("?") || x.contains("___")) score += 300
            if (x.endsWith("?") || x.endsWith(":")) score += 100
            score += n.text.length
            score
        } ?: return null

        val question = qPick.text.trim()
        val opts = best.sortedBy { it.bounds.top }.mapIndexed { i, n ->
            QOpt(optionText(n.text), i, n.bounds)
        }
        if (opts.size < 2) return null

        val fp = sha256(question + "|" + opts.joinToString("|") { it.text })
        if (fp == lastFp) return null

        AutomationState.lastQuestion = question
        AutomationState.action = "Root: Solving question"
        AutomationState.log("⚡ Root Question: ${question.take(100)}")

        val idx = AnswerEngine.solve(context, QuizData(question, opts, fp))
        if (idx in opts.indices) {
            val chosen = opts[idx]
            AutomationState.lastAnswer = chosen.text
            AutomationState.action = "Root: Answer ${idx + 1}"
            AutomationState.log("⚡ Root Answer: ${chosen.text.take(80)}")
            RootEngine.tap(chosen.bounds.centerX(), chosen.bounds.centerY())
            AutomationState.solved++

            // Progression check for next / submit buttons
            Thread {
                try {
                    Thread.sleep(250)
                    val nextXml = RootEngine.dumpScreenHierarchy()
                    val nextNodes = RootEngine.parseDumpXml(nextXml)
                    val nextKeywords = setOf("submit", "submit answer", "next", "continue", "next question", "next level", "done", "check answer", "अगला", "जारी रखें")
                    for (n in nextNodes) {
                        val t = norm(n.text)
                        if (t in nextKeywords || nextKeywords.any { k -> t.contains(k) && t.length <= 30 }) {
                            RootEngine.tap(n.bounds.centerX(), n.bounds.centerY())
                            AutomationState.log("⚡ Root Next/Submit clicked")
                            break
                        }
                    }
                } catch (_: Throwable) {}
            }.start()

            return fp
        } else {
            AutomationState.failed++
            AutomationState.log("⚡ Root: Answer not found")
            return null
        }
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
