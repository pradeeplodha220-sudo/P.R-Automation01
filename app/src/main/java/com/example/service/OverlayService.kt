package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.engine.AutomationState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class OverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var active = false
        @Volatile var isPanelOpen = false
        @Volatile var overlayBounds: List<Rect> = emptyList()

        fun isTouchInsideOverlay(x: Int, y: Int): Boolean {
            if (!active) return false
            val list = overlayBounds
            for (i in list.indices) {
                if (list[i].contains(x, y)) return true
            }
            return false
        }
    }

    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "pr_red_channel",
                "P.R Automation Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        runCatching { startForeground(4101, buildNotification()) }
        active = true
        showBubble()
    }

    private fun lp(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    // FULL RED Theme: Glowing red circular bubble
    private fun bubbleBg() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        colors = intArrayOf(Color.rgb(255, 23, 68), Color.rgb(180, 0, 20))
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = dp(28).toFloat()
        setStroke(dp(2), Color.rgb(255, 100, 120))
    }

    // FULL RED Theme: Dark obsidian red panel with fiery red glowing stroke
    private fun panelBg() = GradientDrawable().apply {
        colors = intArrayOf(Color.rgb(26, 6, 9), Color.rgb(15, 3, 5))
        gradientType = GradientDrawable.LINEAR_GRADIENT
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        cornerRadius = dp(16).toFloat()
        setStroke(dp(2), Color.rgb(255, 23, 68))
    }

    private fun buttonBg(colorStart: Int, colorEnd: Int) = GradientDrawable().apply {
        colors = intArrayOf(colorStart, colorEnd)
        orientation = GradientDrawable.Orientation.TOP_BOTTOM
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), Color.rgb(255, 70, 90))
    }

    private fun styleButton(b: Button, colorStart: Int = Color.rgb(140, 10, 25), colorEnd: Int = Color.rgb(75, 4, 12)) {
        b.textSize = 12f
        b.minHeight = dp(38)
        b.minimumHeight = dp(38)
        b.setPadding(dp(6), 0, dp(6), 0)
        b.isAllCaps = false
        b.setTextColor(Color.WHITE)
        b.background = buttonBg(colorStart, colorEnd)
    }

    private fun refreshOverlayBounds() {
        val list = ArrayList<Rect>()
        bubbleParams?.let { bp ->
            val bw = bubble?.width?.takeIf { it > 0 } ?: dp(48)
            val bh = bubble?.height?.takeIf { it > 0 } ?: dp(48)
            list.add(Rect(bp.x - dp(12), bp.y - dp(12), bp.x + bw + dp(12), bp.y + bh + dp(12)))
        }
        if (isPanelOpen) {
            panelParams?.let { pp ->
                val pw = panel?.width?.takeIf { it > 0 } ?: dp(290)
                val ph = panel?.height?.takeIf { it > 0 } ?: dp(320)
                list.add(Rect(pp.x - dp(14), pp.y - dp(14), pp.x + pw + dp(14), pp.y + ph + dp(14)))
            }
        }
        overlayBounds = list
    }

    private fun drag(view: View, params: WindowManager.LayoutParams, target: View, tap: (() -> Unit)? = null) {
        var downX = 0f
        var downY = 0f
        var sx = 0
        var sy = 0
        var moved = false
        view.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                val e = event ?: return false
                return when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        sx = params.x
                        sy = params.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                        val dm = resources.displayMetrics
                        val maxX = max(0, dm.widthPixels - target.width - dp(4))
                        val maxY = max(0, dm.heightPixels - target.height - dp(24))
                        params.x = max(0, min(maxX, sx + dx))
                        params.y = max(0, min(maxY, sy + dy))
                        runCatching { wm.updateViewLayout(target, params) }
                        refreshOverlayBounds()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        refreshOverlayBounds()
                        if (!moved) tap?.invoke()
                        true
                    }
                    else -> true
                }
            }
        })
    }

    private fun showBubble() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        bubble?.let { runCatching { wm.removeView(it) } }
        val v = TextView(this).apply {
            text = "P.R"
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = bubbleBg()
            elevation = dp(10).toFloat()
        }
        val p = lp(dp(48), dp(48))
        p.x = dp(14)
        p.y = dp(240)
        bubble = v
        bubbleParams = p
        v.post {
            drag(v, p, v) { showPanel() }
            refreshOverlayBounds()
        }
        runCatching { wm.addView(v, p) }
        refreshOverlayBounds()
    }

    private fun showPanel() {
        if (panel != null) return
        isPanelOpen = true
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = panelBg()
            elevation = dp(16).toFloat()
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "P.R AUTOMATION"
            textSize = 15f
            setTextColor(Color.rgb(255, 60, 85))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, 0, 0)
        }
        head.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))
        val close = Button(this).apply {
            text = "—"
            textSize = 14f
            minWidth = dp(34)
            minimumWidth = dp(34)
            minHeight = dp(34)
            minimumHeight = dp(34)
            setPadding(0, 0, 0, 0)
            setTextColor(Color.WHITE)
            background = buttonBg(Color.rgb(180, 0, 30), Color.rgb(100, 0, 15))
            setOnClickListener { hidePanel() }
        }
        head.addView(close, LinearLayout.LayoutParams(dp(34), dp(34)))
        r.addView(head)

        val st = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(255, 215, 220))
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        r.addView(st)

        fun add(t: String, colorStart: Int, colorEnd: Int, f: () -> Unit) {
            val b = Button(this).apply {
                text = t
                styleButton(this, colorStart, colorEnd)
                setOnClickListener {
                    f()
                    update(st)
                }
            }
            r.addView(b, LinearLayout.LayoutParams(-1, dp(40)).apply {
                topMargin = dp(2)
                bottomMargin = dp(3)
            })
        }

        add("▶ START", Color.rgb(215, 0, 35), Color.rgb(140, 0, 20)) {
            com.example.engine.RootEngine.start(this)
        }
        add("⏸ PAUSE", Color.rgb(170, 70, 0), Color.rgb(110, 45, 0)) { com.example.engine.RootEngine.pause() }
        add("▶ RESUME", Color.rgb(200, 25, 45), Color.rgb(120, 10, 25)) {
            com.example.engine.RootEngine.resume()
        }
        add("■ STOP", Color.rgb(90, 10, 18), Color.rgb(50, 5, 10)) {
            com.example.engine.RootEngine.stop()
        }
        add("— MINIMIZE", Color.rgb(45, 15, 20), Color.rgb(25, 8, 12)) { hidePanel() }

        val p = lp(dp(290), WindowManager.LayoutParams.WRAP_CONTENT)
        p.x = dp(16)
        p.y = dp(140)
        panel = r
        panelParams = p
        r.post {
            drag(head, p, r)
            refreshOverlayBounds()
        }
        runCatching { wm.addView(r, p) }
        refreshOverlayBounds()
        update(st)

        updateRunnable = object : Runnable {
            override fun run() {
                if (panel != null) {
                    update(st)
                    handler.postDelayed(this, 600)
                }
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun update(st: TextView) {
        val statusColor = when (AutomationState.status) {
            "RUNNING" -> "● RUNNING"
            "PAUSED" -> "⏸ PAUSED"
            "ROOT UNAVAILABLE" -> "❌ ROOT UNAVAILABLE"
            "TARGET UI UNAVAILABLE" -> "❌ UI UNAVAILABLE"
            "TARGET BLOCKED AUTOMATION" -> "⛔ BLOCKED BY APP"
            else -> "■ STOPPED"
        }
        st.text = "$statusColor\nSolved: ${AutomationState.solved}  |  Failed: ${AutomationState.failed}\nAction: ${AutomationState.action}"
    }

    private fun hidePanel() {
        isPanelOpen = false
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        panelParams = null
        refreshOverlayBounds()
    }

    override fun onDestroy() {
        hidePanel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        active = false
        overlayBounds = emptyList()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, "pr_red_channel")
            .setContentTitle("P.R Automation")
            .setContentText("Red floating HUD & automation active")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .build()
    }
}
