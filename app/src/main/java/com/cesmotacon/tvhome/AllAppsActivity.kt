package com.cesmotacon.tvhome

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ScrollView

/** Every installed TV app, plus a way into Google's own home screen (search, watchlist). */
class AllAppsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val root = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0E1120.toInt(), 0xFF120E24.toInt(), 0xFF05060B.toInt()))
        }
        root.addView(Ui.text(this, 52, Ui.INK, Ui.LIGHT).apply { text = "All apps" }, Ui.frameLp(Ui.WRAP, Ui.WRAP, Ui.px(96), Ui.px(60)))
        root.addView(Ui.text(this, 20, Ui.MUTED).apply { text = "Hold OK on an app to add it to your Apps row, or take it off" },
            Ui.frameLp(Ui.WRAP, Ui.WRAP, Ui.px(98), Ui.px(132)))

        val cols = 6
        val w = Ui.px(240); val h = Ui.px(135); val gap = Ui.px(26)
        val grid = GridLayout(this).apply { columnCount = cols; clipChildren = false; clipToPadding = false; setPadding(Ui.px(96), Ui.px(24), Ui.px(96), Ui.px(80)) }
        fun add(t: TileView) {
            grid.addView(t, GridLayout.LayoutParams().apply { width = w; height = h; rightMargin = gap; bottomMargin = gap })
        }

        // Google TV's own home screen, for search and the watchlist. The Home button won't bounce it for 10 minutes.
        if (runCatching { packageManager.getApplicationInfo(Config.STOCK_HOME_PKG, 0).enabled }.getOrDefault(false)) {
            val g = TileView(this, w, h, Ui.pxf(22), 0xFF1F2A44.toInt())
            g.addView(Ui.text(this, 26, Color.WHITE, Ui.MED).apply { text = "Google TV"; gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
            g.addView(Ui.text(this, 16, Ui.MUTED).apply { text = "search · watchlist"; gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).apply { bottomMargin = Ui.px(16) })
            g.onClick = {
                HomeKeyService.allowStockUntil = System.currentTimeMillis() + 10 * 60_000L
                runCatching { startActivity(Apps.stockHomeIntent()) }
            }
            add(g)
        }
        for (e in Apps.all(this)) add(Apps.tile(this, e, w, h).apply {
            onLongClick = {
                val fav = Apps.isFavorite(this@AllAppsActivity, e.pkg)
                Ui.menu(this@AllAppsActivity, e.label, listOf(
                    (if (fav) "Remove from Apps row" else "Add to Apps row") to {
                        if (fav) Apps.removeFavorite(this@AllAppsActivity, e.pkg) else Apps.addFavorite(this@AllAppsActivity, e.pkg)
                    },
                    "Open" to { Apps.launch(this@AllAppsActivity, e) },
                ))
            }
        })

        val scroll = ScrollView(this).apply { clipChildren = false; clipToPadding = false; isVerticalScrollBarEnabled = false; isSmoothScrollingEnabled = true }
        scroll.addView(grid)
        root.addView(scroll, Ui.frameLp(Ui.MATCH, Ui.MATCH, 0, Ui.px(172)))
        setContentView(root)
        Sounds.attach(this)
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Sounds.key(this, event)
        return super.dispatchKeyEvent(event)
    }
}
