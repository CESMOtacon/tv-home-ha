package com.cesmotacon.tvhome

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Everything the clock, the Live weather sky and the falling leaves need to know about the weather right now. */
data class Wx(
    val elev: Float, val az: Float,            // sun, degrees
    val cover: Float,                           // 0 clear .. 1 overcast
    val sunOut: Float,                          // 0 sun hidden .. 1 sun shining
    val rain: Float, val rainRate: Float,       // intensity 0..1, inches per hour
    val snow: Float,                            // intensity 0..1
    val wind: Float, val gust: Float, val windFrom: Float,   // mph, mph, degrees (where it blows from)
    val fog: Float, val lightning: Boolean,
    val moonAlt: Float?, val moonAz: Float?,
    val tempF: Float?,
)

/** Where the weather comes from. This edition uses Open-Meteo; others can plug in their own (see [Edition]). */
interface WeatherSource {
    /** Short credit for the caption, e.g. "Open-Meteo". */
    val credit: String
    /** True when gust readings arrive as they happen (a weather station); false means gusts are imagined from the forecast. */
    val liveGusts: Boolean
    /** Fetch or refresh if stale. Cheap to call often; never blocks. */
    fun refresh(ctx: Context)
    /** Forget the cached weather (the place changed). */
    fun invalidate() {}
    /** If the source decides the location itself (not the viewer), what to say instead of offering to change it. */
    val placeNote: String? get() = null
    /** The latest weather, or null if nothing is known yet. */
    fun now(): Wx?
}

object Weather {
    private const val TAG = "TvHome.Weather"
    val source: WeatherSource get() = Edition.weather()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    /** Sources call this whenever something changed. */
    fun changed() { main.post { listeners.forEach { runCatching { it() } } } }

    /** adb test hook: show this instead of the real weather until [overrideUntil] (elapsedRealtime). */
    @Volatile var override: Wx? = null
    @Volatile var overrideUntil = 0L
    @Volatile private var cached: Wx? = null
    @Volatile private var cachedAt = 0L

    fun current(): Wx? {
        val now = SystemClock.elapsedRealtime()
        override?.let { if (now < overrideUntil) return it else override = null }
        cached?.let { if (now - cachedAt < 1000) return it }
        return runCatching { source.now() }.onFailure { Log.w(TAG, "weather source failed", it) }.getOrNull()
            ?.also { cached = it; cachedAt = now }
    }

    /** For the Live weather background before any weather has arrived: the right sun and moon, fair skies. */
    fun currentOrFair(): Wx = current() ?: fair()

    fun fair(): Wx {
        val ms = System.currentTimeMillis()
        val p = Place.current
        val (e, a) = if (p != null) Astro.sun(p.lat, p.lon, ms) else (35f to 180f)
        val (ma, mz) = moon(ms)
        return Wx(e, a, .25f, 1f, 0f, 0f, 0f, 3f, 5f, 270f, 0f, false, ma, mz, null)
    }

    /** Where the moon is now (NASA's position for this hour, seen from [Place]), or nulls. */
    fun moon(ms: Long): Pair<Float?, Float?> {
        val m = SkyAssets.moon ?: return null to null
        val p = Place.current ?: return null to null
        val (a, z) = Astro.altAz(m.ra, m.dec, p.lat, p.lon, ms)
        return a to z
    }

    fun useFahrenheit(ctx: Context) = when (Prefs.units(ctx)) {
        "f" -> true
        "c" -> false
        else -> Locale.getDefault().country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")
    }

    fun tempText(ctx: Context, tempF: Float): String =
        if (useFahrenheit(ctx)) "${tempF.roundToInt()}°" else "${((tempF - 32f) * 5f / 9f).roundToInt()}°"

    fun label(w: Wx): String = when {
        w.lightning -> "Thunderstorms"
        w.snow > 0f -> "Snow"
        w.rain > 0f -> when { w.rainRate < 0.01f -> "Drizzle"; w.rainRate < 0.1f -> "Light rain"; w.rainRate < 0.3f -> "Rain"; else -> "Heavy rain" }
        w.fog > 0f -> "Fog"
        w.cover < 0.15f -> if (w.elev > 0) "Sunny" else "Clear"
        w.cover < 0.4f -> if (w.elev > 0) "Mostly sunny" else "Mostly clear"
        w.cover < 0.7f -> "Partly cloudy"
        w.cover < 0.88f -> "Mostly cloudy"
        else -> "Overcast"
    }

    fun emoji(w: Wx): String {
        val night = w.elev < -3f
        return when {
            w.lightning -> "⛈️"
            w.snow > 0f -> "🌨️"
            w.rain > 0f -> "🌧️"
            w.fog > 0f -> "🌫️"
            w.cover < 0.25f -> if (night) "🌙" else "☀️"
            w.cover < 0.7f -> if (night) "☁️" else "⛅"
            else -> "☁️"
        }
    }

