package com.cesmotacon.tvhome

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.view.KeyEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Remote sounds: a soft pop as the selection moves and a slightly deeper one on OK. They're made here in code (a pure
 * tone that drops in pitch and fades within a few hundredths of a second), so there are no audio files and no
 * metallic ring: some TVs' own navigation sound (TCL's, for one) is a bright, ringing tick. They play at the TV's
 * volume, quieter than its own UI sounds. Settings → Sounds turns them off.
 */
object Sounds {
    private var navAt = 0L
    private var okHeld = false

    private var pool: SoundPool? = null
    private var popId = 0
    private var clickId = 0
    private val loaded = HashSet<Int>()
    private const val LEVEL = 0.55f   // on top of the sound's own quiet peak (about -14 dBFS)

    private fun ensure(ctx: Context): SoundPool? {
        pool?.let { return it }
        return runCatching {
            val p = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0) loaded.add(id) }
            val dir = ctx.applicationContext.cacheDir
            popId = p.load(write(File(dir, "pop.wav"), 820.0, 360.0), 1)
            clickId = p.load(write(File(dir, "click.wav"), 600.0, 260.0), 1)
            pool = p
            p
        }.getOrNull()
    }

    /** Get the sounds ready ahead of the first press (loading takes a moment). */
    fun prepare(ctx: Context) { if (Prefs.sounds(ctx)) ensure(ctx) }

    private fun play(ctx: Context, id: () -> Int) {
        if (!Prefs.sounds(ctx)) return
        val p = ensure(ctx) ?: return
        val s = id()
        if (s in loaded) p.play(s, LEVEL, LEVEL, 1, 0, 1f)
    }

    fun move(ctx: Context) = play(ctx) { popId }
    fun click(ctx: Context) = play(ctx) { clickId }

    /**
     * A pop: a sine that glides from [f0] down to [f1] Hz in a few milliseconds, with a 1 ms fade-in and a fast
     * exponential fade-out (-20 dB by ~30 ms), written as a 16-bit mono WAV.
     */
    private fun write(f: File, f0: Double, f1: Double): String {
        val sr = 48000; val n = sr * 90 / 1000
        val pcm = ShortArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            ph += 2 * PI * (f1 + (f0 - f1) * exp(-t / 0.006)) / sr
            val env = (1 - exp(-t / 0.0008)) * exp(-t / 0.013) * (if (i > n - 240) (n - i) / 240.0 else 1.0)
            pcm[i] = (sin(ph) * env * 0.26 * 32767).roundToInt().toShort()
        }
        val b = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(n * 2)
        for (s in pcm) b.putShort(s)
        f.writeBytes(b.array())
        return f.absolutePath
    }

    /** Pop whenever an arrow key moves the selection in [act]. Its dispatchKeyEvent must pass keys to [key]. */
    fun attach(act: Activity) {
        prepare(act)
        act.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (old != null && new != null && SystemClock.uptimeMillis() - navAt < 300) { navAt = 0L; move(act) }
        }
    }

    /** Every key the activity sees, before it handles it. OK clicks only on a short press: holding OK opens menus. */
    fun key(act: Activity, e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (e.action == KeyEvent.ACTION_DOWN) navAt = SystemClock.uptimeMillis()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when (e.action) {
                KeyEvent.ACTION_DOWN -> okHeld = e.repeatCount > 0
                KeyEvent.ACTION_UP -> if (!okHeld && !e.isCanceled && act.currentFocus?.isClickable == true) click(act)
            }
        }
    }
}
