package com.cesmotacon.tvhome

import android.content.Context
import android.os.SystemClock
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * Weather from Home Assistant: a local weather station where configured, plus HA's weather entity (forecast) for
 * what a station can't measure (snow, thunder, cloud at night). Units are read from each sensor.
 *
 * Clouds by day come from the solar sensor: measured sunlight vs. what a clear sky would give at this sun height.
 * A quick average says whether the sun is out right now; a 15-minute average says how cloudy it is overall.
 */
object StationWeather : WeatherSource {
    override val credit get() = if (HaConfig.station?.let { it.solarRadiation ?: it.solarLux ?: it.wind ?: it.rainRate } != null) "your weather station" else "Home Assistant"
    override val liveGusts = true

    // solar-sensor averages, as a fraction of "full sun" (1 = full sun)
    @Volatile private var fast = -1f
    @Volatile private var slow = -1f
    private var lastSample = 0L
    private var attached = false

    /** Start following the sensors. The helper service calls this too, so the averages are warm before TV Home opens. */
    fun attach() {
        if (attached) return
        attached = true
        Ha.addListener { id ->
            val st = HaConfig.station ?: return@addListener
            when (id) {
                st.solarRadiation, st.solarLux -> sample()
                st.gust -> gust()?.let { WindFx.gustReading(it) }
                st.temperature, st.forecast, st.raining, st.rainRate -> Weather.changed()
                "zone.home" -> placeFromHome()
            }
        }
        placeFromHome()
        sample()
    }

    override fun refresh(ctx: Context) { Ha.start(ctx); attach() }   // Home Assistant pushes changes; nothing to fetch

    // ---------------------------------------------------------------- reading entities, with units

    private fun num(id: String?): Float? = id?.let { Ha.state(it)?.toFloatOrNull() }
    private fun unit(id: String?): String = id?.let { Ha.states[it]?.attrs?.optString("unit_of_measurement", "") } ?: ""
    private fun attr(id: String?, k: String): Double? = id?.let { Ha.states[it]?.attrs?.optDouble(k, Double.NaN)?.takeUnless { v -> v.isNaN() } }

    private fun fahrenheit(v: Float, u: String) = if (u.contains("C")) v * 9f / 5f + 32f else v
    private fun mph(v: Float, u: String) = when (u.lowercase()) {
        "km/h", "kph" -> v / 1.609f
        "m/s" -> v * 2.237f
        "kn", "kt", "knots" -> v * 1.151f
        "ft/s" -> v * 0.682f
        else -> v
    }
    private fun inchesPerHour(v: Float, u: String) = if (u.lowercase().startsWith("mm")) v / 25.4f else v

    private fun tempF(): Float? {
        val st = HaConfig.station ?: return null
        num(st.temperature)?.let { return fahrenheit(it, unit(st.temperature)) }
        val t = attr(st.forecast, "temperature")?.toFloat() ?: return null
        val u = st.forecast?.let { Ha.states[it]?.attrs?.optString("temperature_unit", "°F") } ?: "°F"
        return fahrenheit(t, u)
    }
    private fun wind() = HaConfig.station?.wind?.let { id -> num(id)?.let { mph(it, unit(id)) } }
    private fun gust() = HaConfig.station?.gust?.let { id -> num(id)?.let { mph(it, unit(id)) } }
    private fun radiation(): Float? {
        val st = HaConfig.station ?: return null
        num(st.solarRadiation)?.let { return it }
        return num(st.solarLux)?.let { it / 126.7f }   // daylight lux per W/m²
    }

    private fun placeFromHome() {
        val lat = attr("zone.home", "latitude") ?: return
        val lon = attr("zone.home", "longitude") ?: return
        val cur = Place.current
        if (cur == null || cur.source != "home-assistant" || cur.lat != lat || cur.lon != lon)
            Place.set(HaConfig.app, Place.P(lat, lon, "Home", "home-assistant", System.currentTimeMillis()))
    }

    private fun sun(): Pair<Float, Float>? {
        val e = attr("sun.sun", "elevation"); val a = attr("sun.sun", "azimuth")
        if (e != null && a != null) return e.toFloat() to a.toFloat()
        val p = Place.current ?: return null
        return Astro.sun(p.lat, p.lon, System.currentTimeMillis())
    }

    // ---------------------------------------------------------------- clouds from sunlight

    /** Haurwitz clear-sky model, W/m². */
    private fun clearSky(elev: Float): Float {
        val s = sin(Math.toRadians(elev.toDouble())).toFloat()
        return if (s <= 0.02f) 0f else 1098f * s * exp(-0.057f / s)
    }

