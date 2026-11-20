package com.cesmotacon.tvhome

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The Home Assistant edition's settings: where Home Assistant is, the access token, and which entities to use.
 * Nothing is hard-coded to one house. It lives in the app's private storage as ha.json; send it from a computer with
 *   adb shell content write --uri content://com.cesmotacon.tvhome.setup/ha.json < ha.json
 * See docs/ha.example.json for every option.
 */
object HaConfig {
    private const val TAG = "TvHome.HaConfig"

    /** A tile in the Lights or Cameras group. [action]: "show" (start/stop), "lights_back", "all_lights", "doorbell" or "all_cameras". */
    class Tile(val name: String, val icon: String, val action: String, val start: String?, val stop: String?,
               val running: String?, val colors: IntArray, val alsoStop: List<String> = emptyList())

    class Door(val ring: String?, val vehicle: String?, val vehicleQuiet: String?, val vehicleCooldownMs: Long,
               val camera: String?, val cameraHd: String?, val snapshot: String?,
               val tts: String?, val speaker: String?, val replies: List<Pair<String, String>>)

    /** Weather-station entities; any may be missing. Units are read from each sensor. */
    class Station(val temperature: String?, val forecast: String?, val solarRadiation: String?, val solarLux: String?,
                  val rainRate: String?, val raining: String?, val wind: String?, val gust: String?, val windDirection: String?,
                  val humidity: String?, val dewPoint: String?) {
        val ids get() = listOfNotNull(temperature, forecast, solarRadiation, solarLux, rainRate, raining, wind, gust, windDirection, humidity, dewPoint)
    }

    class Pill(val entity: String, val label: String)

    @Volatile var url: String? = null; private set
    @Volatile var token: String? = null; private set
    @Volatile var lightsTitle = "Lights"; private set
    @Volatile var lights: List<Tile> = emptyList(); private set
    @Volatile var camerasTitle = "Cameras"; private set
    @Volatile var cameraTiles: List<Tile> = emptyList(); private set
    /** The All lights screen: a dashboard to mirror (its URL path), lights to leave off, floors (IDs) to show first. */
    @Volatile var lightsDashboard: String? = null; private set
    @Volatile var lightsHide: Set<String> = emptySet(); private set
    @Volatile var floorOrder: List<String> = emptyList(); private set
    @Volatile var door: Door? = null; private set
    @Volatile var station: Station? = null; private set
    @Volatile var pills: List<Pill> = emptyList(); private set
    @Volatile var notMusic: Set<String> = emptySet(); private set
    @Volatile var keepWirelessDebugging = false; private set
    @Volatile var error: String? = null; private set
    lateinit var app: Context; private set
    private var loaded = false

    val configured get() = url != null && token != null

    fun file(ctx: Context) = File(ctx.filesDir, "ha.json")

    fun load(ctx: Context, force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        app = ctx.applicationContext
        val f = file(app)
        val j = runCatching { JSONObject(f.readText()) }.onFailure { if (f.exists()) error = "ha.json isn't valid JSON: ${it.message}" }.getOrNull() ?: JSONObject()
        if (f.exists() && j.length() > 0) error = null
        fun JSONObject.s(k: String) = optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }

