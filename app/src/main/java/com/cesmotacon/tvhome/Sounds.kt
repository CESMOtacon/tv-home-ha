package com.cesmotacon.tvhome

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent

/**
 * Remote sounds: a soft tick as the selection moves and a click on OK. They're the TV's own built-in navigation sounds,
 * the ones Google TV's home screen plays, so TV Home sounds like the rest of the TV and ships no audio of its own.
 * Android normally plays these only while the TV's "system sounds" setting is on (often off on TVs, even though the
 * home screen still ticks). Apps with their own switch may play them anyway: that's Settings → Sounds here.
 */
object Sounds {
    private var navAt = 0L
    private var navFx = AudioManager.FX_FOCUS_NAVIGATION_DOWN
    private var okHeld = false

    private fun play(ctx: Context, fx: Int) {
        if (!Prefs.sounds(ctx)) return
        runCatching { ctx.getSystemService(AudioManager::class.java)?.playSoundEffect(fx, -1f) }   // -1: the TV's usual level
    }

    fun move(ctx: Context, fx: Int = AudioManager.FX_FOCUS_NAVIGATION_DOWN) = play(ctx, fx)
    fun click(ctx: Context) = play(ctx, AudioManager.FX_KEY_CLICK)

    /** Tick whenever an arrow key moves the selection in [act]. Its dispatchKeyEvent must pass keys to [key]. */
    fun attach(act: Activity) {
        act.window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            if (old != null && new != null && SystemClock.uptimeMillis() - navAt < 300) { navAt = 0L; move(act, navFx) }
        }
    }

    /** Every key the activity sees, before it handles it. OK clicks only on a short press: holding OK opens menus. */
    fun key(act: Activity, e: KeyEvent) {
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (e.action == KeyEvent.ACTION_DOWN) {
                    navAt = SystemClock.uptimeMillis()
                    navFx = when (e.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> AudioManager.FX_FOCUS_NAVIGATION_UP
                        KeyEvent.KEYCODE_DPAD_LEFT -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
                        KeyEvent.KEYCODE_DPAD_RIGHT -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
                        else -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
                    }
                }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when (e.action) {
                KeyEvent.ACTION_DOWN -> okHeld = e.repeatCount > 0
                KeyEvent.ACTION_UP -> if (!okHeld && !e.isCanceled && act.currentFocus?.isClickable == true) click(act)
            }
        }
    }
}
