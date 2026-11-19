package com.cesmotacon.tvhome

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    companion object {
        @Volatile var instance: MainActivity? = null
        private const val AMBIENT_AFTER_MS = 120_000L
    }

    val handler = Handler(Looper.getMainLooper())
    val scope = MainScope()

    lateinit var root: FrameLayout
        private set
    private lateinit var animBg: AnimatedBg
    private lateinit var settings: SettingsPanel
    private lateinit var gear: TextView
    private lateinit var bg: BackgroundView
    private lateinit var seasonal: SeasonalView
    private lateinit var glow: GlowView
    private lateinit var content: RowNav
    private lateinit var greetBox: LinearLayout
    private lateinit var greet: TextView
    private lateinit var dateTv: TextView
    private lateinit var npBox: LinearLayout
    private lateinit var npCover: ImageView
    private lateinit var npSource: TextView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var rightBox: LinearLayout
    private lateinit var clockTv: TextView
    private lateinit var sepV: View
    private lateinit var wxTv: TextView
    private lateinit var tempTv: TextView
    private lateinit var chipsRow: LinearLayout
    /** The rows, top to bottom: Apps, Inputs, then whatever the edition adds. */
    lateinit var rowsBox: LinearLayout
        private set
    private lateinit var appRow: LinearLayout
    private lateinit var inputLabel: TextView
    private lateinit var inputRow: LinearLayout
    private lateinit var caption: TextView
    private lateinit var ambient: FrameLayout
    private lateinit var ambClock: TextView
    private lateinit var ambSub: TextView
    private lateinit var toastTv: TextView

    private var appSignature = ""
    private var lastFocused: View? = null
    private var shownClock = ""
    private var inAmbient = false
    private var swallowUp = false
    private var apod: Apod.Pic? = null
    private var showingArt = false
    private var lastApodTry = 0L
    var resumed = false
        private set

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        Place.load(this)
        Edition.init(this)
        build()
        setContentView(root)
        Sounds.attach(this)
        // TVs have no system bars; on other devices (and emulators) hide them so the home screen fills the screen
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(android.view.WindowInsets.Type.systemBars())
            it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        loadApod()
    }

    override fun onResume() {
        super.onResume()
        instance = this
        refreshApps()
        refreshInputs()
        runCatching { getSystemService(TvInputManager::class.java)?.registerCallback(inputCallback, handler) }
        Weather.addListener(weatherListener)
        Weather.source.refresh(this)
        NowPlaying.addListener(npListener)
        resumed = true
        updateNowPlaying()
        applyBackground()
        applySeasonal()
        updateWeatherUi()
        bg.resume()
        Edition.onHomeResume(this)
        tick()
        intro()
        poke()
        if (System.currentTimeMillis() - lastApodTry > 3 * 3600_000L) loadApod()
        handler.post { (lastFocused?.takeIf { it.isAttachedToWindow } ?: appRow.getChildAt(0))?.requestFocus() }
    }

    override fun onPause() {
        super.onPause()
        Weather.removeListener(weatherListener)
        NowPlaying.removeListener(npListener)
        Edition.onHomePause(this)
        runCatching { getSystemService(TvInputManager::class.java)?.unregisterCallback(inputCallback) }
        resumed = false
        seasonal.stop()
        animBg.stop()
        bg.pause()
        if (settings.isOpen) settings.close()
        handler.removeCallbacksAndMessages(null)
        if (inAmbient) exitAmbient(false)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the first app.
        if (inAmbient) exitAmbient(true) else appRow.getChildAt(0)?.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            poke()
            Beat.hold(900)   // background stays still while you move around, so the tiles respond at once
            if (inAmbient) { exitAmbient(true); swallowUp = true; return true }
        } else if (event.action == KeyEvent.ACTION_UP && swallowUp) { swallowUp = false; return true }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {   // closes settings; otherwise nothing to go back to from home
            if (settings.isOpen && event.action == KeyEvent.ACTION_UP) settings.close()
            return true
        }
        Sounds.key(this, event)
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- layout

    private fun build() {
        val lp = { w: Int, h: Int -> FrameLayout.LayoutParams(w, h) }
        root = FrameLayout(this).apply {
            clipChildren = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0B0D1A.toInt(), 0xFF141030.toInt(), 0xFF05060B.toInt()))
        }
        animBg = AnimatedBg(this).apply { visibility = View.GONE }; root.addView(animBg, lp(Ui.MATCH, Ui.MATCH))
        bg = BackgroundView(this); root.addView(bg, lp(Ui.MATCH, Ui.MATCH))
        seasonal = SeasonalView(this); root.addView(seasonal, lp(Ui.MATCH, Ui.MATCH))
        root.addView(View(this).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)   // never changes: painted once, then reused as one picture
            background = LayerDrawable(arrayOf(
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xA6040509.toInt(), 0x38040509, 0x00040509, 0x00040509)),
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66040509, 0x00040509, 0x00040509, 0x99040509.toInt()))))
        }, lp(Ui.MATCH, Ui.MATCH))
        glow = GlowView(this); root.addView(glow, lp(Ui.MATCH, Ui.MATCH))

        // remote navigation goes row by row: Settings, then Apps, Inputs and whatever rows the edition adds
        content = RowNav(this).apply {
            clipChildren = false; clipToPadding = false
            rows = { listOf<View>(chipsRow) + (0 until rowsBox.childCount).map { rowsBox.getChildAt(it) } }
        }
        root.addView(content, lp(Ui.MATCH, Ui.MATCH))

        // greeting (top left)
        greetBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        greet = Ui.text(this, 66, Ui.INK, Ui.LIGHT); greetBox.addView(greet, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        dateTv = Ui.text(this, 27, 0xFFB3BBC8.toInt()); greetBox.addView(dateTv, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(10) })
        content.addView(greetBox, Ui.frameLp(Ui.WRAP, Ui.WRAP, Ui.px(96), Ui.px(60)))

        // now playing (replaces the greeting while music plays)
        npBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        npCover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.round(0xFF222631.toInt(), 16); clipToOutline = true; elevation = Ui.pxf(16)
        }
        npBox.addView(npCover, LinearLayout.LayoutParams(Ui.px(160), Ui.px(160)))
        val npText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        npSource = Ui.text(this, 18, Ui.AMBER, Ui.MED).apply { letterSpacing = 0.12f }
        npTitle = Ui.text(this, 42, Ui.INK, Ui.REG)
        npArtist = Ui.text(this, 27, 0xFFC3C9D4.toInt())
        npText.addView(npSource, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        npText.addView(npTitle, LinearLayout.LayoutParams(Ui.px(760), Ui.WRAP).apply { topMargin = Ui.px(10) })
        npText.addView(npArtist, LinearLayout.LayoutParams(Ui.px(760), Ui.WRAP).apply { topMargin = Ui.px(8) })
        npBox.addView(npText, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.px(30) })
        content.addView(npBox, Ui.frameLp(Ui.WRAP, Ui.WRAP, Ui.px(96), Ui.px(56)))

        // clock + outdoor temperature (top right)
        rightBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END; clipChildren = false; clipToPadding = false; setPadding(Ui.px(20), 0, Ui.px(4), Ui.px(10)) }
        val clockRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        clockTv = Ui.text(this, 58, Ui.INK, Ui.LIGHT)
        sepV = View(this).apply { setBackgroundColor(0x2EFFFFFF); visibility = View.GONE }
        wxTv = Ui.text(this, 42).apply { setShadowLayer(0f, 0f, 0f, 0); visibility = View.GONE }
        tempTv = Ui.text(this, 58, Ui.INK, Ui.LIGHT).apply { visibility = View.GONE }
        clockRow.addView(clockTv)
        clockRow.addView(sepV, LinearLayout.LayoutParams(Ui.px(2), Ui.px(50)).apply { leftMargin = Ui.px(26); rightMargin = Ui.px(24) })
        clockRow.addView(wxTv, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { rightMargin = Ui.px(12) })
        clockRow.addView(tempTv)
        rightBox.addView(clockRow, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP))
        chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        gear = Ui.text(this, 22, 0xFFD7DCE5.toInt()).apply {
            text = "⚙  Settings"; isFocusable = true
            background = Ui.round(Ui.GLASS, 999); setPadding(Ui.px(20), Ui.px(10), Ui.px(22), Ui.px(10))
            setOnClickListener { openSettings() }
            setOnFocusChangeListener { v, f ->
                (background as GradientDrawable).setColor(if (f) Color.WHITE else Ui.GLASS)
                setTextColor(if (f) 0xFF0B0D12.toInt() else 0xFFD7DCE5.toInt())
                setShadowLayer(if (f) 0f else Ui.pxf(12), 0f, if (f) 0f else Ui.pxf(2), 0x66000000)
                v.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(180).start()
                if (f) { lastFocused = v; v.post { val (x, y) = v.centerIn(root); glow.moveTo(x, y, 0xFF9AA4B8.toInt()) } }
            }
        }
        chipsRow.addView(gear, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.px(14) })
        rightBox.addView(chipsRow, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(22) })
        content.addView(rightBox, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).apply { topMargin = Ui.px(60); rightMargin = Ui.px(92) })

        // rows
        rowsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        appRow = row(); inputRow = row()
        rowsBox.addView(rowLabel("Apps"))
        rowsBox.addView(appRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.px(125)).apply { bottomMargin = Ui.px(46) })
        inputLabel = rowLabel("Inputs"); rowsBox.addView(inputLabel)
        rowsBox.addView(inputRow, LinearLayout.LayoutParams(Ui.MATCH, Ui.px(92)).apply { bottomMargin = Ui.px(46) })
        content.addView(rowsBox, Ui.frameLp(Ui.MATCH, Ui.WRAP, Ui.px(96), Ui.px(318)).apply { rightMargin = Ui.px(96) })

        caption = Ui.text(this, 18, 0xCCB3BBC8.toInt())
        content.addView(caption, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END).apply { rightMargin = Ui.px(64); bottomMargin = Ui.px(36) })

        // ambient
        ambient = FrameLayout(this).apply { alpha = 0f }
        val ambCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ambClock = Ui.text(this, 170, Ui.INK, Ui.THIN).apply { letterSpacing = -0.02f }
        ambSub = Ui.text(this, 32, 0xFFD4DAE4.toInt())
        ambCol.addView(ambClock, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP)); ambCol.addView(ambSub, LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { topMargin = Ui.px(18) })
        ambient.addView(ambCol, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply { leftMargin = Ui.px(96); bottomMargin = Ui.px(84) })
        root.addView(ambient, lp(Ui.MATCH, Ui.MATCH))

        settings = SettingsPanel(this, onChange = {
            applyBackground(); applySeasonal(); refreshInputs(); updateWeatherUi(); Weather.source.refresh(this)
        }, onClose = {
            content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            gear.requestFocus()
        })
        root.addView(settings.root, lp(Ui.MATCH, Ui.MATCH))

        toastTv = Ui.text(this, 23).apply {
            background = Ui.round(0xE6141620.toInt(), 999); setPadding(Ui.px(28), Ui.px(16), Ui.px(28), Ui.px(16)); alpha = 0f; elevation = Ui.pxf(20)
        }
        root.addView(toastTv, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply { leftMargin = Ui.px(96); bottomMargin = Ui.px(64) })

        Edition.buildHome(this)
    }

    // ---------------------------------------------------------------- building blocks (also used by editions)

    fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }

    fun rowLabel(t: String) = Ui.text(this, 25, 0xFFAAB2BF.toInt(), Ui.MED).apply {
        text = t; letterSpacing = 0.02f
        layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { bottomMargin = Ui.px(18) }
    }

    /** A status pill (dot + text) for the row next to Settings; hidden until [setChip] gives it text. */
    fun chip(): Pair<View, TextView> {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Ui.round(Ui.GLASS, 999); setPadding(Ui.px(20), Ui.px(10), Ui.px(20), Ui.px(10)); visibility = View.GONE
        }
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.OK) } }
        val tv = Ui.text(this, 22, 0xFFD7DCE5.toInt())
        box.addView(dot, LinearLayout.LayoutParams(Ui.px(10), Ui.px(10)).apply { rightMargin = Ui.px(11) })
        box.addView(tv)
        box.layoutParams = LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.px(14) }
        box.tag = dot
        return box to tv
    }

    /** Adds a pill (from [chip]) before the Settings button. */
    fun addChip(c: Pair<View, TextView>) = chipsRow.addView(c.first, chipsRow.indexOfChild(gear))

    fun setChip(c: Pair<View, TextView>, text: String?, dot: Int = Ui.OK, alert: Boolean = false) {
        val (box, tv) = c
        if (text == null) { box.visibility = View.GONE; return }
        box.visibility = View.VISIBLE
        tv.text = text
        ((box.tag as View).background as GradientDrawable).setColor(dot)
        (box.background as GradientDrawable).setColor(if (alert) 0x52FF4B4B else Ui.GLASS)
    }

    /** The soft light that follows focus. Running tiles glow in their own colour. */
    fun focusGlow(t: TileView, color: Int) {
        t.onFocus = { v ->
            lastFocused = v
            v.post { val (x, y) = v.centerIn(root); glow.moveTo(x, y, if (v.running) (v.palette?.get(0) ?: color) else color) }
        }
    }

    // ---------------------------------------------------------------- rows

    /** Cheap check (no icon loading) so returning home doesn't rebuild the row unless an app or the row changed. */
    private fun appsSignature(favs: List<String>) = favs.joinToString { pkg ->
        runCatching { val pi = packageManager.getPackageInfo(pkg, 0); "$pkg:${pi.lastUpdateTime}:${pi.applicationInfo?.enabled}" }.getOrDefault("$pkg:-")
    }

    fun refreshApps(force: Boolean = false, focusPkg: String? = null) {
        val favs = Apps.favorites(this)
        val sig = appsSignature(favs)
        if (sig == appSignature && !force) return
        appSignature = sig
        val entries = favs.mapNotNull { Apps.entry(this, it) }
        appRow.removeAllViews()
        val w = Ui.px(222); val h = Ui.px(125)
        var focusTile: View? = null
        for ((i, e) in entries.take(6).withIndex()) {
            val t = Apps.tile(this, e, w, h)
            // where the selection starts when TV Home opens (otherwise Android picks Settings, the first in the layout)
            t.isFocusedByDefault = i == 0
            focusGlow(t, t.accent)
            t.onLongClick = { appMenu(e) }
            appRow.addView(t, Ui.linLp(w, h, right = Ui.px(26)))
            if (e.pkg == focusPkg) focusTile = t
        }
        val all = TileView(this, w, h, Ui.pxf(22), 0x8C1E212A.toInt())
        all.addView(Ui.text(this, 36, 0xFFCFD6E2.toInt()).apply { text = "☷" }, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP).apply { leftMargin = Ui.px(20); topMargin = Ui.px(12) })
        all.addView(Ui.text(this, 23, Color.WHITE, Ui.MED).apply { text = "All apps" }, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).apply { leftMargin = Ui.px(20); bottomMargin = Ui.px(16) })
        all.onClick = { startActivity(Intent(this, AllAppsActivity::class.java)) }
        all.isFocusedByDefault = entries.isEmpty()
        focusGlow(all, 0xFF9AA4B8.toInt())
        appRow.addView(all, Ui.linLp(w, h))
        focusTile?.let { t -> t.post { t.requestFocus() } }
    }

    /** Hold OK on an app in the row. */
    private fun appMenu(e: Apps.Entry) {
        val favs = Apps.favorites(this)
        val i = favs.indexOf(e.pkg)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (i > 0) items.add("Move left" to { Apps.move(this, e.pkg, -1); refreshApps(true, e.pkg) })
        if (i in 0 until minOf(favs.size, 6) - 1) items.add("Move right" to { Apps.move(this, e.pkg, 1); refreshApps(true, e.pkg) })
        items.add("Remove from Apps row" to {
            Apps.removeFavorite(this, e.pkg); refreshApps(true)
            appRow.getChildAt(0)?.requestFocus()
            toast("${e.label} removed. Add apps back from All apps.")
        })
        items.add("Open" to { Apps.launch(this, e) })
        Ui.menu(this, e.label, items)
    }

    private var inputSignature: String? = null
    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) = refreshInputs()
        override fun onInputAdded(inputId: String) = refreshInputs()
        override fun onInputRemoved(inputId: String) = refreshInputs()
        override fun onInputUpdated(inputId: String) = refreshInputs()
    }

    /**
     * HDMI inputs that have something switched on. Some TVs (TCL) report "standby" for empty ports too, so only a real
     * CONNECTED state counts (Settings → "Always show HDMI inputs" overrides this).
     */
    private fun refreshInputs() {
        val tim = getSystemService(TvInputManager::class.java)
        val all = Prefs.allInputs(this)
        val hdmi = runCatching { tim?.tvInputList.orEmpty().filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null && !it.isHidden(this) } }
            .getOrDefault(emptyList())
        val shown = hdmi.filter { all || runCatching { tim?.getInputState(it.id) }.getOrNull() == TvInputManager.INPUT_STATE_CONNECTED }
        val sig = "$all|" + shown.joinToString { "${it.id}:${it.loadCustomLabel(this)}" }
        if (sig == inputSignature) return
        inputSignature = sig
        inputRow.removeAllViews()
        val inputs = runCatching {
            shown
                .sortedBy { (it.loadCustomLabel(this)?.toString()?.takeIf { s -> s.isNotBlank() } ?: it.loadLabel(this).toString())
                    .replace(Regex("\\d+")) { m -> m.value.padStart(4, '0') } }
        }.getOrDefault(emptyList())
        inputLabel.visibility = if (inputs.isEmpty()) View.GONE else View.VISIBLE
        inputRow.visibility = inputLabel.visibility
        val w = Ui.px(200); val h = Ui.px(92)
        for (inp in inputs) {
            val label = inp.loadCustomLabel(this)?.toString()?.takeIf { it.isNotBlank() } ?: inp.loadLabel(this).toString()
            val t = TileView(this, w, h, Ui.pxf(22), Ui.GLASS)
            t.addView(Ui.text(this, 22, 0xFFDFE3EA.toInt(), Ui.MED).apply { text = label; gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            t.onClick = {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inp.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { toast("Couldn't switch to $label") }
            }
            focusGlow(t, 0xFF7F8CFF.toInt())
            inputRow.addView(t, Ui.linLp(w, h, right = Ui.px(26)))
        }
    }

    // ---------------------------------------------------------------- weather

    private val weatherListener: () -> Unit = { updateWeatherUi() }

    /** Outdoor temperature and a little weather icon next to the clock, the Live weather caption, the ambient line. */
    fun updateWeatherUi() {
        if (!::tempTv.isInitialized) return
        val w = if (Prefs.weather(this)) Weather.current() else null
        val t = w?.tempF?.let { Weather.tempText(this, it) }
        tempTv.text = t ?: ""
        wxTv.text = if (t != null) Weather.emoji(w) else ""
        for (v in listOf(sepV, wxTv, tempTv)) v.visibility = if (t != null) View.VISIBLE else View.GONE
        updateSkyCaption()
        updateAmbientText()
    }

    private fun updateSkyCaption() {
        if (!::caption.isInitialized || showingArt || Prefs.background(this) != "sky") return
        val txt = Weather.caption(this, Weather.currentOrFair())
        if (caption.text.toString() != txt) caption.text = txt
    }

    private fun refreshSky() { scope.launch { SkyAssets.refresh(this@MainActivity); updateSkyCaption() } }

    // ---------------------------------------------------------------- background: APOD / album art / animated

    private fun loadApod() {
        lastApodTry = System.currentTimeMillis()
        scope.launch {
            val pic = Apod.load(this@MainActivity, resources.displayMetrics.widthPixels) ?: return@launch
            apod = pic
            applyBackground()
        }
    }

    private fun showApod() {
        val pic = apod ?: return
        showingArt = false
        bg.show(pic.bitmap, "apod:${pic.date}", blur = false)
        caption.text = "NASA Astronomy Picture of the Day · ${pic.title}" + (pic.credit?.let { " · © $it" } ?: "")
        caption.animate().alpha(1f).setDuration(800).start()
    }

    private val npListener: () -> Unit = { updateNowPlaying() }
    private val revertArt = Runnable { if (NowPlaying.current?.playing != true) { setNowPlayingUi(null); applyBackground() } }

    private fun updateNowPlaying() {
        val tr = NowPlaying.current
        if (tr != null && tr.playing) {
            handler.removeCallbacks(revertArt)
            setNowPlayingUi(tr)
            applyBackground()
        } else if (npBox.visibility == View.VISIBLE || showingArt) {
            handler.removeCallbacks(revertArt)
            handler.postDelayed(revertArt, 8_000)
        }
        updateAmbientText()
    }

    /** What's behind everything: album art (if on and music is playing), else the background chosen in Settings. */
    fun applyBackground() {
        val mode = Prefs.background(this)
        val canvasMode = when (mode) {
            "space" -> AnimatedBg.Mode.SPACE; "aurora" -> AnimatedBg.Mode.AURORA; "glow" -> AnimatedBg.Mode.LIGHTS
            "sky" -> AnimatedBg.Mode.SKY; else -> null
        }
        refreshGlow()
        if (mode == "sky") refreshSky()
        if (canvasMode != null) {
            animBg.mode = canvasMode
            if (animBg.visibility != View.VISIBLE) { animBg.visibility = View.VISIBLE; animBg.alpha = 0f; animBg.animate().alpha(1f).setDuration(900).start() }
            if (resumed) animBg.start()
        } else { animBg.stop(); animBg.visibility = View.GONE }

        val tr = NowPlaying.current
        val art = tr?.takeIf { it.playing && Prefs.albumArt(this) }?.art
        when {
            art != null && tr != null -> {
                showingArt = true
                bg.show(art, "art:${tr.pkg}|${tr.title}|${tr.artist}", blur = true)
                caption.animate().alpha(0f).setDuration(600).start()
            }
            mode == "apod" && apod != null -> showApod()
            else -> {
                showingArt = false
                bg.clear()
                if (mode == "sky") { updateSkyCaption(); caption.animate().alpha(1f).setDuration(800).start() }
                else caption.animate().alpha(0f).setDuration(400).start()
                if (mode == "apod" && System.currentTimeMillis() - lastApodTry > 60_000) loadApod()
            }
        }
    }

    /** Re-reads [Edition.glowColors] (editions call this when, say, a light show starts or stops). */
    fun refreshGlow() { animBg.lightsColors = Edition.glowColors() }

    private fun applySeasonal() {
        seasonal.noSnow = Prefs.background(this) == "sky"
        if (Prefs.seasonal(this)) {
            seasonal.visibility = View.VISIBLE
            seasonal.override = prefSeason()
            if (resumed) seasonal.start()
        } else { seasonal.stop(); seasonal.visibility = View.GONE }
    }

    private fun openSettings() {
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        settings.open()
    }

    private fun setNowPlayingUi(tr: NowPlaying.Track?) {
        if (tr == null) {
            if (npBox.visibility == View.VISIBLE) {
                npBox.animate().alpha(0f).setDuration(400).withEndAction { npBox.visibility = View.GONE; greetBox.visibility = View.VISIBLE; greetBox.alpha = 0f; greetBox.animate().alpha(1f).setDuration(500).start() }.start()
            }
            return
        }
        val appLabel = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(tr.pkg, 0)).toString() }.getOrDefault("")
        npSource.text = "NOW PLAYING" + if (appLabel.isNotBlank()) " · ${appLabel.uppercase()}" else ""
        npTitle.text = tr.title
        npArtist.text = tr.artist
        if (tr.art != null) npCover.setImageBitmap(tr.art) else npCover.setImageDrawable(null)
        if (npBox.visibility != View.VISIBLE) {
            greetBox.visibility = View.GONE
            npBox.visibility = View.VISIBLE; npBox.alpha = 0f; npBox.translationX = -Ui.pxf(40)
            npBox.animate().alpha(1f).translationX(0f).setDuration(700).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    // ---------------------------------------------------------------- clock, intro, ambient, toast

    private fun tick() {
        val now = LocalDateTime.now()
        val txt = now.format(DateTimeFormatter.ofPattern("h:mm"))
        if (txt != shownClock) {
            val first = shownClock.isEmpty()
            shownClock = txt
            if (first) clockTv.text = txt else rollClock(txt)
            ambClock.text = txt
            val h = now.hour
            greet.text = if (h < 5 || h >= 17) "Good evening" else if (h < 12) "Good morning" else "Good afternoon"
            dateTv.text = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
            if (Prefs.seasonal(this) && seasonal.override == null && seasonal.theme != seasonal.wanted(now)) seasonal.start()
            if (resumed) {
                Weather.source.refresh(this)                                     // only fetches when stale
                if (Prefs.background(this) == "sky") refreshSky()                // NASA pictures: only re-fetched when stale
            }
            updateWeatherUi()
        }
        handler.postDelayed({ tick() }, 1000)
    }

    private fun rollClock(txt: String) {
        clockTv.animate().translationY(-Ui.pxf(28)).alpha(0f).setDuration(260).withEndAction {
            clockTv.text = txt; clockTv.translationY = Ui.pxf(28)
            clockTv.animate().translationY(0f).alpha(1f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        }.start()
    }

    private fun intro() {
        greetBox.alpha = 0f; greetBox.translationX = -Ui.pxf(40)
        greetBox.animate().alpha(1f).translationX(0f).setDuration(450).setInterpolator(DecelerateInterpolator(2f)).start()
        rightBox.alpha = 0f; rightBox.translationY = -Ui.pxf(24)
        rightBox.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(450).setInterpolator(DecelerateInterpolator(2f)).start()
        var i = 0
        fun walk(g: ViewGroup) {
            for (c in 0 until g.childCount) {
                val v = g.getChildAt(c)
                if (v is TileView) {
                    v.animate().cancel()
                    v.alpha = 0f; v.translationY = Ui.pxf(30); v.scaleX = 0.95f; v.scaleY = 0.95f
                    v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(40L + i * 18L).setDuration(380)
                        .setInterpolator(OvershootInterpolator(0.7f)).withEndAction { if (v.isFocused) v.applyFocus(true) }.start()
                    i++
                } else if (v is ViewGroup) walk(v)
            }
        }
        walk(rowsBox)
    }

    private val goAmbient = Runnable { enterAmbient() }
    private fun poke() { handler.removeCallbacks(goAmbient); handler.postDelayed(goAmbient, AMBIENT_AFTER_MS) }

    fun enterAmbient() {
        if (inAmbient) return
        inAmbient = true
        updateAmbientText()
        content.animate().alpha(0f).setDuration(1200).start()
        glow.animate().alpha(0f).setDuration(1200).start()
        ambient.translationY = Ui.pxf(20)
        ambient.animate().alpha(1f).translationY(0f).setStartDelay(300).setDuration(1400).start()
    }

    private fun exitAmbient(animate: Boolean) {
        inAmbient = false
        val d = if (animate) 500L else 0L
        ambient.animate().alpha(0f).setStartDelay(0).setDuration(d).start()
        content.animate().alpha(1f).setDuration(d).start()
        glow.animate().alpha(1f).setDuration(d).start()
        poke()
    }

    private fun updateAmbientText() {
        if (!::ambSub.isInitialized) return
        val parts = ArrayList<String>()
        parts.add(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")))
        if (Prefs.weather(this)) Weather.current()?.let { w ->
            w.tempF?.let { parts.add("${Weather.tempText(this, it)} outside") }
            parts.add(Weather.label(w))
        }
        parts.addAll(Edition.ambientExtras())
        NowPlaying.current?.takeIf { it.playing }?.let { parts.add("♫ ${it.title} — ${it.artist}") }
        ambSub.text = parts.joinToString("   ·   ")
    }

    fun toast(msg: String) {
        toastTv.text = msg
        toastTv.animate().cancel()
        toastTv.translationY = Ui.pxf(30)
        toastTv.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(2f)).start()
        handler.removeCallbacks(hideToast); handler.postDelayed(hideToast, 2800)
    }
    private val hideToast = Runnable { toastTv.animate().alpha(0f).translationY(Ui.pxf(20)).setDuration(300).start() }

    // ---------------------------------------------------------------- debug hooks (adb broadcasts)

    private fun prefSeason(): SeasonalView.Theme? = getSharedPreferences("debug", MODE_PRIVATE).getString("season", null)
        ?.let { runCatching { SeasonalView.Theme.valueOf(it.uppercase()) }.getOrNull() }

    fun applySeasonOverride() = applySeasonal()
}
