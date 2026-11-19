package com.cesmotacon.tvhome

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Front-door camera card that floats over anything, drawn as an accessibility overlay (no extra permission). */
class DoorbellPopup(private val svc: AccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()

    private var card: FrameLayout? = null
    private var dim: View? = null
    private var player: ExoPlayer? = null
    private var snapJob: Job? = null
    private val buttons = ArrayList<TextView>()
    private val actions = ArrayList<() -> Unit>()
    private var focus = 0
    private var footer: TextView? = null
    private var osd: TextView? = null

    val isShowing get() = card != null
    /** "ring", "vehicle" or "view" */
    var kind = ""
        private set

    fun show(ringing: Boolean, vehicle: Boolean = false) {
        val timed = ringing || vehicle
        kind = if (ringing) "ring" else if (vehicle) "vehicle" else "view"
        Ui.init(svc)
        hideNow()
        buttons.clear(); actions.clear()

        val c = FrameLayout(svc).apply {
            background = Ui.round(0xF20E1016.toInt(), 26)
            clipToOutline = true
        }
        val col = LinearLayout(svc).apply { orientation = LinearLayout.VERTICAL }

        // header
        val hd = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.px(24), Ui.px(20), Ui.px(24), Ui.px(18)) }
        if (timed) {
            val bell = Ui.text(svc, 28).apply { text = if (ringing) "🔔" else "🚗"; setShadowLayer(0f, 0f, 0f, 0) }
            hd.addView(bell, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.px(12) })
            bell.post {
                bell.pivotX = bell.width / 2f; bell.pivotY = 0f
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1000; repeatCount = 2
                    addUpdateListener { bell.rotation = (Math.sin(it.animatedFraction * Math.PI * 6) * 16 * (1 - it.animatedFraction)).toFloat() }
                    start()
                }
            }
        }
        val live = Ui.text(svc, 15, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
            text = "● LIVE"; letterSpacing = 0.12f; background = Ui.round(Ui.RED, 7)
            setPadding(Ui.px(11), Ui.px(5), Ui.px(11), Ui.px(5)); setShadowLayer(0f, 0f, 0f, 0)
        }
        hd.addView(live)
        hd.addView(Ui.text(svc, 26, Ui.INK, Ui.MED).apply { text = if (ringing) "Someone's at the front door" else if (vehicle) "Vehicle in the driveway" else "Front door" },
            LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.px(14) })
        hd.addView(Ui.text(svc, 19, Ui.MUTED).apply { text = LocalDateTime.now().format(DateTimeFormatter.ofPattern("h:mm")) })
        col.addView(hd)

        // video
        val video = FrameLayout(svc).apply { setBackgroundColor(0xFF111111.toInt()) }
        val snap = ImageView(svc).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val tex = TextureView(svc).apply { alpha = 0f }
        video.addView(snap, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        video.addView(tex, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val o = Ui.text(svc, 17, Color.WHITE, Typeface.MONOSPACE).apply { text = "FRONT DOOR" }
        osd = o
        video.addView(o, Ui.frameLp(Ui.WRAP, Ui.WRAP, Ui.px(14), Ui.px(12)))
        col.addView(video, LinearLayout.LayoutParams(Ui.px(640), Ui.px(360)))

        // buttons
        val acts = LinearLayout(svc).apply { orientation = LinearLayout.HORIZONTAL; setPadding(Ui.px(24), Ui.px(18), Ui.px(24), Ui.px(12)) }
        fun button(label: String, action: () -> Unit) {
            val b = Ui.text(svc, 20).apply {
                text = label; gravity = Gravity.CENTER; background = Ui.round(0x1CFFFFFF, 14)
                setPadding(Ui.px(8), Ui.px(14), Ui.px(8), Ui.px(14)); setShadowLayer(0f, 0f, 0f, 0)
            }
            acts.addView(b, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { if (buttons.isNotEmpty()) leftMargin = Ui.px(12) })
            buttons.add(b); actions.add(action)
        }
        if (ringing) for ((label, said) in (HaConfig.door?.replies ?: emptyList())) button(label) {
            Ha.speakAtDoor(said)
            footer?.text = "Said at the door: “$said”"
            main.postDelayed({ hide() }, 1200)
        }
        button(if (timed) "Dismiss" else "Close") { hide() }
        col.addView(acts)
        val f = Ui.text(svc, 17, Ui.MUTED).apply {
            text = when {
                ringing -> "Replies are spoken through the doorbell · closes by itself in 30 s"
                vehicle -> "Doorbell camera · closes by itself in 30 s"
                else -> "Live view · press Back to close"
            }
            setShadowLayer(0f, 0f, 0f, 0)
        }
        footer = f
        col.addView(f, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.px(24); bottomMargin = Ui.px(16) })
        val bar = View(svc).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Ui.AMBER, 0xFFFF7A45.toInt()))
            pivotX = 0f
        }
        if (timed) col.addView(bar, LinearLayout.LayoutParams(Ui.MATCH, Ui.px(5)))
        c.addView(col)
        focus = 0
        paintFocus()

        // windows
        if (ringing) {
            val d = View(svc).apply {
                background = GradientDrawable().apply {
                    gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = Ui.pxf(1500); setGradientCenter(0.82f, 0.8f)
                    colors = intArrayOf(0x00000000, 0x99000000.toInt())
                }
                alpha = 0f
            }
            runCatching { wm.addView(d, overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, touchable = false)) }
                .onSuccess { dim = d; d.animate().alpha(1f).setDuration(500).start() }
        }
        val lp = overlayParams(Ui.px(640), WindowManager.LayoutParams.WRAP_CONTENT, touchable = true).apply {
            gravity = Gravity.BOTTOM or Gravity.END; x = Ui.px(64); y = Ui.px(64)
        }
        try { wm.addView(c, lp) } catch (e: Exception) { return }
        card = c

        c.alpha = 0f; c.translationY = Ui.pxf(60); c.scaleX = 0.94f; c.scaleY = 0.94f
        c.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(1.1f)).start()
        if (timed) {
            val ring = GradientDrawable().apply { cornerRadius = Ui.pxf(26); setColor(Color.TRANSPARENT) }
            c.foreground = ring
            val pulse = if (ringing) 0xFFFF5A46.toInt() else Ui.AMBER
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1300; repeatCount = if (ringing) 2 else 1
                addUpdateListener { va ->
                    val a = Math.sin(va.animatedFraction * Math.PI).toFloat()
                    ring.setStroke(Ui.px(3), Ui.withAlpha(pulse, (a * 210).toInt()))
                }
                start()
            }
            bar.post { bar.animate().scaleX(0f).setDuration(30_000).setInterpolator(LinearInterpolator()).start() }
            main.postDelayed(autoHide, 30_000)
        } else main.postDelayed(autoHide, 180_000)

        // picture: a snapshot straight away, then the live stream
        if (!Ha.connected) {
            f.text = "Home Assistant isn't connected yet (${Ha.status.lowercase()}), so there's no picture"
            return
        }
        scope.launch {
            (HaConfig.door?.snapshot?.let { Ha.snapshot(it, 1280) } ?: HaConfig.door?.camera?.let { Ha.snapshot(it) })
                ?.let { if (card === c && player?.isPlaying != true) snap.setImageBitmap(it) }
        }
        scope.launch {
            // the full-resolution "Clear" stream if it's enabled in Home Assistant, else the light "Fluent" one
            val url = HaConfig.door?.cameraHd?.let { Ha.cameraStreamUrl(it) } ?: HaConfig.door?.camera?.let { Ha.cameraStreamUrl(it) }
            if (card !== c) return@launch
            if (url == null) { snapshotLoop(snap, c); return@launch }
            val p = ExoPlayer.Builder(svc).build()
            player = p
            p.volume = 0f
            p.setVideoTextureView(tex)
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { tex.animate().alpha(1f).setDuration(400).start() }
                override fun onPlayerError(error: PlaybackException) { if (card === c) snapshotLoop(snap, c) }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    tex.fitVideo((videoSize.width * videoSize.pixelWidthHeightRatio).toInt(), videoSize.height, crop = true)
                }
            })
            p.setMediaItem(MediaItem.Builder().setUri(url)
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(1500).build()).build())
            p.playWhenReady = true
            p.prepare()
        }
        scope.launch {   // camera-style timestamp
            while (isActive && card === c) {
                o.text = "FRONT DOOR  " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                delay(1000)
            }
        }
    }

    private fun snapshotLoop(snap: ImageView, c: View) {
        snapJob?.cancel()
        snapJob = scope.launch {
            while (isActive && card === c) {
                HaConfig.door?.camera?.let { Ha.snapshot(it) }?.let { snap.setImageBitmap(it) }
                delay(1000)
            }
        }
    }

    private fun overlayParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT)

    private val autoHide = Runnable { hide() }

    private fun paintFocus() {
        buttons.forEachIndexed { i, b ->
            val f = i == focus
            (b.background as GradientDrawable).setColor(if (f) Color.WHITE else 0x1CFFFFFF)
            b.setTextColor(if (f) 0xFF0B0D12.toInt() else Ui.INK)
            b.typeface = if (f) Ui.MED else Ui.REG
            b.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(150).start()
        }
    }

    /** Remote keys while the pop-up is up. Returns true when the key was handled. */
    fun onKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (e.action == KeyEvent.ACTION_DOWN) {
                    val was = focus
                    focus = (focus + if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1).coerceIn(0, buttons.lastIndex)
                    if (focus != was) Sounds.move(svc)
                    paintFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (e.action == KeyEvent.ACTION_UP) actions.getOrNull(focus)?.let { Sounds.click(svc); it() }
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { if (e.action == KeyEvent.ACTION_UP) hide(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            KeyEvent.KEYCODE_HOME -> { hide(); return false }
        }
        return false
    }

    fun hide() {
        val c = card ?: return
        val d = dim
        card = null; dim = null
        main.removeCallbacks(autoHide)
        snapJob?.cancel(); snapJob = null
        player?.release(); player = null
        c.animate().alpha(0f).translationY(Ui.pxf(40)).setDuration(260).setInterpolator(DecelerateInterpolator()).withEndAction {
            runCatching { wm.removeViewImmediate(c) }
        }.start()
        d?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction { runCatching { wm.removeViewImmediate(d) } }?.start()
    }

    private fun hideNow() {
        val c = card ?: return
        card = null
        main.removeCallbacks(autoHide)
        snapJob?.cancel(); snapJob = null
        player?.release(); player = null
        runCatching { wm.removeViewImmediate(c) }
        dim?.let { runCatching { wm.removeViewImmediate(it) } }; dim = null
    }
}
