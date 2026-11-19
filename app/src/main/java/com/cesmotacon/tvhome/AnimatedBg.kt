package com.cesmotacon.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Animated backgrounds drawn on the TV: deep space, aurora, a glow that matches the running light show, and the live sky. ~30 fps (see [Beat]). */
class AnimatedBg(ctx: Context) : View(ctx) {
    enum class Mode { SPACE, AURORA, LIGHTS, SKY }

    var mode = Mode.SPACE
        set(v) { if (field != v) { field = v; ready = false; invalidate() } }
    /** Colours (0xRRGGBB) for the Glow background to follow, e.g. a running light show; null drifts through the built-in palettes. */
    var lightsColors: IntArray? = null
    /** Live weather scene (Mode.SKY). */
    private val sky = LiveSky()

    private val rnd = Random(42)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private var running = false
    private var ready = false
    private var last = 0L
    private var t = 0f

    fun start() { running = true; last = 0L; invalidate() }
    fun stop() { running = false }

    /**
     * Drawn into its own GPU layer, which is only repainted on the [Beat]. Frames in between (a tile lifting as focus
     * moves) reuse it as one picture instead of redrawing every cloud, glow and hill, which is what kept the remote
     * waiting on the TV's GPU.
     */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        setLayerType(if (isVisible) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE, null)
    }

    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
    private val W get() = width.toFloat()
    private val H get() = height.toFloat()

    // ---------------- space
    private class Star(val x: Float, val y: Float, val r: Float, val a: Float, val tw: Float, val ph: Float, val c: Int)
    private var layers: List<Pair<Float, List<Star>>> = emptyList()
    private var nebula: Bitmap? = null
    private var planet: Bitmap? = null
    private var shootX = 0f; private var shootY = 0f; private var shootLife = -1f; private var nextShoot = 2.5f

    private fun setupSpace() {
        layers = listOf(Triple(260, 3f, 0.4f to 1.1f), Triple(120, 7f, 0.7f to 1.6f), Triple(40, 14f, 1.1f to 2.3f)).map { (n, sp, rr) ->
            Ui.pxf(sp) to List(n) {
                val c = when { rnd.nextFloat() < .15f -> 0xFFFFD9B0.toInt(); rnd.nextFloat() < .35f -> 0xFFB9D4FF.toInt(); else -> Color.WHITE }
                Star(r(0f, W), r(0f, H), Ui.pxf(r(rr.first, rr.second)) * 1.5f, r(.35f, 1f), r(.5f, 2.2f), r(0f, 6.28f), c)
            }
        }
        // nebula at half resolution (soft anyway), drawn 2x
        val nw = (W * 0.7f).toInt(); val nh = (H * 0.7f).toInt()
        nebula = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = add }
            val cols = listOf(0x7A3CFF to .22f, 0x2FA8FF to .15f, 0xFF3C9A to .13f, 0x4B2BD1 to .2f, 0x14D1C4 to .1f, 0xFF7A3C to .07f)
            repeat(16) { i ->
                val (rgb, a) = cols[i % cols.size]
                val x = r(0f, nw.toFloat()); val y = r(nh * .05f, nh * .75f); val rad = r(110f, 300f) * Ui.s
                p.shader = RadialGradient(x, y, rad, Ui.withAlpha(rgb, (a * 255).toInt()), Ui.withAlpha(rgb, 0), Shader.TileMode.CLAMP)
                c.drawCircle(x, y, rad, p)
            }
            p.shader = null; p.xfermode = null
            repeat(1400) { p.color = Color.argb(r(0f, 22f).toInt(), 255, 255, 255); c.drawRect(r(0f, nw.toFloat()), r(0f, nh.toFloat()), 0f, 0f, p) }
        }
        planet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { b ->
            val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val cx = W * .84f; val cy = H * 1.62f; val R = H * 1.05f
            p.shader = RadialGradient(cx, cy, R * 1.13f, intArrayOf(0x005AA0FF, 0x005AA0FF, 0x8C5AAAFF.toInt(), 0x005AAAFF),
                floatArrayOf(0f, .95f / 1.13f, (.95f + .18f * .28f) / 1.13f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, R * 1.13f, p)
            c.save()
            val clip = Path().apply { addCircle(cx, cy, R, Path.Direction.CW) }; c.clipPath(clip)
            p.shader = RadialGradient(cx - R * .35f, cy - R * .78f, R * 1.05f, intArrayOf(0xFF2F5A9A.toInt(), 0xFF14233F.toInt(), 0xFF04070D.toInt()), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, p)
            p.shader = null; p.style = Paint.Style.STROKE
            repeat(10) { i ->
                p.color = if (i % 2 == 0) Color.argb(r(10f, 26f).toInt(), 60, 90, 160) else Color.argb(r(10f, 26f).toInt(), 140, 190, 255)
                p.strokeWidth = r(8f, 30f) * Ui.s
                val dy = r(-60f, 60f) * Ui.s; val ry = R * r(.8f, .98f)
                c.drawOval(RectF(cx - R * 1.02f, cy + dy - ry, cx + R * 1.02f, cy + dy + ry), p)
            }
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(cx - R, 0f, cx + R * .45f, 0f, 0x00000000, 0xB8000000.toInt(), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, p)
            c.restore()
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = Ui.pxf(2.5f); p.color = 0x99AAD7FF.toInt()
            c.drawArc(RectF(cx - R, cy - R, cx + R, cy + R), 192f, 97f, false, p)
        }
    }

    private fun drawSpace(c: Canvas, dt: Float) {
        c.drawColor(0xFF03040A.toInt())
        nebula?.let { nb ->
            val ox = -W * .2f + sin(t * .03f) * Ui.pxf(50); val oy = -H * .2f + cos(t * .021f) * Ui.pxf(35)
            c.drawBitmap(nb, null, RectF(ox, oy, ox + nb.width * 2f, oy + nb.height * 2f), paint.apply { alpha = 255; shader = null })
        }
        for ((sp, stars) in layers) for (s in stars) {
            val x = ((s.x - t * sp) % W + W) % W
            paint.color = s.c; paint.alpha = (255 * s.a * (.6f + .4f * sin(t * s.tw + s.ph))).toInt().coerceIn(0, 255)
            c.drawCircle(x, s.y, s.r, paint)
        }
        nextShoot -= dt
        if (shootLife < 0 && nextShoot <= 0) { shootX = r(W * .4f, W * 1.05f); shootY = r(-20f, H * .3f); shootLife = 0f; nextShoot = r(5f, 11f) }
        if (shootLife >= 0) {
            shootLife += dt; val k = shootLife / 1.1f
            val x = shootX - k * Ui.pxf(1350); val y = shootY + k * Ui.pxf(495)
            paint.shader = LinearGradient(x, y, x + Ui.pxf(360), y - Ui.pxf(132), Color.argb((240 * (1 - k)).toInt().coerceIn(0, 255), 255, 255, 255), 0x00FFFFFF, Shader.TileMode.CLAMP)
            paint.strokeWidth = Ui.pxf(3); paint.alpha = 255
            c.drawLine(x, y, x + Ui.pxf(360), y - Ui.pxf(132), paint); paint.shader = null
            if (k >= 1) shootLife = -1f
        }
        planet?.let { paint.alpha = (230 + 25 * sin(t * .4f)).toInt(); c.drawBitmap(it, 0f, 0f, paint); paint.alpha = 255 }
    }

    // ---------------- aurora
    private var auroraStars: List<Star> = emptyList()
    private var curtains: List<Bitmap> = emptyList()
    private val trees = Path()
    private var skyShader: Shader? = null
    private val dst = RectF()

    private fun setupAurora() {
        auroraStars = List(200) { Star(r(0f, W), r(0f, H * .75f), Ui.pxf(r(.6f, 2f)), r(.2f, .85f), r(.5f, 2f), r(0f, 6.28f), Color.WHITE) }
        curtains = listOf(0x4DFFB0, 0x36D1FF, 0xBA6BFF).map { rgb ->
            Bitmap.createBitmap(4, 256, Bitmap.Config.ARGB_8888).also { b ->
                val p = Paint().apply {
                    shader = LinearGradient(0f, 0f, 0f, 256f,
                        intArrayOf(Ui.withAlpha(rgb, 0), Ui.withAlpha(rgb, 56), Ui.withAlpha(rgb, 230), 0xF2E1FFF0.toInt(), Ui.withAlpha(rgb, 0)),
                        floatArrayOf(0f, .55f, .88f, .94f, 1f), Shader.TileMode.CLAMP)
                }
                Canvas(b).drawRect(0f, 0f, 4f, 256f, p)
            }
        }
        val kk = W / 1280f   // the aurora was tuned on a 1280-wide canvas
        val ground = { x: Float -> H * .86f + (sin(x / kk * .004f) * 14 + sin(x / kk * .013f) * 6) * kk }
        trees.reset(); trees.moveTo(0f, H)
        var x = 0f
        while (x <= W) { trees.lineTo(x, ground(x)); x += 8 * kk }
        trees.lineTo(W, H); trees.close()
        x = -10f
        while (x < W + 10) {
            val b = ground(x) + 4 * kk; val h = r(26f, 80f) * kk * (.7f + .3f * sin(x / kk * .01f))
            trees.moveTo(x - h * .22f, b); trees.lineTo(x, b - h); trees.lineTo(x + h * .22f, b); trees.close()
            x += r(8f, 15f) * kk
        }
        skyShader = LinearGradient(0f, 0f, 0f, H, intArrayOf(0xFF01030A.toInt(), 0xFF041621.toInt(), 0xFF0B2D2D.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
    }

    private fun drawAurora(c: Canvas) {
        paint.shader = skyShader; paint.alpha = 255; c.drawRect(0f, 0f, W, H, paint); paint.shader = null
        paint.color = Color.WHITE
        for (s in auroraStars) { paint.alpha = (255 * s.a * (.55f + .45f * sin(t * s.tw + s.ph))).toInt().coerceIn(0, 255); c.drawCircle(s.x, s.y, s.r, paint) }
        paint.xfermode = add
        val ms = t * 1000f
        val kk = W / 1280f
        val step = 4 * kk
        val specs = arrayOf(floatArrayOf(.40f, 0f, .0003f, .55f), floatArrayOf(.47f, 1f, .00022f, .38f), floatArrayOf(.35f, 2f, .00036f, .34f))
        for ((k, sp) in specs.withIndex()) {
            val bmp = curtains[sp[1].toInt()]
            var x = -step
            while (x < W + step) {
                val xd = x / kk
                val y = H * sp[0] + (sin(xd * .0042f + ms * sp[2] * (1 + k * .4f)) * 44 + sin(xd * .011f - ms * .0004f) * 16) * kk
                val h = (110 + 175 * (.5f + .5f * sin(xd * .0055f + ms * .00035f + k * 2))) * kk
                paint.alpha = (255 * sp[3] * (.3f + .7f * (.5f + .5f * sin(xd * .019f + ms * .0012f * (k + 1))))).toInt().coerceIn(0, 255)
                dst.set(x, y - h, x + step * 1.15f, y)
                c.drawBitmap(bmp, null, dst, paint)
                x += step
            }
        }
        paint.xfermode = null; paint.alpha = 255
        paint.color = 0xFF02060A.toInt(); c.drawPath(trees, paint)
    }

    // ---------------- glow (matches a running light show in editions that know about them)
    private val AUTO_ORDER = arrayOf("ocean", "velvet", "tide", "cozy", "idle")
    private val palettes = mapOf(
        "idle" to intArrayOf(0x2A3A9A, 0x145E6E, 0x4A2A8A),
        "cozy" to intArrayOf(0xFF8A2A, 0xFFB04A, 0xC8501A, 0xFF6A1A),
        "velvet" to intArrayOf(0xFF0022, 0xFF0E78, 0xE000B0, 0xFF4A00, 0x8C00FF),
        "ocean" to intArrayOf(0x0050FF, 0x00B4D8, 0x0077B6, 0x48CAE4, 0x023E8A),
        "tide" to intArrayOf(0x1B2A8A, 0x3A0CA3, 0x4361EE, 0x4CC9F0),
    )
    private class Blob(val ax: Float, val ay: Float, val fx: Float, val fy: Float, val ph: Float, val rad: Float, var col: FloatArray)
    private var blobs: List<Blob> = emptyList()

    private fun setupLights() {
        blobs = List(7) { Blob(r(.2f, .45f), r(.15f, .35f), r(.03f, .07f), r(.025f, .06f), r(0f, 6.28f), Ui.pxf(r(390f, 700f)), floatArrayOf(50f, 50f, 110f)) }
    }

    private fun drawLights(c: Canvas, dt: Float) {
        c.drawColor(0xFF05060B.toInt())
        val custom = lightsColors?.takeIf { it.isNotEmpty() }
        val auto = custom == null
        val key = AUTO_ORDER[((t / 40f).toInt()) % AUTO_ORDER.size]
        val pal = custom ?: palettes[key] ?: palettes["idle"]!!
        val al = if (auto) (if (key == "idle") 87 else 110) else 133
        paint.xfermode = add
        blobs.forEachIndexed { i, b ->
            val tgt = pal[i % pal.size]
            val k = (dt * (if (auto) 0.3f else 1.1f)).coerceAtMost(1f)
            b.col[0] += (((tgt shr 16) and 255) - b.col[0]) * k; b.col[1] += (((tgt shr 8) and 255) - b.col[1]) * k; b.col[2] += ((tgt and 255) - b.col[2]) * k
            val x = W * (.5f + b.ax * sin(t * b.fx * 6.283f + b.ph)); val y = H * (.5f + b.ay * cos(t * b.fy * 6.283f + b.ph * 1.3f))
            val col = Color.rgb(b.col[0].toInt(), b.col[1].toInt(), b.col[2].toInt())
            paint.shader = RadialGradient(x, y, b.rad, Ui.withAlpha(col, al), Ui.withAlpha(col, 0), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, b.rad, paint)
        }
        paint.shader = null; paint.xfermode = null
    }

    // ---------------- loop
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { ready = false }

    @Volatile private var preparing = false
    private var gen = 0

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return
        if (!ready) {
            // build the heavy bitmaps off the UI thread so opening TV Home stays snappy
            canvas.drawColor(0xFF05060B.toInt())
            if (!preparing) {
                preparing = true
                val g = ++gen; val m = mode
                Thread {
                    runCatching { when (m) { Mode.SPACE -> setupSpace(); Mode.AURORA -> setupAurora(); Mode.LIGHTS -> setupLights(); Mode.SKY -> sky.setup(width, height) } }
                    post { if (g == gen && mode == m) ready = true; preparing = false; invalidate() }
                }.start()
            }
            return
        }
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.033f else ((now - last) / 1000f).coerceAtMost(0.1f)
        last = now
        if (running) t += dt
        when (mode) {
            Mode.SPACE -> drawSpace(canvas, dt); Mode.AURORA -> drawAurora(canvas); Mode.LIGHTS -> drawLights(canvas, dt)
            Mode.SKY -> { sky.update(if (running) dt else 0f, Weather.currentOrFair()); sky.draw(canvas) }
        }
        if (running) Beat.next(this)
    }

    @Suppress("unused") private val tmpRect = Rect()
}
