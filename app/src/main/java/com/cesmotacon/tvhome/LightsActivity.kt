package com.cesmotacon.tvhome

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The house's lights: a dashboard's lights and switches under its headings, or every light by floor and room (see
 * [HaLights]). OK switches a light on or off; in floor-and-room mode, each room with more than one light starts with
 * a "Whole room" tile. Holding OK opens brightness (◀ ▶) and colours. Tiles follow Home Assistant live, so a light
 * switched from anywhere else changes here too.
 */
class LightsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var root: FrameLayout
    private lateinit var scroll: NavScroll
    private lateinit var nav: GridNav
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private lateinit var toastTv: TextView

    /** A tile for one light, or a whole room's ([room] is true; [ids] has all its lights). */
    private class Tile(val view: TileView, val ids: List<String>, val title: String, val room: Boolean,
                       val dot: View, val sub: TextView, val bar: View)
    private class Row(val view: LinearLayout, val top: View)
    private class Chip(val name: String, val color: Int, val act: () -> Unit)
    private class RoomCount(val label: TextView, val ids: List<String>)

    private val tiles = ArrayList<Tile>()
    private val byId = HashMap<String, MutableList<Tile>>()
    private val gridRows = ArrayList<Row>()
    private val roomCounts = ArrayList<RoomCount>()
    private var allIds = emptyList<String>()
    private var resumed = false
    private var watching = false
    private var panel: Panel? = null

    private val listener: (String) -> Unit = { id ->
        byId[id]?.forEach { update(it) }
        if (id in byId) { refreshCounts(); panel?.refresh() }
        if (id == "_status" && !Ha.connected) status.text = "Home Assistant: ${Ha.status.lowercase()}"
        else if (id == "_status") refreshCounts()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Ha.start(this)
        root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B0E18.toInt(), 0xFF120F22.toInt(), 0xFF05060B.toInt()))
        }
        root.addView(Ui.text(this, 52, Ui.INK, Ui.LIGHT).apply { text = "Lights" }, Ui.frameLp(Ui.WRAP, Ui.WRAP, Ui.px(96), Ui.px(60)))
        status = Ui.text(this, 22, Ui.MUTED).apply { text = "Loading lights…" }
        root.addView(status, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).apply { topMargin = Ui.px(84); rightMargin = Ui.px(96) })

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(Ui.px(96), Ui.px(16), Ui.px(96), Ui.px(150))
        }
        nav = GridNav(this).apply { clipChildren = false; clipToPadding = false; rows = { gridRows.map { it.view } } }
        nav.addView(column, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        scroll = NavScroll(this).apply { clipChildren = false; clipToPadding = false; isVerticalScrollBarEnabled = false }
        scroll.addView(nav, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        root.addView(scroll, Ui.frameLp(Ui.MATCH, Ui.MATCH, 0, Ui.px(150)))

        root.addView(Ui.text(this, 20, 0xCCD7DCE5.toInt()).apply {
            text = "OK  on / off   ·   Hold OK  brightness & colour   ·   Back  home"
            background = Ui.round(0xB3000000.toInt(), 999); setPadding(Ui.px(24), Ui.px(12), Ui.px(24), Ui.px(12))
        }, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = Ui.px(36) })

        toastTv = Ui.text(this, 23).apply {
            background = Ui.round(0xE6141620.toInt(), 999); setPadding(Ui.px(28), Ui.px(16), Ui.px(28), Ui.px(16)); alpha = 0f; elevation = Ui.pxf(20)
        }
        root.addView(toastTv, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply { leftMargin = Ui.px(96); bottomMargin = Ui.px(100) })

        setContentView(root)
        Sounds.attach(this)
        scope.launch { load() }
    }

    private suspend fun load() {
        var layout: List<HaLights.Floor>? = null
        repeat(20) { if (layout == null) { if (Ha.connected) layout = HaLights.layout(); if (layout == null) delay(500) } }
        val floors = layout ?: run { status.text = "Couldn't reach Home Assistant (${Ha.status.lowercase()})"; return }
        allIds = floors.flatMap { f -> f.rooms.flatMap { it.lights } }.distinct()
        if (allIds.isEmpty()) { status.text = "No lights in Home Assistant"; return }
        startWatching()
        // subscribing sends every light's state at once; give it a moment to arrive
        repeat(30) { if (allIds.any { Ha.states[it] == null }) delay(100) }
        build(floors)
    }

    private fun startWatching() {
        if (watching || !resumed || allIds.isEmpty()) return
        watching = true
        Ha.addListener(listener)
        Ha.watch(WATCH, allIds)
    }

    private fun stopWatching() {
        if (!watching) return
        watching = false
        Ha.unwatch(WATCH)
        Ha.removeListener(listener)
    }

    // ---------------------------------------------------------------- layout

    private fun build(floors: List<HaLights.Floor>) {
        val w = Ui.px(258); val h = Ui.px(128); val gap = Ui.px(22); val perRow = 6
        var firstFloor = true
        var n = 0
        for (f in floors) {
            val rooms = f.rooms.map { r ->
                r to r.lights.filter { Ha.states[it] != null }.sortedBy { HaLights.sortKey(HaLights.name(it, r.name)) }
            }.filter { it.second.isNotEmpty() }
            if (rooms.isEmpty()) continue
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
            head.addView(Ui.text(this, 34, Ui.INK, Ui.MED).apply { text = f.name })
            val headCount = Ui.text(this, 21, 0xFF7F8796.toInt())
            head.addView(headCount, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.px(18); bottomMargin = Ui.px(3) })
            column.addView(head, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = if (firstFloor) 0 else Ui.px(30); bottomMargin = Ui.px(22) })
            firstFloor = false
            var top: View? = head
            for ((r, ids) in rooms) {
                if (r.name.isBlank()) {
                    // a dashboard section: no rooms, so the count goes next to the heading
                    roomCounts.add(RoomCount(headCount, ids))
                } else {
                    val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
                    labelRow.addView(Ui.text(this, 25, 0xFFAAB2BF.toInt(), Ui.MED).apply { text = r.name; letterSpacing = 0.02f })
                    val count = Ui.text(this, 20, 0xFF7F8796.toInt())
                    labelRow.addView(count, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.px(16) })
                    column.addView(labelRow, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { bottomMargin = Ui.px(16) })
                    roomCounts.add(RoomCount(count, ids))
                    if (top == null) top = labelRow
                }

                val items = ArrayList<Tile>()
                if (ids.size > 1 && r.name.isNotBlank()) items.add(tile(ids, r.name, "Whole room", true, w, h))
                ids.forEach { items.add(tile(listOf(it), r.name, HaLights.name(it, r.name), false, w, h)) }
                val chunks = items.chunked(perRow)
                chunks.forEachIndexed { ci, chunk ->
                    val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
                    chunk.forEachIndexed { i, t ->
                        row.addView(t.view, Ui.linLp(w, h, right = if (i < chunk.size - 1) gap else 0))
                        t.view.alpha = 0f; t.view.translationY = Ui.pxf(30)
                        t.view.animate().alpha(1f).translationY(0f).setStartDelay(minOf(n++, 18) * 30L).setDuration(380)
                            .setInterpolator(DecelerateInterpolator(2f)).withEndAction { update(t) }.start()
                    }
                    column.addView(row, LinearLayout.LayoutParams(Ui.WRAP, h).apply { bottomMargin = if (ci == chunks.size - 1) Ui.px(34) else gap })
                    gridRows.add(Row(row, top ?: row))
                    top = null
                }
            }
        }
        tiles.forEach { update(it) }
        refreshCounts()
        tiles.firstOrNull()?.view?.let { v -> v.post { v.requestFocus() } }
    }

    private fun tile(ids: List<String>, roomName: String, title: String, room: Boolean, w: Int, h: Int): Tile {
        val v = TileView(this, w, h, Ui.pxf(22), Ui.GLASS)
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x26FFFFFF) } }
        v.addView(dot, FrameLayout.LayoutParams(Ui.px(if (room) 26 else 20), Ui.px(if (room) 26 else 20), Gravity.TOP or Gravity.END)
            .apply { rightMargin = Ui.px(18); topMargin = Ui.px(18) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(Ui.text(this, 21, Color.WHITE, Ui.MED, lines = 2).apply { text = title }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val sub = Ui.text(this, 17, Ui.MUTED)
        col.addView(sub, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(5) })
        v.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM or Gravity.START)
            .apply { leftMargin = Ui.px(20); rightMargin = Ui.px(18); bottomMargin = Ui.px(17) })
        val bar = View(this).apply { pivotX = 0f; visibility = View.INVISIBLE }
        v.addView(bar, FrameLayout.LayoutParams(Ui.MATCH, Ui.px(5), Gravity.BOTTOM))
        val t = Tile(v, ids, if (room) roomName else title, room, dot, sub, bar)
        v.onClick = { toggle(t) }
        v.onLongClick = { openPanel(t) }
        v.onFocus = { reveal(t) }
        tiles.add(t)
        ids.forEach { byId.getOrPut(it) { ArrayList() }.add(t) }
        return t
    }

    private fun reveal(t: Tile) {
        val row = gridRows.firstOrNull { it.view === t.view.parent } ?: return
        scroll.reveal(row.top, row.view)
    }

    private fun update(t: Tile) {
        val avail = t.ids.filter { HaLights.available(it) }
        val on = avail.filter { HaLights.isOn(it) }
        val pct: Int? = if (t.room) on.mapNotNull { HaLights.percent(it) }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                        else HaLights.percent(t.ids[0])
        t.sub.text = when {
            avail.isEmpty() -> "Unavailable"
            t.room -> when { on.isEmpty() -> "All off"; on.size == avail.size -> "All on"; else -> "${on.size} of ${avail.size} on" }
            on.isEmpty() -> "Off"
            else -> pct?.let { "On · $it%" } ?: "On"
        }
        val c = on.firstOrNull()?.let { HaLights.color(it) }
        (t.view.background as GradientDrawable).setColor(if (c != null) tint(c, pct ?: 100) else Ui.GLASS)
        (t.dot.background as GradientDrawable).setColor(c ?: 0x26FFFFFF)
        t.sub.setTextColor(if (on.isNotEmpty()) Color.WHITE else Ui.MUTED)
        if (c != null && pct != null) {
            t.bar.setBackgroundColor(c); t.bar.visibility = View.VISIBLE
            t.bar.animate().scaleX(pct / 100f).setStartDelay(0).setDuration(250).start()
        } else t.bar.visibility = View.INVISIBLE
        if (t.view.animation == null && t.view.translationY == 0f) t.view.alpha = if (avail.isEmpty()) 0.45f else 1f
    }

    /** A lit tile takes on the light's colour, more strongly the brighter it is. */
    private fun tint(c: Int, pct: Int): Int {
        val a = 0.16f + 0.26f * pct / 100f
        val g = Ui.GLASS
        return Color.argb(165, (Color.red(c) * a + Color.red(g) * (1 - a)).toInt(),
            (Color.green(c) * a + Color.green(g) * (1 - a)).toInt(), (Color.blue(c) * a + Color.blue(g) * (1 - a)).toInt())
    }

    private fun refreshCounts() {
        val avail = allIds.filter { byId.containsKey(it) && HaLights.available(it) }
        val on = avail.count { HaLights.isOn(it) }
        if (Ha.connected && tiles.isNotEmpty()) status.text = "$on of ${avail.size} lights on"
        for (rc in roomCounts) {
            val a = rc.ids.filter { HaLights.available(it) }
            rc.label.text = if (a.isEmpty()) "unavailable" else "${a.count { HaLights.isOn(it) }} of ${a.size} on"
        }
    }

    // ---------------------------------------------------------------- actions

    private fun connectedOrSay(): Boolean {
        if (!Ha.connected) toast("Home Assistant isn't connected (${Ha.status.lowercase()})")
        return Ha.connected
    }

    private fun toggle(t: Tile) {
        if (!connectedOrSay()) return
        val avail = t.ids.filter { HaLights.available(it) }
        if (avail.isEmpty()) { toast("${t.title} is unavailable"); return }
        // homeassistant.* handles a mix of lights and switches; a single tile uses its own domain (light or switch)
        if (t.room) Ha.call("homeassistant", if (avail.any { HaLights.isOn(it) }) "turn_off" else "turn_on", avail)
        else avail[0].let { Ha.call(it.substringBefore('.'), "toggle", it) }
    }

    /** What a brightness or colour change applies to: the room's lights that are on, or all of them if none are. */
    private fun targets(t: Tile, can: (String) -> Boolean): List<String> {
        val avail = t.ids.filter { HaLights.available(it) && can(it) }
        return avail.filter { HaLights.isOn(it) }.ifEmpty { avail }
    }

    private fun openPanel(t: Tile) {
        if (!connectedOrSay()) return
        if (t.ids.none { HaLights.available(it) }) { toast("${t.title} is unavailable"); return }
        if (targets(t) { HaLights.dimmable(it) }.isEmpty()) { toast("${t.title} only switches on and off"); return }
        panel = Panel(t).also { it.show() }
    }

    private fun closePanel() {
        val p = panel ?: return
        panel = null
        p.hide()
        nav.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        p.tile.view.requestFocus()
    }

    private fun toast(msg: String) {
        toastTv.text = msg
        toastTv.animate().cancel()
        toastTv.animate().alpha(1f).setStartDelay(0).setDuration(180).withEndAction {
            toastTv.animate().alpha(0f).setStartDelay(2200).setDuration(400).start()
        }.start()
    }

    /** Brightness and colour for one light or a whole room, over the grid. Back closes it. */
    private inner class Panel(val tile: Tile) {
        private val view = FrameLayout(this@LightsActivity).apply { setBackgroundColor(0xB3000000.toInt()); isClickable = true; alpha = 0f }
        private val info = Ui.text(this@LightsActivity, 22, Ui.MUTED)
        private val level: LevelBar
        private var sendJob: Job? = null

        init {
            val ctx = this@LightsActivity
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
                background = Ui.round(0xF2161A26.toInt(), 28); elevation = Ui.pxf(30)
                setPadding(Ui.px(56), Ui.px(46), Ui.px(56), Ui.px(40))
            }
            card.addView(Ui.text(ctx, 40, Ui.INK, Ui.MED).apply { text = if (tile.room) "${tile.title} · whole room" else tile.title })
            card.addView(info, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(8) })

            card.addView(label("Brightness"), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(34); bottomMargin = Ui.px(14) })
            level = LevelBar(ctx, currentPercent(), currentColor(), onChange = { p -> Sounds.move(ctx); send(p) }, onToggle = { toggle(tile) })
            card.addView(level, LinearLayout.LayoutParams(Ui.MATCH, Ui.px(64)))

            if (targets(tile) { HaLights.whites(it) }.isNotEmpty()) {
                card.addView(label("Whites"), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(30); bottomMargin = Ui.px(14) })
                card.addView(chips(WHITES.map { (name, k, c) -> Chip(name, c) { setLook("color_temp_kelvin", k) { id -> HaLights.whites(id) } } }))
            }
            if (targets(tile) { HaLights.colours(it) }.isNotEmpty()) {
                card.addView(label("Colours"), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(30); bottomMargin = Ui.px(14) })
                card.addView(chips(COLOURS.map { (name, hue, sat) ->
                    Chip(name, Color.HSVToColor(floatArrayOf(hue.toFloat(), sat / 100f, 1f))) { setLook("hs_color", JSONArray().put(hue).put(sat)) { id -> HaLights.colours(id) } }
                }))
            }
            card.addView(Ui.text(ctx, 19, 0xFF7F8796.toInt()).apply { text = "◀ ▶  brightness   ·   OK on the bar  on / off   ·   Back  done" },
                LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(34) })
            view.addView(card, FrameLayout.LayoutParams(Ui.px(1180), Ui.WRAP, Gravity.CENTER))
            refresh()
        }

        private fun label(s: String) = Ui.text(this@LightsActivity, 22, 0xFFAAB2BF.toInt(), Ui.MED).apply { text = s }

        private fun chips(items: List<Chip>): View {
            val row = LinearLayout(this@LightsActivity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
            items.forEachIndexed { i, c -> row.addView(chip(c.name, c.color, c.act), LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { if (i > 0) leftMargin = Ui.px(14) }) }
            return row
        }

        private fun chip(name: String, color: Int, act: () -> Unit) = Ui.text(this@LightsActivity, 20, 0xFFD7DCE5.toInt()).apply {
            text = name; isFocusable = true; isClickable = true
            background = Ui.round(0x1FFFFFFF, 999); setPadding(Ui.px(18), Ui.px(11), Ui.px(22), Ui.px(11))
            val d = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setBounds(0, 0, Ui.px(18), Ui.px(18)) }
            setCompoundDrawables(d, null, null, null); compoundDrawablePadding = Ui.px(10)
            setOnClickListener { act() }
            setOnFocusChangeListener { v, f ->
                (background as GradientDrawable).setColor(if (f) Color.WHITE else 0x1FFFFFFF)
                setTextColor(if (f) 0xFF0B0D12.toInt() else 0xFFD7DCE5.toInt())
                setShadowLayer(if (f) 0f else Ui.pxf(12), 0f, if (f) 0f else Ui.pxf(2), 0x66000000)
                v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(160).start()
            }
        }

        private fun setLook(key: String, value: Any, can: (String) -> Boolean) {
            if (!connectedOrSay()) return
            val ids = targets(tile, can).ifEmpty { return }
            Ha.call("light", "turn_on", ids, JSONObject().put(key, value))
        }

        /** Send a brightness once the remote settles, so holding ◀ ▶ doesn't flood the bulbs. */
        private fun send(pct: Int) {
            sendJob?.cancel()
            sendJob = scope.launch {
                delay(220)
                if (!connectedOrSay()) return@launch
                targets(tile) { HaLights.dimmable(it) }.takeIf { it.isNotEmpty() }?.let { Ha.call("light", "turn_on", it, JSONObject().put("brightness_pct", pct)) }
            }
        }

        private fun currentPercent(): Int {
            val on = tile.ids.filter { HaLights.isOn(it) }
            return on.mapNotNull { HaLights.percent(it) }.takeIf { it.isNotEmpty() }?.average()?.roundToInt() ?: if (on.isEmpty()) 0 else 100
        }

        private fun currentColor(): Int = tile.ids.firstOrNull { HaLights.isOn(it) }?.let { HaLights.color(it) } ?: 0xFFFFC27A.toInt()

        fun refresh() {
            val avail = tile.ids.filter { HaLights.available(it) }
            val on = avail.filter { HaLights.isOn(it) }
            info.text = when {
                avail.isEmpty() -> "Unavailable"
                tile.room -> "${on.size} of ${avail.size} lights on"
                on.isEmpty() -> "Off"
                else -> listOfNotNull("On", HaLights.percent(avail[0])?.let { "$it%" }, HaLights.kelvin(avail[0])?.let { "$it K" }).joinToString("  ·  ")
            }
            level.color = currentColor()
            if (sendJob?.isActive != true) level.level = currentPercent()
            level.invalidate()
        }

        fun show() {
            nav.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            root.addView(view, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            view.animate().alpha(1f).setDuration(200).start()
            level.requestFocus()
        }

        fun hide() { view.animate().alpha(0f).setDuration(160).withEndAction { root.removeView(view) }.start() }
    }

    // ---------------------------------------------------------------- lifecycle and keys

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK && panel != null) {
            if (e.action == KeyEvent.ACTION_UP) closePanel()
            return true
        }
        Sounds.key(this, e)
        return super.dispatchKeyEvent(e)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        startWatching()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        stopWatching()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val WATCH = "all_lights"
        private val WHITES = listOf(Triple("Candle", 2200, 0xFFFFA65C.toInt()), Triple("Warm", 2700, 0xFFFFC28E.toInt()),
            Triple("Neutral", 4000, 0xFFFFE4CC.toInt()), Triple("Daylight", 6000, 0xFFF1F3FF.toInt()))
        /** Name, hue (degrees), saturation (%). */
        private val COLOURS = listOf(Triple("Red", 0, 100), Triple("Orange", 24, 100), Triple("Gold", 42, 95), Triple("Green", 120, 85),
            Triple("Teal", 172, 90), Triple("Blue", 222, 100), Triple("Purple", 268, 95), Triple("Pink", 320, 75))
    }
}

