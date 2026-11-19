package com.cesmotacon.tvhome

import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.hypot

/** A focusable tile: lifts, rings, shines and reports focus (for the glow). Optional animated "running" border. */
class TileView(ctx: Context, private val w: Int, private val h: Int, radius: Float, bg: Int) : FrameLayout(ctx) {
    var onFocus: ((TileView) -> Unit)? = null
    var onClick: (() -> Unit)? = null
    /** Hold OK on the remote. */
    var onLongClick: (() -> Unit)? = null
        set(v) { field = v; isLongClickable = v != null }
    var accent: Int = Color.WHITE
    var palette: IntArray? = null
    var running = false
        set(v) { field = v; invalidate() }

    private val shine = View(ctx)
    private val ring = GradientDrawable().apply {
        cornerRadius = radius; setColor(Color.TRANSPARENT); setStroke(Ui.px(4), Color.WHITE)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.pxf(3.5f) }
    private val borderRect = RectF()
    private val m = Matrix()
    private val r = radius

    init {
        isFocusable = true
        background = GradientDrawable().apply { cornerRadius = radius; setColor(bg) }
        clipToOutline = true
        clipChildren = true
        setWillNotDraw(false)
        shine.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x18FFFFFF, 0x00FFFFFF))
        shine.rotation = 18f
        addView(shine, LayoutParams((w * 0.45f).toInt(), (h * 1.8f).toInt()).apply { topMargin = -(h * 0.4f).toInt() })
        shine.translationX = -w.toFloat()
        setOnClickListener { onClick?.invoke() }
        setOnLongClickListener { onLongClick?.let { it(); true } ?: false }
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gain, direction, previouslyFocusedRect)
        applyFocus(gain)
        if (gain) onFocus?.invoke(this)
    }

    fun applyFocus(gain: Boolean) {
        // setStartDelay(0) matters: a view's animator keeps the last delay it was given, and the home screen's
        // entrance staggers its tiles by up to a third of a second, which otherwise held back every later lift.
        animate().scaleX(if (gain) 1.09f else 1f).scaleY(if (gain) 1.09f else 1f)
            .translationY(if (gain) -Ui.pxf(4) else 0f).translationZ(if (gain) Ui.pxf(26) else 0f)
            .setStartDelay(0).setDuration(230).setInterpolator(DecelerateInterpolator(2f)).start()
        foreground = if (gain) ring else null
        if (gain) {
            shine.animate().cancel()
            shine.translationX = -w * 0.6f
            shine.animate().translationX(w * 1.1f).setStartDelay(80).setDuration(900)
                .setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val p = palette
        if (running && p != null && p.size >= 2) {
            val cx = width / 2f; val cy = height / 2f
            val sweep = SweepGradient(cx, cy, intArrayOf(*p, p[0]), null)
            m.setRotate((System.currentTimeMillis() % 3200L) / 3200f * 360f, cx, cy)
            sweep.setLocalMatrix(m)
            borderPaint.shader = sweep
            val inset = borderPaint.strokeWidth / 2
            borderRect.set(inset, inset, width - inset, height - inset)
            canvas.drawRoundRect(borderRect, r, r, borderPaint)
            Beat.next(this)
        }
    }
}

/**
 * One shared ~30 fps beat for everything that animates non-stop (the background, seasonal leaves and snow, a running
 * light show's border), so they repaint together. On a TV every repaint redraws the whole screen on its modest GPU:
 * layers ticking on their own schedules (the leaves were at 60 fps) asked it for more frames than it can draw, and
 * the backlog delayed every remote press by about a tenth of a second.
 */
object Beat : android.view.Choreographer.FrameCallback {
    private val waiting = LinkedHashSet<View>()
    private var posted = false
    private var lastNs = 0L

    /** Redraw [v] on the next beat. Main thread only. */
    fun next(v: View) {
        waiting.add(v)
        if (!posted) { posted = true; android.view.Choreographer.getInstance().postFrameCallback(this) }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (frameTimeNanos - lastNs < 30_000_000L) { android.view.Choreographer.getInstance().postFrameCallback(this); return }  // every other 60 Hz frame
        lastNs = frameTimeNanos; posted = false
        val views = waiting.toTypedArray(); waiting.clear()
        for (v in views) v.invalidate()
    }
}

/**
 * Remote navigation on the home screen, row by row. Left and Right move along a row and stop at its ends. Up and Down
 * go to the nearest row above or below with a tile under the same spot (a shorter row in between is skipped; if no
 * row has one, the next row), landing on the tile nearest the column you started from, so Down then Up comes back to
 * the same tile. Android's own search goes to whatever is geometrically closest, which at the end of a row jumps
 * diagonally into another one: Right from "All apps" landed on a camera tile, Right from the last camera on Settings.
 */
class RowNav(ctx: Context) : FrameLayout(ctx) {
    /** The rows, top to bottom. The focusable views inside each one make up that row. */
    var rows: () -> List<View> = { emptyList() }
    private var anchorX = 0f
    private var anchorFor: View? = null

    private class Spot(val v: View, val r: Rect)

    private fun spot(v: View) = Spot(v, Rect().also { v.getDrawingRect(it); offsetDescendantRectToMyCoords(v, it) })