    private val COMPASS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a")

    /** The line under the home screen while Live weather is the background. */
    fun caption(ctx: Context, w: Wx): String {
        val metric = !useFahrenheit(ctx)
        val parts = arrayListOf("Live weather", label(w))
        if (w.rain > 0f && w.rainRate > 0f) parts.add(if (metric) "%.1f mm/h".format(w.rainRate * 25.4f) else "%.2f in/h".format(w.rainRate))
        if (w.wind >= 1f) {
            val dir = COMPASS[((w.windFrom % 360 + 360) % 360 / 22.5f).roundToInt() % 16]
            parts.add(if (metric) "${(w.wind * 1.609f).roundToInt()} km/h $dir wind" else "${w.wind.roundToInt()} mph $dir wind")
        }
        parts.add(source.credit)
        val day = w.elev > -4f
        if (day) SkyAssets.sun?.let { parts.add("Sun: NASA SDO, " + Instant.ofEpochMilli(it.updatedMs).atZone(ZoneId.systemDefault()).format(CLOCK)) }
        if ((w.moonAlt ?: -90f) > 0f && (!day || w.cover < 0.6f)) SkyAssets.moon?.let { parts.add("Moon: NASA, ${it.phase.roundToInt()}% lit") }
        return parts.joinToString("  ·  ")
    }

    fun describe(w: Wx) = "elev=%.1f az=%.0f cover=%.2f sunOut=%.2f rain=%.2f (%.3f in/h) snow=%.2f wind=%.1f/%.1f from %.0f fog=%.1f bolt=%s moon=%s temp=%s".format(
        w.elev, w.az, w.cover, w.sunOut, w.rain, w.rainRate, w.snow, w.wind, w.gust, w.windFrom, w.fog, w.lightning,
        w.moonAlt?.let { "%.0f/%.0f".format(it, w.moonAz ?: 0f) } ?: "-", w.tempF?.let { "%.1fF".format(it) } ?: "-")

    /** Rain intensity 0..1 from a rate in inches per hour (drizzle still shows a little). */
    fun rainIntensity(inPerHour: Float, raining: Boolean): Float =
        if (!raining && inPerHour <= 0f) 0f else (0.12f + 0.36f * log10(1f + inPerHour.coerceAtLeast(0f) / 0.01f)).coerceIn(0.12f, 1f)
}

/** Where the weather and the sky are for: set by hand over adb, by an edition, or looked up once from the internet address. */
object Place {
    private const val TAG = "TvHome.Place"
    data class P(val lat: Double, val lon: Double, val name: String, val source: String, val at: Long)

    @Volatile var current: P? = null
        private set
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        current = runCatching {
            val j = JSONObject(prefs(ctx).getString("place", null)!!)
            P(j.getDouble("lat"), j.getDouble("lon"), j.optString("name"), j.optString("source", "ip"), j.optLong("at"))
        }.getOrNull()
    }

    fun set(ctx: Context, p: P) {
        current = p; loaded = true
        prefs(ctx).edit().putString("place", JSONObject().put("lat", p.lat).put("lon", p.lon).put("name", p.name)
            .put("source", p.source).put("at", p.at).toString()).apply()
        Weather.source.invalidate()
    }

    fun clear(ctx: Context) {
        current = null; loaded = true
        prefs(ctx).edit().remove("place").apply()
        Weather.source.invalidate()
    }

    /**
     * Blocking; call off the main thread. If nothing was set by hand, looks the approximate place up once from the
     * internet address (city level, refreshed weekly) using ipapi.co, falling back to ipwho.is.
     */
    fun ensureBlocking(ctx: Context): P? {
        load(ctx)
        val p = current
        if (p != null && (p.source != "ip" || System.currentTimeMillis() - p.at < 7 * 86_400_000L)) return p
        val now = System.currentTimeMillis()
        fun name(vararg s: String) = s.filter { it.isNotBlank() }.joinToString(", ")
        val got = runCatching {
            val j = JSONObject(Net.get("https://ipapi.co/json/"))
            P(j.getDouble("latitude"), j.getDouble("longitude"), name(j.optString("city"), j.optString("region")), "ip", now)
        }.onFailure { Log.w(TAG, "ipapi.co: ${it.message}") }.getOrNull() ?: runCatching {
            val j = JSONObject(Net.get("https://ipwho.is/"))
            if (!j.optBoolean("success", true)) null
            else P(j.getDouble("latitude"), j.getDouble("longitude"), name(j.optString("city"), j.optString("region")), "ip", now)
        }.onFailure { Log.w(TAG, "ipwho.is: ${it.message}") }.getOrNull()
        if (got != null) set(ctx, got)
        return got ?: p
    }
}