/** The brightness bar in the light panel: ◀ ▶ step it, OK switches the light. */
private class LevelBar(ctx: Context, var level: Int, var color: Int, val onChange: (Int) -> Unit, val onToggle: () -> Unit) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.pxf(4); color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.pxf(26); typeface = Ui.MED }
    private val rect = RectF()

    init {
        isFocusable = true; isClickable = true
        setOnClickListener { onToggle() }
    }

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat()); c.drawRoundRect(rect, r, r, track)
        if (level > 0) {
            fill.color = color
            rect.set(0f, 0f, maxOf(height.toFloat(), width * level / 100f), height.toFloat()); c.drawRoundRect(rect, r, r, fill)
        }
        if (isFocused) { val i = ring.strokeWidth / 2; rect.set(i, i, width - i, height - i); c.drawRoundRect(rect, r - i, r - i, ring) }
        val label = if (level > 0) "$level%" else "Off"
        val dark = level > 12 && Color.luminance(color) > 0.5f
        text.color = if (dark) 0xFF141620.toInt() else Color.WHITE
        c.drawText(label, Ui.pxf(28), height / 2f - (text.descent() + text.ascent()) / 2, text)
    }

    override fun onFocusChanged(gain: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gain, direction, previouslyFocusedRect); invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            val up = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            val next = if (up) STEPS.firstOrNull { it > level } ?: 100 else STEPS.lastOrNull { it < level } ?: 1
            if (next != level) { level = next; invalidate(); onChange(level) }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object { private val STEPS = listOf(1, 5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100) }
}

