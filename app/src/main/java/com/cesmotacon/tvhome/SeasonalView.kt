package com.cesmotacon.tvhome

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import java.time.LocalDateTime
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Seasonal particles drawn between the background and the tiles. */
class SeasonalView(ctx: Context) : View(ctx) {
    enum class Theme { NONE, LEAVES, SNOW, FIREWORKS, HEARTS }

    companion object {
        fun pick(now: LocalDateTime = LocalDateTime.now()): Theme {
            val m = now.monthValue; val d = now.dayOfMonth; val h = now.hour
            return when {
                (m == 12 && d == 31 && h >= 18) || (m == 1 && d == 1 && h < 3) || (m == 7 && d == 4 && h >= 20) -> Theme.FIREWORKS
                m == 2 && d == 14 -> Theme.HEARTS
                m == 12 || m == 1 || m == 2 -> Theme.SNOW
                m == 10 || m == 11 -> Theme.LEAVES
                else -> Theme.NONE
            }
        }
    }

    private class P(
        var x: Float, var y: Float, var vx: Float, var vy: Float, var size: Float,
        var rot: Float, var vr: Float, var ph: Float, var color: Int, var life: Float = 0f, var maxLife: Float = 0f,
    )

    var override: Theme? = null
    /** Live weather draws real snow, so the pretend seasonal snow is skipped there. */
    var noSnow = false
    fun wanted(now: LocalDateTime = LocalDateTime.now()) = (override ?: pick(now)).let { if (noSnow && it == Theme.SNOW) Theme.NONE else it }
    var theme = Theme.NONE
        private set
    private val ps = ArrayList<P>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = Random(System.nanoTime())
    private var running = false
    private var last = 0L
    private var t = 0f
    private var nextBurst = 1f

    private val leaf = Path().apply {
        moveTo(0f, -1f); quadTo(0.85f, -0.45f, 0f, 1f); quadTo(-0.85f, -0.45f, 0f, -1f); close()
    }
    private val heart = Path().apply {
        moveTo(0f, 0.9f)
        cubicTo(-1.2f, 0.1f, -0.9f, -1f, 0f, -0.35f)
        cubicTo(0.9f, -1f, 1.2f, 0.1f, 0f, 0.9f); close()
    }
    private val leafColors = intArrayOf(0xFFE8742A.toInt(), 0xFFC8401E.toInt(), 0xFFF2B33D.toInt(), 0xFF9C5A2B.toInt(), 0xFFD9622B.toInt())
    private val fwColors = intArrayOf(0xFFFFD27A.toInt(), 0xFFFF5A7A.toInt(), 0xFF6AD7FF.toInt(), 0xFFB18CFF.toInt(), 0xFF7CFFB2.toInt(), 0xFFFFFFFF.toInt())

    fun start() {
        theme = wanted()
        ps.clear(); t = 0f; nextBurst = 0.6f
        if (width > 0) seed()
        running = true; last = 0L
        Beat.next(this)
    }

    fun stop() { running = false }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { if (running) { ps.clear(); seed() } }

    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
    private val W get() = width.toFloat()
    private val H get() = height.toFloat()

    private fun seed() {
        when (theme) {
            Theme.LEAVES -> repeat(24) { ps.add(newLeaf(r(0f, H))) }
            Theme.SNOW -> repeat(110) { ps.add(newFlake(r(0f, H))) }
            Theme.HEARTS -> repeat(18) { ps.add(newHeart(r(0f, H))) }
            else -> {}
        }
    }

    private fun newLeaf(y: Float) = P(r(0f, W), y, 0f, Ui.pxf(r(45f, 85f)), Ui.pxf(r(14f, 26f)), r(0f, 360f), r(-90f, 90f), r(0f, 6.28f), leafColors[rnd.nextInt(leafColors.size)])
    private fun newFlake(y: Float) = P(r(0f, W), y, 0f, Ui.pxf(r(25f, 75f)), Ui.pxf(r(1.6f, 4.6f)), 0f, 0f, r(0f, 6.28f), Color.argb((r(0.35f, 0.9f) * 255).toInt(), 255, 255, 255))
    private fun newHeart(y: Float) = P(r(0f, W), y, 0f, -Ui.pxf(r(30f, 65f)), Ui.pxf(r(10f, 22f)), r(-20f, 20f), 0f, r(0f, 6.28f), if (rnd.nextBoolean()) 0xCCFF3B6B.toInt() else 0xCCFF8FB1.toInt())