/** Finds a place by postal code or town name (Open-Meteo's free geocoding; no account). */
object Geo {
    class Hit(val label: String, val name: String, val lat: Double, val lon: Double)

    /** Blocking; call off the main thread. Postal codes match in several countries, so this TV's country comes first. */
    fun search(query: String): List<Hit> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val lang = Locale.getDefault().language.ifBlank { "en" }
        val country = Locale.getDefault().country
        val base = "https://geocoding-api.open-meteo.com/v1/search?name=${java.net.URLEncoder.encode(q, "UTF-8")}&count=10&language=$lang&format=json"
        val postal = q.any { it.isDigit() } && q.length <= 10
        val local = if (postal && country.length == 2) runCatching { parse(Net.get("$base&countryCode=$country")) }.getOrDefault(emptyList()) else emptyList()
        if (local.isNotEmpty()) return local
        return parse(Net.get(base)).sortedBy { if (it.label.endsWith(Locale("", country).displayCountry)) 0 else 1 }
    }

    private fun parse(json: String): List<Hit> {
        val a = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { r ->
            val name = r.optString("name")
            val region = r.optString("admin1").takeIf { it.isNotBlank() && it != name }
            val countryName = r.optString("country")
            Hit(listOfNotNull(name, region, countryName.takeIf { it.isNotBlank() }).joinToString(", "),
                listOfNotNull(name, region).joinToString(", "), r.getDouble("latitude"), r.getDouble("longitude"))
        }.distinctBy { it.label }
    }
}

/** Sun and moon positions. */
object Astro {
    /** Altitude and azimuth (degrees; azimuth from north through east) of a body at [raHours]/[decDeg]. */
    fun altAz(raHours: Double, decDeg: Double, latDeg: Double, lonDeg: Double, ms: Long): Pair<Float, Float> {
        val d = ms / 86_400_000.0 + 2440587.5 - 2451545.0
        val gmst = ((280.46061837 + 360.98564736629 * d) % 360 + 360) % 360
        val h = Math.toRadians(gmst + lonDeg - raHours * 15)
        val lat = Math.toRadians(latDeg); val dec = Math.toRadians(decDeg)
        val alt = asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(h))
        val az = atan2(-cos(dec) * sin(h), sin(dec) * cos(lat) - cos(dec) * sin(lat) * cos(h))
        return Math.toDegrees(alt).toFloat() to ((Math.toDegrees(az) + 360) % 360).toFloat()
    }

    /** The sun's altitude and azimuth (low-precision almanac formula; within a few hundredths of a degree). */
    fun sun(latDeg: Double, lonDeg: Double, ms: Long): Pair<Float, Float> {
        val d = ms / 86_400_000.0 + 2440587.5 - 2451545.0
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = Math.toDegrees(atan2(cos(e) * sin(l), cos(l)))
        val dec = Math.toDegrees(asin(sin(e) * sin(l)))
        return altAz(((ra + 360) % 360) / 15.0, dec, latDeg, lonDeg, ms)
    }
}

/**
 * Current conditions from Open-Meteo (free, no account; open-meteo.com), refreshed every 15 minutes while TV Home is
 * on screen. Weather data by Open-Meteo.com (CC BY 4.0).
 */
object OpenMeteo : WeatherSource {
    private const val TAG = "TvHome.OpenMeteo"
    override val credit = "Open-Meteo"
    override val liveGusts = false

    private class Cur(val tempF: Float, val cover: Float, val rate: Float, val snowfall: Float, val code: Int,
                      val wind: Float, val gust: Float, val dir: Float)

    @Volatile private var cur: Cur? = null
    @Volatile private var fetched = 0L
    @Volatile private var tried = 0L
    private val busy = AtomicBoolean(false)

    override fun invalidate() { fetched = 0L; tried = 0L }