/**
 * The lights grid's remote navigation: Left and Right move along a row and stop at its ends; Up and Down always go to
 * the next row (even a short one), landing on the tile nearest the column you started from.
 */
private class GridNav(ctx: Context) : FrameLayout(ctx) {
    var rows: () -> List<View> = { emptyList() }
    private var anchorX = 0f
    private var anchorFor: View? = null

    private fun kids(row: View) = (row as ViewGroup).let { g -> (0 until g.childCount).map { g.getChildAt(it) }.filter { it.isShown && it.isFocusable } }
    private fun centerX(v: View) = Rect().also { v.getDrawingRect(it); offsetDescendantRectToMyCoords(v, it) }.exactCenterX()

    override fun focusSearch(focused: View?, direction: Int): View? {
        val horizontal = direction == FOCUS_LEFT || direction == FOCUS_RIGHT
        if (focused == null || !(horizontal || direction == FOCUS_UP || direction == FOCUS_DOWN)) return super.focusSearch(focused, direction)
        val rs = rows()
        val ri = rs.indexOfFirst { it === focused.parent }
        if (ri < 0) return super.focusSearch(focused, direction)
        val row = kids(rs[ri])
        if (horizontal) {
            anchorFor = null
            return row.getOrNull(row.indexOf(focused) + if (direction == FOCUS_RIGHT) 1 else -1) ?: focused
        }
        val x = if (anchorFor === focused) anchorX else centerX(focused)
        val next = rs.getOrNull(ri + if (direction == FOCUS_DOWN) 1 else -1) ?: return focused
        return kids(next).minByOrNull { abs(centerX(it) - x) }?.also { anchorX = x; anchorFor = it } ?: focused
    }
}

/** A ScrollView that leaves the arrow keys to focus search (so [GridNav] decides) and scrolls only when asked. */
private class NavScroll(ctx: Context) : ScrollView(ctx) {
    override fun executeKeyEvent(event: KeyEvent): Boolean = false
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect?): Int = 0

    /** Scroll so everything from [top] (a heading above the row) down to [bottom] (the row) is on screen. */
    fun reveal(top: View, bottom: View) {
        val a = Rect().also { top.getDrawingRect(it); offsetDescendantRectToMyCoords(top, it) }
        val b = Rect().also { bottom.getDrawingRect(it); offsetDescendantRectToMyCoords(bottom, it) }
        val want = when {
            a.top - Ui.px(20) < scrollY -> a.top - Ui.px(20)
            b.bottom + Ui.px(110) > scrollY + height -> b.bottom + Ui.px(110) - height
            else -> return
        }
        smoothScrollTo(0, want.coerceAtLeast(0))
    }
}