    /** What "full sun" reads as: lower when the winter sun is low (angle, trees). Tuned on a GW2000/WS90 in Nov 2026. */
    private fun fullSun(elev: Float) = when {
        elev >= 25f -> 0.85f
        elev <= 5f -> 0.55f
        else -> 0.55f + (elev - 5f) / 20f * 0.30f
    }

    private fun sample() {
        val rad = radiation() ?: return
        val e = sun()?.first ?: return
        if (e < 4f) return
        val r = rad / clearSky(e).coerceAtLeast(1f) / fullSun(e)
        val now = SystemClock.elapsedRealtime()
        if (lastSample == 0L || now - lastSample > 3 * 3600_000L) { fast = r; slow = r }   // first reading of the day
        else {
            val dt = (now - lastSample) / 1000f
            fast += (r - fast) * (1 - exp(-dt / 40f))
            slow += (r - slow) * (1 - exp(-dt / 900f))
        }
        lastSample = now
        Weather.changed()
    }

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    override fun now(): Wx? {
        val st = HaConfig.station ?: return null
        if (!Ha.connected && Ha.states.isEmpty()) return null
        val (e, az) = sun() ?: return null
        val cond = st.forecast?.let { Ha.state(it) } ?: ""
        val metCover = attr(st.forecast, "cloud_coverage")?.toFloat()?.div(100f)?.coerceIn(0f, 1f) ?: when (cond) {
            "sunny", "clear-night" -> 0.1f
            "partlycloudy" -> 0.45f
            "cloudy", "fog" -> 0.9f
            "rainy", "pouring", "snowy", "snowy-rainy", "lightning", "lightning-rainy", "hail" -> 0.95f
            else -> 0.5f
        }
        val temp = tempF()
        val rate = st.rainRate?.let { id -> num(id)?.let { inchesPerHour(it, unit(id)) } }?.coerceAtLeast(0f) ?: 0f
        val wet = Ha.isOn(st.raining) || rate > 0f ||
            (st.raining == null && st.rainRate == null && cond in setOf("rainy", "pouring", "lightning-rainy"))
        var rain = Weather.rainIntensity(rate, wet)
        // Rain gauges can't feel snow, so trust the forecast's "snowy" when it's cold enough, or wet + freezing here.
        val metSnow = cond == "snowy" || cond == "snowy-rainy"
        val snowing = (metSnow && (temp == null || temp <= 37f)) || (wet && temp != null && temp <= 33f)
        val snow = if (snowing) max(rain, 0.45f) else 0f
        if (snowing && (cond == "snowy" || (temp != null && temp <= 33f))) rain = 0f

        // Clouds: by day mostly the solar sensor (if there is one), at night the forecast.
        val trustSun = if (radiation() == null) 0f else ((e - 4f) / 6f).coerceIn(0f, 1f)
        val sunCover = if (slow < 0) metCover else 1f - smooth(0.3f, 0.95f, slow)
        var cover = metCover + (0.65f * sunCover + 0.35f * metCover - metCover) * trustSun
        if (rain > 0f || snow > 0f) cover = max(cover, 0.85f + 0.15f * max(rain, snow))
        val sunOut = if (fast < 0 || e < 4f || trustSun == 0f) ((0.85f - cover) / 0.6f).coerceIn(0f, 1f) else smooth(0.35f, 0.75f, fast)

        val wind = wind() ?: attr(st.forecast, "wind_speed")?.toFloat()?.let {
            mph(it, st.forecast?.let { f -> Ha.states[f]?.attrs?.optString("wind_speed_unit", "mph") } ?: "mph")
        } ?: 0f
        val hum = num(st.humidity)
        val dew = st.dewPoint?.let { id -> num(id)?.let { fahrenheit(it, unit(id)) } }
        val fog = when {
            cond == "fog" -> 0.8f
            hum != null && dew != null && temp != null && hum >= 97f && temp - dew <= 1.5f && wind < 6f -> 0.5f
            else -> 0f
        }
        val bolt = (cond == "lightning-rainy" && wet) || cond == "lightning"
        val dir = num(st.windDirection) ?: attr(st.forecast, "wind_bearing")?.toFloat() ?: 270f
        val (ma, mz) = Weather.moon(System.currentTimeMillis())
        return Wx(e, az, cover.coerceIn(0f, 1f), sunOut, rain, rate, snow, wind, gust() ?: wind, dir, fog, bolt, ma, mz, temp)
    }
}
