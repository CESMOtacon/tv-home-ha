package com.cesmotacon.tvhome

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * All cameras in a grid. Snapshots refresh every couple of seconds; the selected camera plays live.
 * OK opens it full screen; left/right flips between cameras; Back returns. Only one live stream runs at a time.
 */
class CamerasActivity : Activity() {
    private val scope = MainScope()
    private lateinit var root: FrameLayout
    private lateinit var grid: GridLayout
    private lateinit var status: TextView
    private lateinit var full: FrameLayout
    private lateinit var fullTex: TextureView
    private lateinit var fullSnap: ImageView
    private lateinit var fullName: TextView
    private lateinit var fullClock: TextView

    private class Tile(val cam: Ha.Cam, val view: TileView, val snap: ImageView, val tex: TextureView, val live: View)

    private val tiles = ArrayList<Tile>()
    private var player: ExoPlayer? = null
    private var playingFor: TextureView? = null
    private var liveJob: Job? = null
    private val snapJobs = ArrayList<Job>()
    private var fullIndex = -1
    private var focusIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Ha.start(this)
        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B0E18.toInt(), 0xFF0E1222.toInt(), 0xFF05060B.toInt()))
        }
        root.addView(Ui.text(this, 52, Ui.INK, Ui.LIGHT).apply { text = "Cameras" }, Ui.frameLp(Ui.WRAP, Ui.WRAP, Ui.px(96), Ui.px(60)))
        status = Ui.text(this, 22, Ui.MUTED).apply { text = "Loading cameras…" }
        root.addView(status, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).apply { topMargin = Ui.px(84); rightMargin = Ui.px(96) })

        grid = GridLayout(this).apply { columnCount = 3; clipChildren = false; clipToPadding = false; setPadding(Ui.px(96), Ui.px(20), Ui.px(96), Ui.px(80)) }
        val scroll = ScrollView(this).apply { clipChildren = false; clipToPadding = false; isVerticalScrollBarEnabled = false }
        scroll.addView(grid)
        root.addView(scroll, Ui.frameLp(Ui.MATCH, Ui.MATCH, 0, Ui.px(150)))

        // full-screen view
        full = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        fullSnap = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        fullTex = TextureView(this).apply { alpha = 0f }
        full.addView(fullSnap, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        full.addView(fullTex, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xB3000000.toInt(), 0x00000000))
            setPadding(Ui.px(64), Ui.px(40), Ui.px(64), Ui.px(60))
        }
        bar.addView(liveBadge())
        fullName = Ui.text(this, 34, Color.WHITE, Ui.MED)
        bar.addView(fullName, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.px(18) })
        fullClock = Ui.text(this, 26, 0xFFD7DCE5.toInt(), Typeface.MONOSPACE)
        bar.addView(fullClock)
        full.addView(bar, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))
        full.addView(Ui.text(this, 20, 0xCCD7DCE5.toInt()).apply {
            text = "◀ ▶  switch camera   ·   Back  all cameras"
            background = Ui.round(0x99000000.toInt(), 999); setPadding(Ui.px(24), Ui.px(12), Ui.px(24), Ui.px(12))
        }, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.px(40) })
        root.addView(full, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        setContentView(root)
        scope.launch {
            var cams = emptyList<Ha.Cam>()
            repeat(10) { if (cams.isEmpty()) { if (Ha.connected) cams = Ha.cameras(); if (cams.isEmpty()) delay(500) } }
            if (cams.isEmpty()) { status.text = "Couldn't reach Home Assistant (${Ha.status.lowercase()})"; return@launch }
            build(cams)
        }
        scope.launch { while (isActive) { fullClock.text = LocalDateTime.now().format(DateTimeFormatter.ofPattern("h:mm:ss a")); delay(1000) } }
    }

    private fun liveBadge() = Ui.text(this, 15, Color.WHITE, Typeface.DEFAULT_BOLD).apply {
        text = "● LIVE"; letterSpacing = 0.12f; background = Ui.round(Ui.RED, 7)
        setPadding(Ui.px(11), Ui.px(5), Ui.px(11), Ui.px(5)); setShadowLayer(0f, 0f, 0f, 0)
    }

    private fun build(cams: List<Ha.Cam>) {
        status.text = "${cams.size} cameras · OK for full screen"
        val w = Ui.px(552); val h = Ui.px(310); val gap = Ui.px(28)
        cams.forEachIndexed { i, cam ->
            val t = TileView(this, w, h, Ui.pxf(20), 0xFF111318.toInt())
            val snap = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val tex = TextureView(this).apply { alpha = 0f }
            t.addView(snap, 0, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            t.addView(tex, 1, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            val label = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
                setPadding(Ui.px(20), Ui.px(40), Ui.px(20), Ui.px(16))
            }
            val live = liveBadge().apply { visibility = View.GONE }
            label.addView(live, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.px(12) })
            label.addView(Ui.text(this, 24, Color.WHITE, Ui.MED).apply { text = cam.name })
            t.addView(label, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
            val tile = Tile(cam, t, snap, tex, live)
            tiles.add(tile)
            t.onFocus = { focusIndex = i; playIn(tile.tex, cam, tile.live) }
            t.onClick = { openFull(i) }
            grid.addView(t, GridLayout.LayoutParams().apply { width = w; height = h; rightMargin = gap; bottomMargin = gap })
            t.alpha = 0f; t.translationY = Ui.pxf(30)
            t.animate().alpha(1f).translationY(0f).setStartDelay(i * 40L).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
        }
        startSnapshots()
        grid.post { tiles.getOrNull(focusIndex)?.view?.requestFocus() }
    }

    private fun startSnapshots() {
        snapJobs.forEach { it.cancel() }; snapJobs.clear()
        tiles.forEachIndexed { i, t ->
            snapJobs += scope.launch {
                delay(i * 250L)
                while (isActive) {
                    Ha.snapshot(t.cam.id, 640)?.let { t.snap.setImageBitmap(it) }
                    delay(2500)
                }
            }
        }
    }

    /** Move the single live stream to [tex]. Full screen uses the camera's full-resolution "Clear" stream when it's enabled. */
    private fun playIn(tex: TextureView, cam: Ha.Cam, badge: View?) {
        liveJob?.cancel()
        tiles.forEach { it.live.visibility = View.GONE; if (it.tex !== tex) it.tex.alpha = 0f }
        if (tex !== fullTex) fullTex.alpha = 0f
        // Stop the old stream now but release it only once the remote settles: release() waits for the video decoder
        // to shut down, which on the TV took long enough to make every move between cameras stutter. stop() doesn't wait.
        player?.stop()
        playingFor = tex
        val isFull = tex === fullTex
        liveJob = scope.launch {
            delay(350)   // don't start streams while scrolling past
            player?.release(); player = null   // already stopped, so this is quick now
            // try the Clear twin even if it was switched on after this screen opened
            val hi = if (isFull) cam.hiId ?: cam.id.takeIf { it.endsWith("_fluent") }?.replace("_fluent", "_clear") else null
            val hiUrl = hi?.let { Ha.cameraStreamUrl(it) }
            val url = hiUrl ?: Ha.cameraStreamUrl(cam.id) ?: return@launch
            if (isFull) fullName.text = cam.name + if (hiUrl != null) "  ·  HD" else ""
            if (playingFor !== tex) return@launch
            val p = ExoPlayer.Builder(this@CamerasActivity).build()
            player = p
            p.volume = 0f
            p.setVideoTextureView(tex)
            p.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { tex.animate().alpha(1f).setDuration(300).start(); badge?.visibility = View.VISIBLE }
                override fun onPlayerError(error: PlaybackException) { tex.alpha = 0f; badge?.visibility = View.GONE }
                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    tex.fitVideo((videoSize.width * videoSize.pixelWidthHeightRatio).toInt(), videoSize.height, crop = !isFull)
                    // show what's actually playing, so HD vs low-res is visible, not assumed
                    if (isFull && videoSize.width > 0) fullName.text = cam.name + (if (hiUrl != null) "  ·  HD" else "  ·  standard") +
                        "  ·  ${videoSize.width}×${videoSize.height}"
                    android.util.Log.i("TvHome.Cam", "${if (hiUrl != null) hi else cam.id}: ${videoSize.width}x${videoSize.height}")
                }
            })
            p.setMediaItem(MediaItem.Builder().setUri(url)
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(1500).build()).build())
            p.playWhenReady = true
            p.prepare()
        }
    }

    private fun openFull(i: Int) {
        if (tiles.isEmpty()) return
        fullIndex = (i + tiles.size) % tiles.size
        val t = tiles[fullIndex]
        fullName.text = t.cam.name
        fullSnap.setImageDrawable(t.snap.drawable)
        scope.launch { Ha.snapshot(t.cam.hiId ?: t.cam.id, 1920)?.let { if (fullIndex >= 0 && tiles[fullIndex] === t) fullSnap.setImageBitmap(it) } }
        if (full.visibility != View.VISIBLE) {
            full.visibility = View.VISIBLE; full.alpha = 0f
            full.animate().alpha(1f).setDuration(250).start()
        }
        playIn(fullTex, t.cam, null)
    }

    private fun closeFull() {
        val back = fullIndex
        fullIndex = -1
        full.animate().alpha(0f).setDuration(200).withEndAction { full.visibility = View.GONE }.start()
        tiles.getOrNull(back)?.let { it.view.requestFocus(); playIn(it.tex, it.cam, it.live) }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (fullIndex >= 0) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (e.action == KeyEvent.ACTION_DOWN) openFull(fullIndex + if (e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { if (e.action == KeyEvent.ACTION_UP) closeFull(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onResume() {
        super.onResume()
        if (tiles.isNotEmpty()) {
            startSnapshots()
            if (fullIndex >= 0) openFull(fullIndex) else tiles.getOrNull(focusIndex)?.let { playIn(it.tex, it.cam, it.live) }
        }
    }

    override fun onPause() {
        super.onPause()
        snapJobs.forEach { it.cancel() }; snapJobs.clear()
        liveJob?.cancel()
        player?.release(); player = null; playingFor = null
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
