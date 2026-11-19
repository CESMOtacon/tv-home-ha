package com.cesmotacon.tvhome

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object Prefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun background(ctx: Context) = p(ctx).getString("bg", "apod")!!.let { if (it == "lights") "glow" else it }
    fun setBackground(ctx: Context, v: String) = p(ctx).edit().putString("bg", v).apply()
    fun albumArt(ctx: Context) = p(ctx).getBoolean("albumArt", true)
    fun setAlbumArt(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("albumArt", v).apply()
    fun seasonal(ctx: Context) = p(ctx).getBoolean("seasonal", true)
    fun setSeasonal(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("seasonal", v).apply()
    fun allInputs(ctx: Context) = p(ctx).getBoolean("allInputs", false)
    fun setAllInputs(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("allInputs", v).apply()
    /** Weather next to the clock and in the Live weather background (uses the network; off = none of it). */
    fun weather(ctx: Context) = p(ctx).getBoolean("weather", true)
    fun setWeather(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("weather", v).apply()
    /** "auto" (from the TV's country), "f" or "c". */
    fun units(ctx: Context) = p(ctx).getString("units", "auto")!!
    fun setUnits(ctx: Context, v: String) = p(ctx).edit().putString("units", v).apply()
    /** Off by default: TV Home's helper can take over the Home button, but only once the viewer turns this on. */
    fun takeOverHome(ctx: Context) = p(ctx).getBoolean("takeOverHome", false)
    fun setTakeOverHome(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("takeOverHome", v).apply()
    /** Soft pops as the selection moves and on OK (see [Sounds]). */
    fun sounds(ctx: Context) = p(ctx).getBoolean("sounds", true)
    fun setSounds(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("sounds", v).apply()

    val BACKGROUNDS = listOf(
        Triple("apod", "NASA picture of the day", "A new real space photo every day, slowly panning"),
        Triple("sky", "Live weather", "Your real sky right now: clouds, rain, snow and wind, with today's actual sun and moon"),
        Triple("space", "Deep space", "Drifting stars, a nebula, a planet, shooting stars"),
        Triple("aurora", "Aurora", "Northern lights over the pines"),
        Triple("glow", "Glow", "Soft colours drifting slowly"),
        Triple("plain", "Plain", "Dark and still"),
    )
}

/** Right-hand settings sheet. Changes apply live behind it. */
class SettingsPanel(private val ctx: Context, private val onChange: () -> Unit, private val onClose: () -> Unit) {
    val root = FrameLayout(ctx).apply { visibility = View.GONE; isClickable = true }
    private val sheet = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xFF0F1118.toInt())
            cornerRadii = floatArrayOf(Ui.pxf(28), Ui.pxf(28), 0f, 0f, 0f, 0f, Ui.pxf(28), Ui.pxf(28))
        }
        setPadding(Ui.px(56), Ui.px(44), Ui.px(56), Ui.px(30))
        elevation = Ui.pxf(30)
    }
    private val bgRows = ArrayList<Pair<String, Row>>()
    private lateinit var artRow: Row
    private lateinit var seasonRow: Row
    private lateinit var homeRow: Row
    private lateinit var inputsRow: Row
    private lateinit var weatherRow: Row
    private lateinit var locationRow: Row
    private lateinit var unitsRow: Row
    private lateinit var soundsRow: Row
    private lateinit var slide: View
    val isOpen get() = root.visibility == View.VISIBLE

    private inner class Row(title: String, val desc: String, val onPick: () -> Unit) : LinearLayout(ctx) {
        val mark: TextView = Ui.text(ctx, 26, Ui.MUTED).apply { setShadowLayer(0f, 0f, 0f, 0) }
        val titleTv: TextView = Ui.text(ctx, 26, Ui.INK).apply { text = title; setShadowLayer(0f, 0f, 0f, 0) }
        val descTv: TextView = Ui.text(ctx, 18, Ui.MUTED, lines = 2).apply { text = desc; setShadowLayer(0f, 0f, 0f, 0) }
        private val bg = GradientDrawable().apply { cornerRadius = Ui.pxf(16); setColor(Color.TRANSPARENT) }
        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isFocusable = true; background = bg
            setPadding(Ui.px(20), Ui.px(14), Ui.px(20), Ui.px(14))
            addView(mark, LayoutParams(Ui.px(48), Ui.WRAP))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL }
            col.addView(titleTv, LayoutParams(Ui.WRAP, Ui.WRAP))
            col.addView(descTv, LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(4) })
            addView(col, LayoutParams(0, Ui.WRAP, 1f))
            setOnClickListener { onPick() }
            setOnFocusChangeListener { _, f ->
                bg.setColor(if (f) Color.WHITE else Color.TRANSPARENT)
                titleTv.setTextColor(if (f) 0xFF0B0D12.toInt() else Ui.INK)
                descTv.setTextColor(if (f) 0xFF4A5160.toInt() else Ui.MUTED)
                mark.setTextColor(if (f) 0xFF0B0D12.toInt() else if (mark.tag == true) Ui.AMBER else Ui.MUTED)
                animate().scaleX(if (f) 1.02f else 1f).scaleY(if (f) 1.02f else 1f).setDuration(150).start()
            }
        }
        fun setMark(on: Boolean, radio: Boolean) {
            mark.tag = on
            mark.text = if (radio) (if (on) "◉" else "○") else (if (on) "✔" else "—")
            mark.setTextColor(if (isFocused) 0xFF0B0D12.toInt() else if (on) Ui.AMBER else Ui.MUTED)
        }
    }

    init {
        root.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, intArrayOf(0xB3000000.toInt(), 0x33000000, 0x00000000))
        }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        sheet.addView(Ui.text(ctx, 44, Ui.INK, Ui.LIGHT).apply { text = "Settings" })
        sheet.addView(section("BACKGROUND"))
        for ((key, title, desc) in Prefs.BACKGROUNDS) {
            val row = Row(title, desc) { Prefs.setBackground(ctx, key); refresh(); onChange() }
            bgRows.add(key to row)
            sheet.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.px(4) })
        }
        sheet.addView(section("WHILE MUSIC PLAYS"))
        artRow = Row("Album art background", "Blurred cover art behind everything") { Prefs.setAlbumArt(ctx, !Prefs.albumArt(ctx)); refresh(); onChange() }
        sheet.addView(artRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        sheet.addView(section("SEASONAL"))
        seasonRow = Row("Seasonal effects", seasonDesc()) { Prefs.setSeasonal(ctx, !Prefs.seasonal(ctx)); refresh(); onChange() }
        sheet.addView(seasonRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        sheet.addView(section("WEATHER"))
        weatherRow = Row("Weather and temperature", weatherDesc()) { Prefs.setWeather(ctx, !Prefs.weather(ctx)); refresh(); onChange() }
        sheet.addView(weatherRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        locationRow = Row("Weather location", placeDesc()) {
            val note = Weather.source.placeNote
            if (note != null) (ctx as? MainActivity)?.toast(note)
            else LocationPicker.open(ctx) { refresh(); onChange() }
        }
        sheet.addView(locationRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        unitsRow = Row("Temperature in °F", "Switch between °F and °C") {
            Prefs.setUnits(ctx, if (Weather.useFahrenheit(ctx)) "c" else "f"); refresh(); onChange()
        }
        sheet.addView(unitsRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        sheet.addView(section("APPS ROW"))
        sheet.addView(Ui.text(ctx, 18, Ui.MUTED, lines = 3).apply {
            text = "Hold OK on an app in the row to move it or take it off. Add apps from All apps (hold OK there too)."
            setShadowLayer(0f, 0f, 0f, 0)
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { leftMargin = Ui.px(20); rightMargin = Ui.px(20) })
        sheet.addView(section("SOUNDS"))
        soundsRow = Row("Remote sounds", "A soft pop as you move around, and a deeper one on OK") {
            val on = !Prefs.sounds(ctx)
            Prefs.setSounds(ctx, on); refresh()
            if (on) Sounds.click(ctx)   // so you hear what you turned on
        }
        sheet.addView(soundsRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        sheet.addView(section("INPUTS"))
        inputsRow = Row("Always show HDMI inputs", "Off: only inputs with something switched on are shown") {
            Prefs.setAllInputs(ctx, !Prefs.allInputs(ctx)); refresh(); onChange()
        }
        sheet.addView(inputsRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        sheet.addView(section("HOME BUTTON"))
        homeRow = Row("Home button opens TV Home", "Off: the remote's Home button still goes to Google TV") {
            Prefs.setTakeOverHome(ctx, !Prefs.takeOverHome(ctx)); refresh(); onChange()
        }
        sheet.addView(homeRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        sheet.addView(Ui.text(ctx, 17, Ui.MUTED).apply { text = "Back to close"; setShadowLayer(0f, 0f, 0f, 0) },
            LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(28) })
        val scroll = android.widget.ScrollView(ctx).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            background = sheet.background; sheet.background = null; elevation = sheet.elevation
            clipToOutline = true
        }
        scroll.addView(sheet, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        root.addView(scroll, FrameLayout.LayoutParams(Ui.px(640), Ui.MATCH, Gravity.END))
        slide = scroll
    }

    private fun weatherDesc() =
        if (Prefs.weather(ctx)) "Temperature by the clock and the Live weather sky, from ${Weather.source.credit}"
        else "Off: no weather and no location lookups"

    private fun placeDesc(): String {
        Weather.source.placeNote?.let { return it }
        val p = Place.current
        return when {
            p == null -> "Automatic: your area, worked out from your internet address. Press to enter a ZIP code or town"
            p.source == "ip" -> "${p.name.ifBlank { "Your area" }}, worked out from your internet address. Press to enter a ZIP code or town"
            p.source == "manual" -> "${p.name.ifBlank { "Set by you" }} (set by you). Press to change"
            else -> p.name.ifBlank { "Set" }
        }
    }

    private fun seasonDesc() = when (SeasonalView.pick()) {
        SeasonalView.Theme.LEAVES -> "Falling leaves this month"
        SeasonalView.Theme.SNOW -> if (Prefs.background(ctx) == "sky") "Snow this month (Live weather shows real snow instead)" else "Snow this month"
        SeasonalView.Theme.FIREWORKS -> "Fireworks tonight"
        SeasonalView.Theme.HEARTS -> "Hearts today"
        SeasonalView.Theme.NONE -> "Nothing this month; leaves, snow, fireworks and hearts come with the dates"
    }

    private fun section(t: String) = Ui.text(ctx, 17, Ui.AMBER, Ui.MED).apply {
        text = t; letterSpacing = 0.14f; setShadowLayer(0f, 0f, 0f, 0)
        layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(24); bottomMargin = Ui.px(4); leftMargin = Ui.px(20) }
    }

    private fun refresh() {
        val cur = Prefs.background(ctx)
        for ((k, row) in bgRows) row.setMark(k == cur, radio = true)
        artRow.setMark(Prefs.albumArt(ctx), radio = false)
        seasonRow.setMark(Prefs.seasonal(ctx), radio = false)
        homeRow.setMark(Prefs.takeOverHome(ctx), radio = false)
        inputsRow.setMark(Prefs.allInputs(ctx), radio = false)
        soundsRow.setMark(Prefs.sounds(ctx), radio = false)
        weatherRow.setMark(Prefs.weather(ctx), radio = false)
        weatherRow.descTv.text = weatherDesc()
        locationRow.descTv.text = placeDesc()
        locationRow.setMark(Place.current?.source == "manual", radio = false)
        val f = Weather.useFahrenheit(ctx)
        unitsRow.titleTv.text = if (f) "Temperature in °F" else "Temperature in °C"
        unitsRow.setMark(true, radio = false)
    }

    fun open() {
        refresh()
        root.visibility = View.VISIBLE
        root.alpha = 0f; root.animate().alpha(1f).setDuration(250).start()
        slide.translationX = Ui.pxf(640)
        slide.animate().translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator(2.2f)).start()
        val cur = Prefs.background(ctx)
        sheet.post { (bgRows.firstOrNull { it.first == cur }?.second ?: bgRows.first().second).requestFocus() }
    }

    fun close() {
        slide.animate().translationX(Ui.pxf(640)).setDuration(240).start()
        root.animate().alpha(0f).setDuration(260).withEndAction { root.visibility = View.GONE }.start()
        onClose()
    }
}

/** Settings → Weather location: type a ZIP/postal code or a town with the remote, then pick the right match. */
object LocationPicker {
    fun open(ctx: Context, done: () -> Unit) {
        val act = ctx as? android.app.Activity ?: return
        val input = EditText(act).apply {
            hint = "ZIP code or town"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            Place.current?.takeIf { it.source == "manual" }?.let { setText(it.name); selectAll() }
        }
        val box = FrameLayout(act).apply { setPadding(Ui.px(56), Ui.px(8), Ui.px(56), 0); addView(input) }
        val prompt = "Type a ZIP or postal code, or a town name."
        val dlg = AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Weather location")
            .setMessage(prompt)
            .setView(box)
            .setPositiveButton("Search", null)
            .setNeutralButton("Automatic") { _, _ ->
                Place.clear(act); done(); (act as? MainActivity)?.toast("Weather for your area, from your internet address")
            }
            .setNegativeButton("Cancel", null)
            .create()

        fun choose(h: Geo.Hit) {
            Place.set(act, Place.P(h.lat, h.lon, h.name, "manual", System.currentTimeMillis()))
            done()
            (act as? MainActivity)?.toast("Weather for ${h.name}")
        }

        fun search() {
            val q = input.text.toString().trim()
            if (q.length < 2) { dlg.setMessage("Type at least two letters or digits."); return }
            val btn = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            btn.isEnabled = false
            dlg.setMessage("Searching…")
            Net.io.execute {
                val r = runCatching { Geo.search(q) }
                act.runOnUiThread {
                    btn.isEnabled = true
                    val hits = r.getOrNull()
                    when {
                        hits == null -> dlg.setMessage("Couldn't search. Is the TV online?")
                        hits.isEmpty() -> dlg.setMessage("Nothing found for \"$q\". Try a ZIP code or a bigger town nearby.")
                        else -> {
                            dlg.dismiss()
                            if (hits.size == 1) choose(hits[0]) else Ui.menu(act, "Which one?", hits.map { h -> h.label to { choose(h) } })
                        }
                    }
                }
            }
        }

        input.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE || ev?.keyCode == KeyEvent.KEYCODE_ENTER) { search(); true } else false
        }
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { search() }
            input.requestFocus()
        }
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dlg.show()
    }
}