    override fun refresh(ctx: Context) {
        if (!Prefs.weather(ctx)) return
        val now = System.currentTimeMillis()
        if (now - fetched < 15 * 60_000L || now - tried < 2 * 60_000L) return
        if (!busy.compareAndSet(false, true)) return
        tried = now
        val app = ctx.applicationContext
        Net.io.execute {
            try {
                val p = Place.ensureBlocking(app) ?: return@execute
                val url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,precipitation,rain,showers,snowfall,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                    "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch&timezone=auto", p.lat, p.lon)
                val j = JSONObject(Net.get(url)).getJSONObject("current")
                val perHour = 3600f / j.optInt("interval", 900).coerceAtLeast(60)
                cur = Cur(
                    tempF = j.optDouble("temperature_2m", Double.NaN).toFloat(),
                    cover = (j.optDouble("cloud_cover", 50.0) / 100.0).toFloat().coerceIn(0f, 1f),
                    rate = ((j.optDouble("rain", 0.0) + j.optDouble("showers", 0.0)) * perHour).toFloat(),
                    snowfall = j.optDouble("snowfall", 0.0).toFloat(),
                    code = j.optInt("weather_code", 0),
                    wind = j.optDouble("wind_speed_10m", 0.0).toFloat(),
                    gust = j.optDouble("wind_gusts_10m", 0.0).toFloat(),
                    dir = j.optDouble("wind_direction_10m", 270.0).toFloat(),
                )
                fetched = System.currentTimeMillis()
                Weather.changed()
            } catch (e: Exception) {
                Log.w(TAG, "weather: ${e.message}")
            } finally { busy.set(false) }
        }
    }

    override fun now(): Wx? {
        val c = cur ?: return null
        val p = Place.current ?: return null
        val ms = System.currentTimeMillis()
        val (e, a) = Astro.sun(p.lat, p.lon, ms)
        // WMO weather codes: 45/48 fog, 51-67 drizzle/rain (66/67 freezing), 71-77 snow, 80-82 showers, 85/86 snow showers, 95-99 thunder
        val code = c.code
        val thunder = code in 95..99
        val snowing = c.snowfall > 0f || code in 71..77 || code in 85..86
        val wetCode = code in 51..67 || code in 80..82 || thunder
        var rain = if (snowing && code !in 66..67) 0f else Weather.rainIntensity(c.rate, wetCode)
        if (rain > 0f && c.rate <= 0f && !wetCode) rain = 0f
        val snow = if (snowing) (0.35f + c.snowfall * 4f).coerceIn(0.35f, 1f) else 0f
        var cover = c.cover
        if (rain > 0f || snow > 0f) cover = max(cover, 0.85f + 0.15f * max(rain, snow))
        val sunOut = ((0.85f - cover) / 0.6f).coerceIn(0f, 1f)
        val (ma, mz) = Weather.moon(ms)
        return Wx(e, a, cover, sunOut, rain, c.rate, snow, c.wind, c.gust, c.dir,
            if (code == 45 || code == 48) 0.8f else 0f, thunder, ma, mz, c.tempF.takeUnless { it.isNaN() })
    }
}

/**
 * Real wind for falling things (seasonal leaves and snow, live-sky rain and snow): a steady drift from the current wind,
 * plus a gust that sweeps across the screen from the upwind side. A weather station reports gusts as they happen
 * ([gustReading]); with a forecast the gusts are imagined, as often and as strong as its gust speed suggests.
 */
object WindFx {
    /** -1 = drifting left (wind from the west; the sky looks south) .. +1 = drifting right. */
    var dirX = 0f
        private set
    var mph = 0f
        private set
    /** Sideways sign for gust pushes, never zero (a north or south wind still shoves things one way). */
    val pushDir get() = if (dirX < 0f) -1f else 1f
    private var gustAt = -100f
    private var gustK = 0f
    private var checked = -1f
    private var nextImagined = 0f
    private val rnd = Random(System.nanoTime())

    fun now() = SystemClock.uptimeMillis() / 1000f

    /** A weather station's new gust reading. */
    fun gustReading(gustMph: Float) {
        val extra = gustMph - mph
        if (extra >= 2f) { gustAt = now(); gustK = (extra / 10f).coerceIn(.25f, 1.5f) }
    }

    fun tick(t: Float) {
        if (t - checked < .5f) return
        checked = t
        val w = Weather.current()
        if (w == null) { mph = 0f; return }
        mph = w.wind
        dirX = sin(Math.toRadians(w.windFrom.toDouble())).toFloat()
        if (!Weather.source.liveGusts && t >= nextImagined) {
            val extra = w.gust - w.wind
            if (extra >= 3f && nextImagined > 0f) { gustAt = t; gustK = (extra / 10f).coerceIn(.25f, 1.5f) * (0.6f + 0.4f * rnd.nextFloat()) }
            nextImagined = t + 6f + rnd.nextFloat() * 16f
        }
    }

    /** Gust strength (0 .. ~1.5) felt at [xFrac] across the screen (0 = left edge) at time [t]. */
    fun pulse(t: Float, xFrac: Float): Float {
        val from = if (dirX < 0f) 1f - xFrac else xFrac
        val p = t - gustAt - from.coerceIn(0f, 1f) * 1.2f
        if (p < 0f || p > 7f) return 0f
        return gustK * (p / .5f).coerceAtMost(1f) * exp(-(p - .5f).coerceAtLeast(0f) / 1.4f)
    }
}
