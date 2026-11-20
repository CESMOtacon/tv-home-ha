package com.cesmotacon.tvhome

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Home Assistant on the home screen: one row with the Lights and Cameras groups side by side (each with its own
 * label), and status pills next to Settings (connection problems, configured sensors, the doorbell while it rings).
 */
object HaHome {
    private class Ref(val cfg: HaConfig.Tile, val view: TileView, val sub: TextView)
    private val refs = ArrayList<Ref>()
    private var home: MainActivity? = null
    private var chipHa: Pair<View, TextView>? = null
    private var chipDoor: Pair<View, TextView>? = null
    private val chipPills = ArrayList<Pair<HaConfig.Pill, Pair<View, TextView>>>()

    private val listener: (String) -> Unit = { id -> if (id !in skyOnly()) update() }

    /** Weather-station readings change every few seconds and don't affect anything here. */
    private fun skyOnly(): Set<String> = HaConfig.station?.let { setOfNotNull(it.solarRadiation, it.solarLux, it.wind, it.gust, it.windDirection, it.humidity, it.dewPoint, it.rainRate) } ?: emptySet()

    fun build(h: MainActivity) {
        home = h
        refs.clear(); chipPills.clear()
        chipHa = h.chip().also { h.addChip(it) }
        for (p in HaConfig.pills) chipPills.add(p to h.chip().also { h.addChip(it) })
        chipDoor = h.chip().also { h.addChip(it) }

        val groups = listOf(HaConfig.lightsTitle to HaConfig.lights, HaConfig.camerasTitle to HaConfig.cameraTiles).filter { it.second.isNotEmpty() }
        if (groups.isEmpty()) return
        val total = groups.sumOf { it.second.size }
        val gapIn = 18; val gapGroup = 56
        val w = minOf(220, (1728 - gapIn * (total - groups.size) - gapGroup * (groups.size - 1)) / total)
        val hgt = 116
        val bar = h.row()
        groups.forEachIndexed { gi, (title, list) ->
            val col = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
            col.addView(h.rowLabel(title))
            val r = h.row()
            list.forEachIndexed { i, t ->
                val tv = tile(h, t, Ui.px(w), Ui.px(hgt))
                r.addView(tv, Ui.linLp(Ui.px(w), Ui.px(hgt), right = if (i < list.size - 1) Ui.px(gapIn) else 0))
            }
            col.addView(r, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.px(hgt)))
            bar.addView(col, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { if (gi > 0) leftMargin = Ui.px(gapGroup) })
        }
        h.rowsBox.addView(bar, LinearLayout.LayoutParams(Ui.MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun tile(h: MainActivity, t: HaConfig.Tile, w: Int, hgt: Int): TileView {
        val v = TileView(h, w, hgt, Ui.pxf(22), Ui.GLASS)
        v.palette = t.colors.map { it or 0xFF000000.toInt() }.toIntArray()
        v.addView(Ui.text(h, 30).apply { text = t.icon; setShadowLayer(0f, 0f, 0f, 0) },
            FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.px(18); topMargin = Ui.px(12) })
        val col = LinearLayout(h).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(h, 22, Color.WHITE, Ui.MED).apply { text = t.name }, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        val sub = Ui.text(h, 17, Ui.MUTED)
        col.addView(sub, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(4) })
        v.addView(col, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply { leftMargin = Ui.px(20); bottomMargin = Ui.px(14) })
        v.onClick = { run(h, t) }
        h.focusGlow(v, Ui.AMBER)
        refs.add(Ref(t, v, sub))
        return v
    }

    fun resume(h: MainActivity) {
        home = h
        Ha.start(h)
        Ha.addListener(listener)
        update()
    }

    fun pause(h: MainActivity) { Ha.removeListener(listener) }

    private fun isRunning(t: HaConfig.Tile) = t.action == "show" && Ha.isOn(t.running)

    /** Colours of the first running light show, for the Glow background. */
    fun glowColors(): IntArray? = HaConfig.lights.firstOrNull { isRunning(it) }?.colors

