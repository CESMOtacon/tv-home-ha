package com.cesmotacon.tvhome

import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * The Home Assistant edition. This file is the only shared file that differs from plain TV Home
 * (github.com/CESMOtacon/tv-home); everything it adds lives in the Ha*.kt files, StationWeather.kt,
 * DoorbellPopup.kt and CamerasActivity.kt. All house-specific settings come from ha.json (see docs/ha.example.json).
 */
object Edition {
    const val NAME = "TV Home for Home Assistant"

    fun init(ctx: Context) {
        HaConfig.load(ctx)
        Ha.start(ctx)
        if (HaConfig.station != null) StationWeather.attach()
    }

    /** A weather station (or HA's weather entity) when ha.json has a "weather" section, otherwise Open-Meteo. */
    fun weather(): WeatherSource = if (HaConfig.station != null) StationWeather else OpenMeteo

    fun buildHome(home: MainActivity) = HaHome.build(home)
    fun onHomeResume(home: MainActivity) = HaHome.resume(home)
    fun onHomePause(home: MainActivity) = HaHome.pause(home)

    fun ambientExtras(): List<String> = emptyList()
    fun glowColors(): IntArray? = HaHome.glowColors()
    fun notMusic(): Set<String> = HaConfig.notMusic

    fun onHelperConnected(svc: HomeKeyService) = HaHelper.connected(svc)
    fun onHelperDestroyed(svc: HomeKeyService) = HaHelper.destroyed(svc)
    fun onHelperKey(event: KeyEvent): Boolean = HaHelper.onKey(event)

    /**
     * adb test hooks (all with  -p com.cesmotacon.tvhome ):
     *   RING (doorbell pop-up as if someone rang; the reply buttons really speak), CAM (just the camera),
     *   VEHICLE (the vehicle preview), RELOAD (re-read ha.json and reconnect).
     */
    fun onDebug(ctx: Context, i: Intent): Boolean {
        when (i.action) {
            "com.cesmotacon.tvhome.RING" -> HaHelper.showDoorbell(true)
            "com.cesmotacon.tvhome.CAM" -> HaHelper.showDoorbell(false)
            "com.cesmotacon.tvhome.VEHICLE" -> HaHelper.showVehicle(force = true)
            "com.cesmotacon.tvhome.RELOAD" -> { HaConfig.load(ctx, force = true); Ha.start(ctx); Ha.reload() }
            else -> return false
        }
        return true
    }
}