    override fun focusSearch(focused: View?, direction: Int): View? {
        val horizontal = direction == FOCUS_LEFT || direction == FOCUS_RIGHT
        if (focused == null || !(horizontal || direction == FOCUS_UP || direction == FOCUS_DOWN)) return super.focusSearch(focused, direction)
        val rs = rows().filter { it.isShown }.mapNotNull { g ->
            val found = ArrayList<View>()
            g.addFocusables(found, direction)
            found.filter { it.isShown }.map { spot(it) }.sortedBy { it.r.left }.takeIf { it.isNotEmpty() }
        }
        val ri = rs.indexOfFirst { row -> row.any { it.v === focused } }
        if (ri < 0) return super.focusSearch(focused, direction)
        val row = rs[ri]
        val i = row.indexOfFirst { it.v === focused }
        if (horizontal) {
            anchorFor = null
            return row.getOrNull(if (direction == FOCUS_RIGHT) i + 1 else i - 1)?.v ?: focused
        }
        val x = if (anchorFor === focused) anchorX else row[i].r.exactCenterX()
        val step = if (direction == FOCUS_DOWN) 1 else -1
        val slop = Ui.pxf(40)
        var j = ri + step
        var target: List<Spot>? = null
        while (j in rs.indices) {
            val r = rs[j]
            if (x >= r.first().r.left - slop && x <= r.last().r.right + slop) { target = r; break }
            j += step
        }
        val to = target ?: rs.getOrNull(ri + step) ?: return focused
        return to.minBy { abs(it.r.exactCenterX() - x) }.v.also { anchorX = x; anchorFor = it }
    }
}

/** Big soft colour glow that follows the focused tile. */
class GlowView(ctx: Context) : View(ctx) {
    private var cx = -9999f; private var cy = -9999f; private var color = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim: ValueAnimator? = null
    private val argb = ArgbEvaluator()

    fun moveTo(x: Float, y: Float, c: Int) {
        if (cx < -1000) { cx = x; cy = y; color = Ui.withAlpha(c, 0) }
        val sx = cx; val sy = cy; val sc = color
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { va ->
                val f = va.animatedValue as Float
                cx = sx + (x - sx) * f; cy = sy + (y - sy) * f
                color = argb.evaluate(f, sc, c) as Int
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (cx < -1000) return
        val rad = Ui.pxf(560)
        paint.shader = RadialGradient(cx, cy, rad,
            intArrayOf(Ui.withAlpha(color, 95), Ui.withAlpha(color, 38), Ui.withAlpha(color, 0)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rad, paint)
    }
}

/** Two stacked images that crossfade, with a slow Ken Burns drift. Album art is blurred and darkened. */
class BackgroundView(ctx: Context) : FrameLayout(ctx) {
    private val a = ImageView(ctx); private val b = ImageView(ctx)
    private var front: ImageView? = null
    private var kb: ObjectAnimator? = null
    var currentTag: String? = null
        private set

    init {
        for (v in listOf(a, b)) {
            v.scaleType = ImageView.ScaleType.CENTER_CROP
            v.alpha = 0f
            addView(v, LayoutParams(Ui.MATCH, Ui.MATCH))
        }
    }

    fun show(bmp: Bitmap, tag: String, blur: Boolean) {
        if (tag == currentTag) return
        currentTag = tag
        val back = if (front === a) b else a
        val old = front
        back.animate().cancel()
        back.setImageBitmap(bmp)
        back.setRenderEffect(if (blur) RenderEffect.createBlurEffect(Ui.pxf(70), Ui.pxf(70), Shader.TileMode.MIRROR) else null)
        if (blur) back.setColorFilter(Color.argb(120, 0, 0, 0), PorterDuff.Mode.SRC_ATOP) else back.clearColorFilter()
        back.bringToFront()
        back.alpha = 0f
        back.animate().alpha(1f).setDuration(1600).withEndAction { old?.alpha = 0f }.start()
        front = back
        kenBurns(back)
    }

    private fun kenBurns(v: View) {
        kb?.cancel()
        v.scaleX = 1.04f; v.scaleY = 1.04f; v.translationX = -Ui.pxf(30); v.translationY = Ui.pxf(12)
        kb = ObjectAnimator.ofPropertyValuesHolder(v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.04f, 1.17f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.04f, 1.17f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -Ui.pxf(30), Ui.pxf(30)),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, Ui.pxf(12), -Ui.pxf(12))).apply {
            duration = 55_000; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** Fade the picture out (an animated or plain background shows through). */
    fun clear() {
        if (currentTag == null) return
        currentTag = null
        front?.animate()?.alpha(0f)?.setDuration(1200)?.start()
    }

    fun pause() { kb?.pause() }
    fun resume() { kb?.resume() }
}

/**
 * Keep a video's real shape in a TextureView (which otherwise stretches it).
 * crop = fill the view and trim the edges; otherwise fit the whole picture with bars.
 */
fun android.view.TextureView.fitVideo(vw: Int, vh: Int, crop: Boolean) {
    if (vw <= 0 || vh <= 0) return
    post {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return@post
        val s = if (crop) maxOf(w / vw, h / vh) else minOf(w / vw, h / vh)
        setTransform(Matrix().apply { setScale(vw * s / w, vh * s / h, w / 2, h / 2) })
    }
}

/** Screen-space helpers. */
fun View.centerIn(root: View): Pair<Float, Float> {
    val a = IntArray(2); val b = IntArray(2)
    getLocationInWindow(a); root.getLocationInWindow(b)
    return (a[0] - b[0] + width / 2f) to (a[1] - b[1] + height / 2f)
}

@Suppress("unused") private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) = hypot(x1 - x2, y1 - y2)
