package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.engine.AutomationState
import com.example.engine.QOpt
import com.example.engine.QuizData
import com.example.engine.AnswerEngine
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class QuizAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: QuizAccessibilityService? = null
    }

    private var lastFp = ""
    private var lastAt = 0L
    private var solving = false
    private var scannerThread: Thread? = null
    private var lastCloseTapAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.packageNames = null // Listen to all packages so ads (Meta, Google AdMob, Unity) are caught
        serviceInfo = info
        AutomationState.log("Accessibility service connected • target=${AutomationState.target(this)}")
        startCrossScanner()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        // In pure root mode, accessibility service stands down completely
        if (com.example.engine.RootEngine.isRootGranted) return
        if (!AutomationState.running || AutomationState.paused) return
        val target = AutomationState.target(this)
        if (target.isBlank()) return
        // Strictly only operate inside target app
        if (!isTargetAppInForeground()) return

        val pkg = e?.packageName?.toString() ?: return
        if (pkg == packageName) return

        val now = SystemClock.uptimeMillis()

        // Immediate check to close any ad on screen inside target app
        val windows = runCatching { getWindows() }.getOrDefault(emptyList())
        for (w in windows) {
            val r = runCatching { w.root }.getOrNull() ?: continue
            if (r.packageName?.toString() == packageName) continue
            if (dismissCrossAndDialog(r)) {
                lastCloseTapAt = now
                return
            }
        }

        // Only solve questions when target app is active
        if (pkg != target) return
        if (solving) return
        if (now - lastAt < 120) return

        val root = targetRoot() ?: return

        // Fast randomized answer timing: strictly 1 to 5 seconds
        val answerWindowStart = now
        val answerWindowDelay = java.util.concurrent.ThreadLocalRandom.current().nextLong(1100L, 2900L)

        val q = extract(root) ?: return
        if (q.fingerprint == lastFp && now - lastAt < 2500) return
        lastFp = q.fingerprint
        solving = true
        AutomationState.lastQuestion = q.question
        AutomationState.action = "Reading question"
        AutomationState.log("Question: ${q.question.take(120)}")

        Thread {
            try {
                val idx = AnswerProvider.solve(this, q)
                if (idx !in q.options.indices) {
                    AutomationState.failed++
                    AutomationState.action = "Answer not found"
                    AutomationState.log("No verified answer found")
                    return@Thread
                }
                AutomationState.lastAnswer = q.options[idx].text
                AutomationState.action = "Answer ${idx + 1}"
                AutomationState.log("Answer: ${q.options[idx].text.take(100)}")

                val elapsed = SystemClock.uptimeMillis() - answerWindowStart
                val remaining = answerWindowDelay - elapsed
                if (remaining > 0) {
                    AutomationState.log("Fast answer timing: ${remaining}ms humanized wait")
                    Thread.sleep(remaining.coerceAtMost(3200L))
                } else {
                    AutomationState.log("Fast answer timing: AI ready (${elapsed}ms)")
                }
                if (!AutomationState.running || AutomationState.paused) return@Thread
                val root2 = targetRoot() ?: return@Thread
                if (root2.packageName?.toString() != target) {
                    AutomationState.action = "Target changed"
                    return@Thread
                }
                val fresh = extract(root2) ?: return@Thread
                val desired = norm(q.options[idx].text)
                val freshIdx = fresh.options.indexOfFirst { norm(it.text) == desired }
                val opt = if (freshIdx >= 0) fresh.options[freshIdx] else fresh.options.getOrNull(idx)
                if (opt == null) {
                    AutomationState.failed++
                    AutomationState.log("Answer option disappeared after AI result")
                    return@Thread
                }
                if (!click(opt)) {
                    AutomationState.failed++
                    AutomationState.action = "Answer click failed"
                    AutomationState.log("Click failed")
                    return@Thread
                }
                AutomationState.solved++
                lastAt = SystemClock.uptimeMillis()
                AutomationState.action = "Answer selected"
                AutomationState.log("Selected option ${idx + 1}")

                // Submit progression check
                for (i in 0..25) {
                    Thread.sleep(90)
                    val current = targetRoot() ?: continue
                    if (closePostSubmitOverlay(current)) continue
                    if (clickKeyword(current, "submit", "submit answer", "next", "continue", "next question", "next level", "done", "check answer", "verify", "wait to verify", "checking answer", "अगला", "अगला प्रश्न", "अगला लेवल", "जमा करें", "उत्तर जमा करें", "जाँचें")) {
                        AutomationState.action = "Submit / Next clicked"
                        AutomationState.log("Submit/Next clicked")
                        Thread.sleep(100)
                        break
                    }
                    val text = currentText(current)
                    if (text.contains("level complete") || text.contains("level completed") || text.contains("next level")) {
                        if (clickKeyword(current, "next level", "next level ", "अगला लेवल", "continue")) {
                            AutomationState.log("Next Level clicked")
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                AutomationState.failed++
                AutomationState.action = "Error: ${t.javaClass.simpleName}"
                AutomationState.log("Error: ${t.message?.take(100)}")
                Log.e("PRAutomation", "solve", t)
            } finally {
                solving = false
            }
        }.start()
    }

    override fun onInterrupt() {
        AutomationState.log("Accessibility interrupted")
    }

    override fun onDestroy() {
        scannerThread?.interrupt()
        scannerThread = null
        instance = null
        super.onDestroy()
    }

    private fun isTargetAppInForeground(): Boolean {
        val target = AutomationState.target(this)
        if (target.isBlank()) return false
        val windows = runCatching { getWindows() }.getOrDefault(emptyList())
        for (w in windows) {
            val r = runCatching { w.root }.getOrNull() ?: continue
            val pkg = r.packageName?.toString() ?: continue
            if (pkg == target && r.isVisibleToUser) {
                return true
            }
        }
        val active = runCatching { rootInActiveWindow }.getOrNull()
        return active?.let { it.packageName?.toString() == target && it.isVisibleToUser } ?: false
    }

    private fun targetRoot(): AccessibilityNodeInfo? {
        val target = AutomationState.target(this)
        if (target.isBlank()) return null
        val windows = runCatching { getWindows() }.getOrDefault(emptyList())
        for (w in windows) {
            val r = runCatching { w.root }.getOrNull() ?: continue
            if (r.packageName?.toString() == target && r.isVisibleToUser) {
                return r
            }
        }
        val active = runCatching { rootInActiveWindow }.getOrNull()
        return active?.takeIf { it.packageName?.toString() == target && it.isVisibleToUser }
    }

    /**
     * Dedicated background close & skip scanner.
     * Operates continuously every 100ms.
     * Guaranteed to detect and close:
     * - Meta Audience Network ads (white ✕ top right)
     * - Video ads (▶▶| skip top left)
     * - Circular dark button with white ✕
     * - STRICTLY only runs when target app is active in the foreground!
     */
    private fun startCrossScanner() {
        scannerThread?.interrupt()
        scannerThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    // In pure root mode, accessibility scanner stands down completely
                    if (com.example.engine.RootEngine.isRootGranted) {
                        Thread.sleep(1000)
                        continue
                    }
                    // Strictly never click or scan outside the target app
                    if (AutomationState.running && !AutomationState.paused && isTargetAppInForeground()) {
                        var handled = false

                        // 1. Accessibility node tree deep scan across all active windows of target app
                        val windows = runCatching { getWindows() }.getOrDefault(emptyList())
                        for (w in windows) {
                            val r = runCatching { w.root }.getOrNull() ?: continue
                            val pkg = r.packageName?.toString() ?: continue
                            if (pkg == packageName) continue // Never click our own app
                            if (dismissCrossAndDialog(r)) {
                                handled = true
                                lastCloseTapAt = SystemClock.uptimeMillis()
                                break
                            }
                        }

                        // 2. High-precision full-resolution corner scan (Screenshots 1, 2, 3)
                        if (!handled && SystemClock.uptimeMillis() - lastCloseTapAt > 500) {
                            if (scanAndTapGraphicalClose()) {
                                handled = true
                                lastCloseTapAt = SystemClock.uptimeMillis()
                            }
                        }
                    }
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                }
            }
        }.also { it.start() }
    }

    /**
     * Full-resolution graphical corner screenshot scan.
     * Directly analyzes:
     * 1) Top-Right Corner (Image 1 & Image 3: White ✕, Circular button with ✕)
     * 2) Top-Left Corner (Image 2: White ▶▶| Fast-Forward / Skip ad)
     * 3) Top-Left Corner (White ✕)
     */
    private fun scanAndTapGraphicalClose(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        if (!isTargetAppInForeground()) return false

        val executor = Executors.newSingleThreadExecutor()
        try {
            var hit: Pair<Int, Int>? = null

            // Build protected rectangles: ALL floating HUD views
            val protected = ArrayList<Rect>()
            protected.addAll(OverlayService.overlayBounds)

            val done = CountDownLatch(1)
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, executor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = result.hardwareBuffer ?: return
                        val src = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return
                        val bmp = src.copy(Bitmap.Config.ARGB_8888, false)
                        src.recycle()
                        hb.close()
                        hit = analyzeCornersForCloseOrSkip(bmp, protected)
                        bmp.recycle()
                    } catch (_: Throwable) {
                    } finally {
                        done.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    done.countDown()
                }
            })
            done.await(350, TimeUnit.MILLISECONDS)

            if (hit != null) {
                val hx = hit!!.first
                val hy = hit!!.second
                if (!OverlayService.isTouchInsideOverlay(hx, hy)) {
                    tap(hx, hy)
                    AutomationState.log("Auto-closed ad graphically at ($hx, $hy)")
                    return true
                }
            }
        } catch (_: Throwable) {
        } finally {
            executor.shutdownNow()
        }
        return false
    }

    /**
     * High-speed corner analyzer operating at full resolution.
     * Specifically built for Image 1, Image 2, and Image 3.
     */
    private fun analyzeCornersForCloseOrSkip(bitmap: Bitmap, protectedRects: List<Rect>): Pair<Int, Int>? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 200 || h < 200) return null

        val cornerHeight = min(h, (h * 0.22).toInt())
        val trBoxLeft = max(0, (w * 0.65).toInt())
        val tlBoxRight = min(w, (w * 0.35).toInt())

        fun blocked(x: Int, y: Int): Boolean {
            if (OverlayService.isTouchInsideOverlay(x, y)) return true
            return protectedRects.any { it.contains(x, y) }
        }

        fun intensity(c: Int): Int = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).toInt()

        // 1. Check Top-Right Corner for White Cross ✕ (Image 1 & Image 3)
        for (y in 24 until cornerHeight step 2) {
            for (x in trBoxLeft + 16 until w - 16 step 2) {
                if (blocked(x, y)) continue
                val c = bitmap.getPixel(x, y)
                if (intensity(c) >= 165) {
                    // Check for white cross arms around (x, y)
                    for (arm in intArrayOf(8, 11, 14, 18, 24, 30)) {
                        if (x - arm < 2 || y - arm < 2 || x + arm >= w - 2 || y + arm >= h - 2) continue
                        var diag1 = 0
                        var diag2 = 0
                        var axis = 0
                        var total = 0
                        for (k in -arm..arm) {
                            val cD1 = bitmap.getPixel(x + k, y + k)
                            val cD2 = bitmap.getPixel(x + k, y - k)
                            val cAx = bitmap.getPixel(x + k, y)
                            val cAy = bitmap.getPixel(x, y + k)
                            if (intensity(cD1) >= 155) diag1++
                            if (intensity(cD2) >= 155) diag2++
                            if (intensity(cAx) >= 155) axis++
                            if (intensity(cAy) >= 155) axis++
                            total++
                        }
                        val s1 = diag1.toDouble() / total
                        val s2 = diag2.toDouble() / total
                        val ax = axis.toDouble() / (total * 2.0)
                        if (s1 >= 0.58 && s2 >= 0.58 && abs(s1 - s2) <= 0.25 && ax <= 0.35) {
                            return Pair(x, y)
                        }
                    }
                }
            }
        }

        // 2. Check Top-Left Corner for Skip ▶▶| Icon (Image 2)
        for (y in 24 until cornerHeight step 2) {
            for (x in 16 until tlBoxRight - 16 step 2) {
                if (blocked(x, y)) continue
                val c = bitmap.getPixel(x, y)
                if (intensity(c) >= 170) {
                    // Check horizontal span of bright pixels ending with vertical terminal line
                    for (spanW in intArrayOf(16, 22, 28, 36, 48)) {
                        for (spanH in intArrayOf(12, 16, 22, 28)) {
                            val rightX = x + spanW
                            if (rightX >= w - 2 || y + spanH >= h - 2) continue
                            var barPoints = 0
                            for (yy in y until y + spanH) {
                                val cBar = bitmap.getPixel(rightX, yy)
                                if (intensity(cBar) >= 155) barPoints++
                            }
                            val barRatio = barPoints.toDouble() / spanH
                            if (barRatio >= 0.70) {
                                return Pair(x + spanW / 2, y + spanH / 2)
                            }
                        }
                    }
                }
            }
        }

        // 3. Check Top-Left Corner for White Cross ✕
        for (y in 24 until cornerHeight step 2) {
            for (x in 16 until tlBoxRight - 16 step 2) {
                if (blocked(x, y)) continue
                val c = bitmap.getPixel(x, y)
                if (intensity(c) >= 165) {
                    for (arm in intArrayOf(8, 11, 14, 18, 24)) {
                        if (x - arm < 2 || y - arm < 2 || x + arm >= w - 2 || y + arm >= h - 2) continue
                        var diag1 = 0
                        var diag2 = 0
                        var axis = 0
                        var total = 0
                        for (k in -arm..arm) {
                            val cD1 = bitmap.getPixel(x + k, y + k)
                            val cD2 = bitmap.getPixel(x + k, y - k)
                            val cAx = bitmap.getPixel(x + k, y)
                            val cAy = bitmap.getPixel(x, y + k)
                            if (intensity(cD1) >= 155) diag1++
                            if (intensity(cD2) >= 155) diag2++
                            if (intensity(cAx) >= 155) axis++
                            if (intensity(cAy) >= 155) axis++
                            total++
                        }
                        val s1 = diag1.toDouble() / total
                        val s2 = diag2.toDouble() / total
                        val ax = axis.toDouble() / (total * 2.0)
                        if (s1 >= 0.58 && s2 >= 0.58 && abs(s1 - s2) <= 0.25 && ax <= 0.35) {
                            return Pair(x, y)
                        }
                    }
                }
            }
        }

        return null
    }

    private fun extract(root: AccessibilityNodeInfo): QuizData? {
        data class N(val t: String, val r: Rect, val n: AccessibilityNodeInfo, val click: AccessibilityNodeInfo?)
        val list = ArrayList<N>()
        fun clickParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var p: AccessibilityNodeInfo? = n
            repeat(7) {
                val x = p ?: return null
                if (x.isClickable || x.isCheckable || x.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }) return x
                val cls = x.className?.toString().orEmpty()
                if (cls.contains("Button", true) || cls.contains("RadioButton", true) || cls.contains("CheckBox", true) || cls.contains("MaterialButton", true)) return x
                p = x.parent
            }
            return null
        }
        fun walk(n: AccessibilityNodeInfo) {
            if (n.isVisibleToUser) {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrBlank() && t.length <= 420) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0 && !noise(t)) {
                        list.add(N(t, r, n, clickParent(n)))
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        if (list.size < 3) return null

        fun optionText(s: String) = s.replace(Regex("^\\s*(?:[A-Ha-h]|\\d{1,2})\\s*[.)\\]:-]\\s*"), "").trim()
        val nav = setOf("home", "reels", "mylist", "search", "quiz", "tasks", "settings", "share", "follow", "login", "sign in", "quit level", "remove 50% wrong options", "remove two wrong options")
        val optionCandidates = list.filter { n ->
            val t = norm(optionText(n.t))
            t.length in 1..180 && t !in nav && n.click != null &&
                    !t.contains("time left") && !t.contains("difficulty")
        }.distinctBy { norm(optionText(it.t)) + "@" + it.r.top / 8 + "@" + it.r.left / 8 }

        var best = emptyList<N>()
        var bestScore = -1
        for (base in optionCandidates) {
            val run = optionCandidates.filter { n ->
                n.r.top >= base.r.top &&
                        n.r.top - base.r.top <= 1100 &&
                        abs(n.r.centerX() - base.r.centerX()) <= max(140, base.r.width() / 2) &&
                        abs(n.r.width() - base.r.width()) <= max(220, base.r.width())
            }.sortedBy { it.r.top }.distinctBy { norm(optionText(it.t)) }.take(8)
            if (run.size >= 2) {
                val gaps = run.zipWithNext().map { it.second.r.top - it.first.r.bottom }
                val sane = gaps.count { it <= 360 } >= run.size - 1
                if (!sane) continue
                var score = run.size * 150
                score -= max(0, (run.last().r.top - run.first().r.top) - 900) / 10
                if (run.size >= 3) score += 80
                if (run.size >= 4) score += 80
                if (score > bestScore) {
                    bestScore = score
                    best = run
                }
            }
        }
        if (best.size < 2) return null
        val firstOptionY = best.minOf { it.r.top }

        val questionNoise = listOf(
            "daily use vocabulary", "learn english", "questions for level", "do not repeat",
            "difficulty", "time left", "remove 50%", "quit level", "share your win",
            "back to quiz home", "mylist", "reels", "search", "tasks"
        )
        val qCandidates = list.filter { n ->
            n.r.bottom <= firstOptionY && firstOptionY - n.r.bottom <= 720 && n.t.length >= 3 &&
                    questionNoise.none { bad -> norm(n.t).contains(bad) } &&
                    best.none { b -> b.n == n.n }
        }
        if (qCandidates.isEmpty()) return null

        val qPick = qCandidates.maxByOrNull { n ->
            val x = norm(n.t)
            var score = 0
            score += max(0, 520 - (firstOptionY - n.r.bottom)) / 3
            if (x.contains("?") || x.contains("___") || x.contains("____") || x.contains("blank") || x.contains("fill in")) score += 320
            if (x.endsWith("?") || x.endsWith(":")) score += 100
            if (x.length >= 18) score += 50
            if (x.length > 260) score -= 120
            score
        } ?: return null

        val qTop = qPick.r.top
        val qParts = qCandidates.filter { it.r.top >= qTop - 230 && it.r.bottom <= firstOptionY + 4 }
            .sortedBy { it.r.top }
        val question = qParts.joinToString(" ") { it.t }.trim().ifBlank { qPick.t }
        if (question.length < 4) return null

        val opts = best.sortedBy { it.r.top }
            .distinctBy { norm(optionText(it.t)) + "@" + it.r.top / 10 + "@" + it.r.left / 10 }
            .take(8).mapIndexed { i, n ->
                val r = Rect(n.r)
                val cn = n.click
                if (cn != null) cn.getBoundsInScreen(r)
                QOpt(optionText(n.t), i, r, cn ?: n.n)
            }
        if (opts.size < 2) return null
        return QuizData(question, opts, sha(question + "|" + opts.joinToString("|") { it.text }))
    }

    private fun noise(s: String): Boolean {
        val x = norm(s)
        if (x in setOf("next", "continue", "submit", "done", "close", "cancel", "settings", "menu", "share", "follow", "login", "sign in", "like", "home", "search", "play", "skip", "not now", "no thanks", "back", "quit level", "remove 50% wrong options")) return true
        if (x.contains("install now") || x.contains("minipix")) return true
        return x.length < 2
    }

    private fun closeObvious(root: AccessibilityNodeInfo): Boolean = dismissCrossAndDialog(root)

    /**
     * Node-based deep detection of cross, close, and skip controls.
     * Guaranteed to match:
     * 1) Exact close/skip keywords and symbols
     * 2) Resource IDs
     * 3) Clickable corner buttons in top-right and top-left
     */
    private fun dismissCrossAndDialog(root: AccessibilityNodeInfo): Boolean {
        val exactCloseKeywords = setOf(
            "close", "closh", "skip", "cross", "dismiss", "dismissed", "discard",
            "close ad", "skip ad", "skip video", "close video", "skip this ad", "skip this",
            "skip now", "skip ad now", "watch later", "continue without",
            "no thanks", "not now", "maybe later", "later", "cancel", "deny", "never",
            "cross button", "close button", "skip button", "dismiss button",
            "बंद", "बंद करें", "छोड़ें", "स्किप", "खारिज", "खारिज करें", "रद्द करें", "हटाएं", "बाद में"
        )

        val exactSymbols = setOf(
            "✕", "×", "✖", "✗", "x", "X", "❌", "⊗", "ⓧ", "⨉", "⨵",
            "▶▶|", "▶▶", "⏭", ">>|", ">>", "|<<"
        )

        fun isExplicitCloseOrSkipId(id: String): Boolean {
            val rid = id.lowercase(Locale.ROOT).substringAfterLast('/')
            return rid in setOf(
                "btn_close", "close_btn", "iv_close", "img_close", "icon_close",
                "action_close", "close_button", "button_close", "dialog_close",
                "interstitial_close", "ad_close", "tt_close", "ksad_close",
                "mraid_close", "btn_skip", "skip_btn", "iv_skip", "img_skip",
                "skip_button", "button_skip", "ad_skip", "video_skip",
                "btn_dismiss", "dismiss_btn", "btn_cross", "cross_btn"
            ) || rid.contains("close") || rid.contains("skip") || rid.contains("dismiss") || rid.contains("cross") || rid.contains("cancel")
        }

        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            all.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)

        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val sh = dm.heightPixels

        fun attemptClick(n: AccessibilityNodeInfo, label: String): Boolean {
            if (!n.isVisibleToUser) return false
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) return false
            val cx = r.centerX()
            val cy = r.centerY()
            // Strictly protect floating HUD
            if (OverlayService.isTouchInsideOverlay(cx, cy)) return false

            if (n.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                AutomationState.log("Auto-dismissed: $label")
                return true
            }
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                AutomationState.log("Auto-closed: $label")
                return true
            }
            var p = n.parent
            repeat(5) {
                val pp = p ?: return@repeat
                if (pp.isVisibleToUser && pp.isClickable && pp.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    AutomationState.log("Auto-closed via parent: $label")
                    return true
                }
                p = pp.parent
            }
            // Coordinate tap fallback
            if (r.width() in 10..380 && r.height() in 10..380) {
                tap(cx, cy)
                AutomationState.log("Auto-tapped close/skip: $label at ($cx, $cy)")
                return true
            }
            return false
        }

        // Pass 1: Keyword, symbol, or ID matches anywhere on screen
        for (n in all) {
            if (!n.isVisibleToUser) continue
            val raw = (n.text ?: n.contentDescription ?: "").toString().trim()
            val label = norm(raw)
            val rid = (n.viewIdResourceName ?: "").lowercase(Locale.ROOT)
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() <= 0 || r.height() <= 0) continue
            if (OverlayService.isTouchInsideOverlay(r.centerX(), r.centerY())) continue

            val phraseMatch = label in exactCloseKeywords ||
                label.contains("skip ad") || label.contains("close ad") ||
                label.contains("dismiss ad") || label.contains("skip video") ||
                label.contains("close video") || label.contains("no thanks") ||
                label.contains("not now") ||
                ((label.contains("skip") || label.contains("close") || label.contains("dismiss") || label.contains("closh") || label.contains("cross")) && label.length <= 30)

            val symbolMatch = raw in exactSymbols || (raw.length == 1 && raw in exactSymbols)
            val idMatch = isExplicitCloseOrSkipId(rid)

            if (phraseMatch || symbolMatch || idMatch) {
                if (attemptClick(n, if (label.isNotBlank()) label else rid)) return true
            }
        }

        // Pass 2: Extreme top corner buttons (covers Meta Audience Network, AdMob, WebViews without text)
        for (n in all) {
            if (!n.isVisibleToUser) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() !in 16..360 || r.height() !in 16..360) continue
            if (OverlayService.isTouchInsideOverlay(r.centerX(), r.centerY())) continue

            // Top-Right corner (Image 1 Meta Ad & Image 3 KuCoin Ad)
            val isTopRightCorner = (r.top < sh * 0.18) && (r.right > sw * 0.70)
            // Top-Left corner (Image 2 Bitunix Skip Ad)
            val isTopLeftCorner = (r.top < sh * 0.18) && (r.left < sw * 0.32)

            if (isTopRightCorner || isTopLeftCorner) {
                val isClickable = n.isClickable || n.parent?.isClickable == true || n.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                val raw = (n.text ?: "").toString().trim()
                val desc = norm((n.contentDescription ?: "").toString())

                // Ignore volume/mute button
                if (desc.contains("mute") || desc.contains("sound") || desc.contains("volume") || desc.contains("audio")) continue

                // Top corner button with short or no text is the ad close/skip control!
                if (isClickable && (raw.isBlank() || raw.length <= 4 || raw in exactSymbols || norm(raw) in exactCloseKeywords)) {
                    if (attemptClick(n, if (isTopRightCorner) "Top-Right Ad Close Control" else "Top-Left Ad Skip Control")) {
                        return true
                    }
                }
            }
        }

        return false
    }

    private fun closePostSubmitOverlay(root: AccessibilityNodeInfo): Boolean = dismissCrossAndDialog(root)

    private fun click(o: QOpt): Boolean {
        val n = o.node
        if (n != null) {
            if (n.isCheckable && n.performAction(AccessibilityNodeInfo.ACTION_SELECT)) return true
            if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            var p = n.parent
            repeat(7) {
                if (p?.isVisibleToUser == true) {
                    if (p?.isCheckable == true && p?.performAction(AccessibilityNodeInfo.ACTION_SELECT) == true) return true
                    if (p?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                }
                p = p?.parent
            }
        }
        val r = o.bounds
        if (r.width() > 0 && r.height() > 0) {
            tap(r.centerX(), r.centerY())
            return true
        }
        return false
    }

    private fun currentText(root: AccessibilityNodeInfo): String {
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            all.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        return all.joinToString(" ") { norm((it.text ?: it.contentDescription ?: "").toString()) }
    }

    private fun clickKeyword(root: AccessibilityNodeInfo?, vararg keys: String): Boolean {
        if (root == null) return false
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            all.add(n)
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        walk(root)
        val normalizedKeys = keys.map { norm(it) }.filter { it.isNotBlank() }.distinct()
        for (pass in 0..1) {
            for (k in normalizedKeys) {
                val matches = all.filter { n ->
                    if (!n.isVisibleToUser) return@filter false
                    val t = norm((n.text ?: n.contentDescription ?: "").toString())
                    if (t.isBlank()) return@filter false
                    if (pass == 0) t == k else (t.contains(k) && t.length <= 90)
                }.sortedWith(compareByDescending<AccessibilityNodeInfo> { it.isClickable || it.isCheckable })
                for (n in matches) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    if (OverlayService.isTouchInsideOverlay(r.centerX(), r.centerY())) continue

                    if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    var p = n.parent
                    repeat(6) {
                        if (p?.isVisibleToUser == true && p?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
                        p = p?.parent
                    }
                    if (r.width() > 0 && r.height() > 0) {
                        tap(r.centerX(), r.centerY())
                        return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Dispatches a gesture tap at screen coordinates (x, y).
     * Strictly verifies the coordinates do not touch our floating HUD.
     */
    private fun tap(x: Int, y: Int) {
        if (OverlayService.isTouchInsideOverlay(x, y)) {
            AutomationState.log("Ignored tap inside floating HUD at ($x, $y)")
            return
        }
        if (com.example.engine.RootEngine.isRootGranted) {
            com.example.engine.RootEngine.tap(x, y)
            return
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    object AnswerProvider {
        fun solve(c: Context, q: QuizData): Int = AnswerEngine.solve(c, q)
    }
}

private fun norm(s: String) = s.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