        url = j.s("url")?.trimEnd('/')
        // the token can be in ha.json, or on its own in files/ha_token (older setups)
        token = j.s("token") ?: runCatching { File(app.filesDir, "ha_token").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length > 20 } }.getOrNull()

        val l = j.optJSONObject("lights")
        lightsTitle = l?.s("title") ?: "Lights"
        lights = tiles(l?.optJSONArray("tiles"))
        val c = j.optJSONObject("cameras")
        camerasTitle = c?.s("title") ?: "Cameras"
        cameraTiles = tiles(c?.optJSONArray("tiles"))
        val al = j.optJSONObject("all_lights")
        fun strings(a: JSONArray?) = a?.let { x -> (0 until x.length()).mapNotNull { x.optString(it).trim().takeIf { s -> s.isNotEmpty() } } } ?: emptyList()
        lightsDashboard = al?.s("dashboard")?.trim('/')
        lightsHide = strings(al?.optJSONArray("hide")).toSet()
        floorOrder = strings(al?.optJSONArray("floor_order"))

        door = j.optJSONObject("doorbell")?.let { d ->
            val replies = ArrayList<Pair<String, String>>()
            d.optJSONArray("replies")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.opt(i)
                    when (r) {
                        is JSONArray -> if (r.length() >= 2) replies.add(r.getString(0) to r.getString(1))
                        is JSONObject -> replies.add((r.s("button") ?: r.s("say") ?: "") to (r.s("say") ?: ""))
                    }
                }
            }
            val speak = d.optJSONObject("speak")
            Door(d.s("ring"), d.s("vehicle"), d.s("vehicle_quiet_when"), (d.optDouble("vehicle_cooldown_minutes", 2.0) * 60_000).toLong(),
                d.s("camera"), d.s("camera_hd"), d.s("snapshot"), speak?.s("tts"), speak?.s("speaker"), replies.filter { it.first.isNotBlank() && it.second.isNotBlank() })
        }

        station = j.optJSONObject("weather")?.let { w ->
            Station(w.s("temperature"), w.s("forecast"), w.s("solar_radiation"), w.s("solar_lux"), w.s("rain_rate"), w.s("raining"),
                w.s("wind_speed"), w.s("wind_gust"), w.s("wind_direction"), w.s("humidity"), w.s("dew_point"))
        }?.takeIf { it.ids.isNotEmpty() }

        pills = j.optJSONArray("pills")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { p -> p.s("entity")?.let { Pill(it, p.s("label") ?: "") } }
        } ?: emptyList()
        notMusic = j.optJSONArray("not_music")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        keepWirelessDebugging = j.optBoolean("keep_wireless_debugging_on", false)
        Log.i(TAG, "loaded: url=${url != null} token=${token != null} lights=${lights.size} cameras=${cameraTiles.size} doorbell=${door != null} station=${station != null}")
    }

    private fun tiles(a: JSONArray?): List<Tile> {
        a ?: return emptyList()
        val out = ArrayList<Tile>()
        for (i in 0 until a.length()) {
            val t = a.optJSONObject(i) ?: continue
            fun s(k: String) = t.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" }
            val cols = t.optJSONArray("colors")?.let { c -> (0 until c.length()).mapNotNull { k -> parseColor(c.optString(k)) }.toIntArray() }
                ?: intArrayOf(0xF4B24E, 0xFFD27A, 0xC8501A)
            val also = t.optJSONArray("also_stop")?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
            out.add(Tile(s("name") ?: "?", s("icon") ?: "●", s("action") ?: "show", s("start"), s("stop"), s("running"), cols, also))
        }
        return out
    }

    private fun parseColor(s: String): Int? = runCatching { s.trim().removePrefix("#").toLong(16).toInt() and 0xFFFFFF }.getOrNull()

    /** Everything to subscribe to. */
    fun watch(): List<String> = (listOf("sun.sun", "zone.home") +
        (lights + cameraTiles).mapNotNull { it.running } + (lights + cameraTiles).flatMap { it.alsoStop } +
        listOfNotNull(door?.ring, door?.vehicle, door?.vehicleQuiet) +
        (station?.ids ?: emptyList()) + pills.map { it.entity }).distinct()

    fun summary(): String = buildString {
        append(if (configured) "Configured" else if (url == null) "No Home Assistant address yet" else "No access token yet")
        error?.let { append(" · $it") }
        append(" · ${Ha.status}")
        append(" · lights ${lights.size}, cameras ${cameraTiles.size}, doorbell ${if (door != null) "yes" else "no"}, weather station ${if (station != null) "yes" else "no"}")
    }
}

/**
 * Setup over adb (only the adb shell and the system can use it: it requires the DUMP permission).
 *   adb shell content write --uri content://com.cesmotacon.tvhome.setup/ha.json < ha.json     (save the settings, reconnect)
 *   adb shell content read  --uri content://com.cesmotacon.tvhome.setup/status                 (what TV Home thinks of them)
 */
class SetupProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val ctx = context ?: return null
        return when (uri.lastPathSegment) {
            "ha.json" -> {
                if (!mode.contains('w')) return ParcelFileDescriptor.open(HaConfig.file(ctx), ParcelFileDescriptor.MODE_READ_ONLY)
                val tmp = File(ctx.filesDir, "ha.json.incoming")
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
                    Handler(Looper.getMainLooper())) { e ->
                    if (e != null) { tmp.delete(); return@open }
                    val ok = runCatching { JSONObject(tmp.readText()) }.isSuccess
                    if (ok) {
                        tmp.renameTo(HaConfig.file(ctx))
                        HaConfig.load(ctx, force = true)
                        Ha.start(ctx); Ha.reload()
                        MainActivity.instance?.let { it.recreate() }
                        Log.i("TvHome.Setup", "new ha.json saved")
                    } else { tmp.delete(); Log.w("TvHome.Setup", "ha.json rejected: not valid JSON") }
                }
            }
            "status" -> {
                val (r, w) = ParcelFileDescriptor.createPipe()
                Thread { ParcelFileDescriptor.AutoCloseOutputStream(w).use { it.write((HaConfig.summary() + "\n").toByteArray()) } }.start()
                r
            }
            else -> null
        }
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/json"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