    private fun update() {
        val h = home ?: return
        val connected = Ha.connected
        chipHa?.let { h.setChip(it, if (connected) null else "Home Assistant: ${Ha.status.lowercase()}", dot = Ui.AMBER) }
        for ((p, c) in chipPills) {
            val s = Ha.state(p.entity)?.takeIf { connected && it.isNotBlank() && it != "unavailable" && it != "unknown" }
            val unit = Ha.states[p.entity]?.attrs?.optString("unit_of_measurement", "")?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
            h.setChip(c, s?.let { if (p.label.isBlank()) "$it$unit" else "${p.label} · $it$unit" })
        }
        // only while someone is actually at the door ("last rang" times are unreliable: integration reloads bump them)
        chipDoor?.let { h.setChip(it, if (connected && Ha.isOn(HaConfig.door?.ring)) "Doorbell · ringing" else null, dot = Ui.RED, alert = true) }

        for (r in refs) {
            val t = r.cfg
            val run = isRunning(t)
            if (r.view.running != run) {
                r.view.running = run
                (r.view.background as GradientDrawable).setColor(if (run) blend(t.colors[0] or 0xFF000000.toInt(), Ui.GLASS) else Ui.GLASS)
                if (r.view.isFocused) r.view.onFocus?.invoke(r.view)
            }
            r.sub.setTextColor(if (run) Color.WHITE else Ui.MUTED)
            r.sub.text = when (t.action) {
                "doorbell" -> "Live camera"
                "all_cameras" -> "All feeds"
                "all_lights" -> "Every room"
                "lights_back" -> "End shows"
                else -> if (!connected) "Offline" else if (run) "● Running" else if (t.stop != null) "Off" else "Light show"
            }
        }
        h.refreshGlow()
    }

    private fun blend(c: Int, glass: Int): Int {
        val a = 0.22f
        return Color.argb(150, (Color.red(c) * a + Color.red(glass) * (1 - a)).toInt(),
            (Color.green(c) * a + Color.green(glass) * (1 - a)).toInt(), (Color.blue(c) * a + Color.blue(glass) * (1 - a)).toInt())
    }

    /** Turns a script/scene/light/... on, presses a button, or triggers an automation. */
    private fun activate(entity: String) {
        when (val domain = entity.substringBefore('.')) {
            "automation" -> Ha.call("automation", "trigger", entity)
            "button", "input_button" -> Ha.call(domain, "press", entity)
            else -> Ha.call(domain, "turn_on", entity)
        }
    }

    private fun stop(t: HaConfig.Tile) {
        if (t.stop != null) activate(t.stop) else t.running?.let { Ha.call("homeassistant", "turn_off", it) }
    }

    private fun run(h: MainActivity, t: HaConfig.Tile) {
        when (t.action) {
            "all_cameras" -> if (Ha.connected) h.startActivity(Intent(h, CamerasActivity::class.java)) else h.toast("Home Assistant isn't connected (${Ha.status.lowercase()})")
            "all_lights" -> if (Ha.connected) h.startActivity(Intent(h, LightsActivity::class.java)) else h.toast("Home Assistant isn't connected (${Ha.status.lowercase()})")
            "doorbell" -> if (HomeKeyService.instance != null) HaHelper.showDoorbell(false)
                          else h.toast("The camera needs TV Home's background helper, which isn't switched on yet")
            "lights_back" -> {
                if (!Ha.connected) { h.toast("Home Assistant isn't connected (${Ha.status.lowercase()})"); return }
                val on = HaConfig.lights.filter { isRunning(it) }
                on.forEach { stop(it) }
                val extra = t.alsoStop.filter { Ha.isOn(it) }   // shows without a tile
                extra.forEach { Ha.call("homeassistant", "turn_off", it) }
                h.toast(if (on.isEmpty() && extra.isEmpty()) "Nothing to turn back" else "Putting the lights back")
            }
            else -> {
                if (!Ha.connected) { h.toast("Home Assistant isn't connected (${Ha.status.lowercase()})"); return }
                if (isRunning(t)) { stop(t); h.toast("Stopping ${t.name}") }
                else { t.start?.let { activate(it) }; h.toast("Starting ${t.name}") }
            }
        }
    }
}
