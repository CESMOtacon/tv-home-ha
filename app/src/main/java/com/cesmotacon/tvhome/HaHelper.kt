package com.cesmotacon.tvhome

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

/**
 * The Home Assistant parts of TV Home's background helper (works over any app):
 *  - the doorbell pop-up when someone rings, and a quieter preview when a vehicle is detected
 *  - optionally keeping wireless debugging on (see [AdbKeeper])
 */
object HaHelper {
    private const val TAG = "TvHome.HaHelper"
    private var popup: DoorbellPopup? = null
    private var lastRing: String? = null
    private var lastVehicle: String? = null
    private var lastVehiclePopup = 0L

    private val listener: (String) -> Unit = { id ->
        val d = HaConfig.door
        if (d != null) {
            if (id == d.ring) {
                val st = Ha.state(id)
                if (st == "on" && lastRing != "on") popup?.show(ringing = true)   // a ring always wins
                lastRing = st
            }
            if (id == d.vehicle) {
                val st = Ha.state(id)
                if (st == "on" && lastVehicle == "off") showVehicle()   // off->on only, so reloads (unavailable->on) don't count
                lastVehicle = st
            }
        }
    }

    fun connected(svc: HomeKeyService) {
        popup = DoorbellPopup(svc)
        Ha.start(svc)
        StationWeather.attach()
        Ha.addListener(listener)
        lastRing = HaConfig.door?.ring?.let { Ha.state(it) }
        lastVehicle = HaConfig.door?.vehicle?.let { Ha.state(it) }
        AdbKeeper.start(svc)
    }

    fun destroyed(svc: HomeKeyService) {
        Ha.removeListener(listener)
        popup?.hide(); popup = null
        AdbKeeper.stop(svc)
    }

    fun onKey(event: KeyEvent): Boolean = popup?.let { it.isShowing && it.onKey(event) } ?: false

    fun showDoorbell(ringing: Boolean) { popup?.show(ringing) ?: Log.w(TAG, "helper not running") }

    /** Vehicle preview: skipped while the quiet switch is on, at most every few minutes, never over a doorbell ring. */
    fun showVehicle(force: Boolean = false) {
        val d = HaConfig.door ?: return
        val p = popup ?: return
        val now = System.currentTimeMillis()
        if (!force && (Ha.isOn(d.vehicleQuiet) || now - lastVehiclePopup < d.vehicleCooldownMs)) return
        if (p.isShowing && p.kind == "ring") return
        lastVehiclePopup = now
        p.show(ringing = false, vehicle = true)
    }
}

/**
 * Keeps wireless debugging on (Android turns it off whenever Wi-Fi drops). Off unless ha.json says
 * "keep_wireless_debugging_on": true AND WRITE_SECURE_SETTINGS was granted over adb. Handy for tinkering;
 * leave it off otherwise.
 */
object AdbKeeper {
    private var cb: ConnectivityManager.NetworkCallback? = null

    fun start(svc: HomeKeyService) {
        if (!HaConfig.keepWirelessDebugging) return
        ensure(svc)
        val cm = svc.getSystemService(ConnectivityManager::class.java)
        cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) svc.main.postDelayed({ ensure(svc) }, 5_000)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb!!) }
    }

    fun stop(svc: HomeKeyService) {
        cb?.let { runCatching { svc.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        cb = null
    }

    fun ensure(ctx: Context) {
        if (!HaConfig.keepWirelessDebugging) return
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val cr = ctx.contentResolver
        try {
            if (Settings.Global.getInt(cr, "adb_enabled", 0) == 1 && Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 0) {
                Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
                Log.i("TvHome.Adb", "wireless debugging switched back on")
            }
        } catch (e: Exception) { Log.w("TvHome.Adb", "couldn't enable wireless debugging: ${e.message}") }
    }
}