    private fun burst() {
        val cx = r(W * 0.15f, W * 0.85f); val cy = r(H * 0.12f, H * 0.45f)
        val c1 = fwColors[rnd.nextInt(fwColors.size)]; val c2 = fwColors[rnd.nextInt(fwColors.size)]
        repeat(70) {
            val a = r(0f, 6.283f); val sp = Ui.pxf(r(110f, 330f))
            ps.add(P(cx, cy, cos(a) * sp, sin(a) * sp, Ui.pxf(r(2f, 3.4f)), 0f, 0f, 0f, if (rnd.nextBoolean()) c1 else c2, 0f, r(1.3f, 2.1f)))
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!running || theme == Theme.NONE || width == 0) return
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceAtMost(0.05f)
        last = now; t += dt
        if (ps.isEmpty() && theme != Theme.FIREWORKS) seed()
        val wt = WindFx.now(); WindFx.tick(wt)
        when (theme) {
            Theme.LEAVES -> for (p in ps) {
                // the real wind carries them; a gust sweeps through, lifting and spinning them
                val g = WindFx.pulse(wt, p.x / W)
                p.y += p.vy * (1f - .45f * g.coerceAtMost(1f)) * dt
                p.x += (sin(t * 0.9f + p.ph) * Ui.pxf(40) + WindFx.dirX * Ui.pxf(6f + WindFx.mph * 7f) + WindFx.pushDir * g * Ui.pxf(300)) * dt
                p.rot += p.vr * (1f + 3f * g) * dt
                if (p.y > H + 40) { val n = newLeaf(-Ui.pxf(40)); p.x = n.x; p.y = n.y; p.color = n.color }
                if (p.x > W + 60) p.x -= W + 120 else if (p.x < -60) p.x += W + 120
                paint.color = p.color; paint.alpha = 215
                canvas.save(); canvas.translate(p.x, p.y); canvas.rotate(p.rot)
                canvas.scale(p.size * (0.35f + 0.65f * kotlin.math.abs(cos(t * 1.3f + p.ph))), p.size)
                canvas.drawPath(leaf, paint); canvas.restore()
            }
            Theme.SNOW -> for (p in ps) {
                p.y += p.vy * dt
                p.x += (sin(t * 0.8f + p.ph) * Ui.pxf(16) + WindFx.dirX * Ui.pxf(WindFx.mph * 4f) + WindFx.pushDir * WindFx.pulse(wt, p.x / W) * Ui.pxf(200)) * dt
                if (p.y > H + 10) { p.y = -10f; p.x = r(0f, W) }
                if (p.x > W + 10) p.x -= W + 20 else if (p.x < -10) p.x += W + 20
                paint.color = p.color
                canvas.drawCircle(p.x, p.y, p.size, paint)
            }
            Theme.HEARTS -> for (p in ps) {
                p.y += p.vy * dt; p.x += sin(t * 0.7f + p.ph) * Ui.pxf(22) * dt
                if (p.y < -40) { val n = newHeart(H + Ui.pxf(30)); p.x = n.x; p.y = n.y }
                paint.color = p.color
                canvas.save(); canvas.translate(p.x, p.y); canvas.rotate(p.rot + sin(t + p.ph) * 12f); canvas.scale(p.size, p.size)
                canvas.drawPath(heart, paint); canvas.restore()
            }
            Theme.FIREWORKS -> {
                nextBurst -= dt
                if (nextBurst <= 0 && ps.size < 420) { burst(); nextBurst = r(0.8f, 1.8f) }
                val g = Ui.pxf(95f)
                val it = ps.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    p.life += dt
                    if (p.life >= p.maxLife) { it.remove(); continue }
                    p.vx *= 0.985f; p.vy = p.vy * 0.985f + g * dt
                    p.x += p.vx * dt; p.y += p.vy * dt
                    val a = 1f - p.life / p.maxLife
                    paint.color = p.color; paint.alpha = (a * 255).toInt()
                    paint.strokeWidth = p.size * 0.8f
                    canvas.drawLine(p.x, p.y, p.x - p.vx * 0.05f, p.y - p.vy * 0.05f, paint)
                    canvas.drawCircle(p.x, p.y, p.size * (0.6f + 0.4f * a), paint)
                }
            }
            Theme.NONE -> {}
        }
        Beat.next(this)   // in step with the background: one repaint for both
    }
}
