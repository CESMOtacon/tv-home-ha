package com.cesmotacon.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Home Assistant over the websocket API: live entity states, service calls, camera stream URLs. */
object Ha {
    private const val TAG = "TvHome.Ha"

    class Ent(val state: String, val attrs: JSONObject, val lastChanged: Double)

    val states = ConcurrentHashMap<String, Ent>()
    @Volatile var connected = false
        private set
    @Volatile var status = "Not started"
        private set

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    val http: OkHttpClient = client.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(String) -> Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(1)
    private var subId = -1
    private var ws: WebSocket? = null
    private var token: String? = null
    private var backoff = 2_000L
    private var started = false
    private lateinit var app: Context

    fun start(ctx: Context) {
        if (started) return
        started = true
        app = ctx.applicationContext
        HaConfig.load(app)
        connect()
    }

    /** Re-read the token (after it was pushed over adb) and reconnect. */
    fun reload() {
        if (!started) return
        main.removeCallbacksAndMessages(null)
        ws?.cancel(); ws = null
        backoff = 2_000L
        connect()
    }

    fun addListener(l: (String) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }
    fun state(id: String): String? = states[id]?.state
    fun isOn(id: String?) = id != null && states[id]?.state == "on"

    private fun emit(id: String) = main.post { listeners.forEach { runCatching { it(id) } } }

    private fun connect() {
        HaConfig.load(app, force = true)
        token = HaConfig.token
        if (HaConfig.url == null || token == null) {
            status = if (HaConfig.url == null) "Not set up yet" else "No token yet"; emit("_status")
            main.postDelayed({ connect() }, 60_000)
            return
        }
        status = "Connecting"
        val req = Request.Builder().url(HaConfig.url!!.replaceFirst("http", "ws") + "/api/websocket").build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== ws) return
                try { handle(JSONObject(text)) } catch (e: Exception) { Log.w(TAG, "bad message", e) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket !== ws) return
                Log.w(TAG, "ws failure: ${t.message}")
                dropped("Offline (${t.javaClass.simpleName})")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket !== ws) return
                dropped("Closed")
            }
        })
    }

    private fun dropped(why: String) {
        connected = false; status = why; emit("_status")
        pending.values.forEach { it.cancel() }; pending.clear()
        ws = null
        main.postDelayed({ if (ws == null) connect() }, backoff)
        backoff = (backoff * 2).coerceAtMost(60_000)
    }

    private fun send(o: JSONObject): Boolean = ws?.send(o.toString()) ?: false

    private fun handle(m: JSONObject) {
        when (m.optString("type")) {
            "auth_required" -> send(JSONObject().put("type", "auth").put("access_token", token))
            "auth_ok" -> {
                connected = true; status = "Connected"; backoff = 2_000L
                subId = nextId.getAndIncrement()
                send(JSONObject().put("id", subId).put("type", "subscribe_entities").put("entity_ids", JSONArray(HaConfig.watch())))
                emit("_status")
            }
            "auth_invalid" -> {
                connected = false; status = "Token rejected"; emit("_status")
                ws?.close(1000, null); ws = null
                main.postDelayed({ connect() }, 300_000)
            }
            "event" -> if (m.optInt("id") == subId) applyEvent(m.getJSONObject("event"))
            "result" -> pending.remove(m.optInt("id"))?.complete(m)
        }
    }

    private fun applyEvent(ev: JSONObject) {
        ev.optJSONObject("a")?.let { a ->
            for (id in a.keys()) {
                val o = a.getJSONObject(id)
                states[id] = Ent(o.optString("s"), o.optJSONObject("a") ?: JSONObject(), o.optDouble("lc", 0.0))
                emit(id)
            }
        }
        ev.optJSONObject("c")?.let { c ->
            for (id in c.keys()) {
                val diff = c.getJSONObject(id)
                val cur = states[id] ?: Ent("", JSONObject(), 0.0)
                var st = cur.state; var lc = cur.lastChanged
                val attrs = JSONObject(cur.attrs.toString())
                diff.optJSONObject("+")?.let { p ->
                    if (p.has("s")) st = p.getString("s")
                    if (p.has("lc")) lc = p.getDouble("lc")
                    p.optJSONObject("a")?.let { na -> for (k in na.keys()) attrs.put(k, na.get(k)) }
                }
                diff.optJSONObject("-")?.optJSONArray("a")?.let { arr -> for (i in 0 until arr.length()) attrs.remove(arr.getString(i)) }
                states[id] = Ent(st, attrs, lc)
                emit(id)
            }
        }
        ev.optJSONArray("r")?.let { r -> for (i in 0 until r.length()) { states.remove(r.getString(i)); emit(r.getString(i)) } }
    }

    fun call(domain: String, service: String, entityId: String? = null, data: JSONObject? = null) {
        val o = JSONObject().put("id", nextId.getAndIncrement()).put("type", "call_service")
            .put("domain", domain).put("service", service)
        if (entityId != null) o.put("target", JSONObject().put("entity_id", entityId))
        if (data != null) o.put("service_data", data)
        if (!send(o)) Log.w(TAG, "call_service $domain.$service not sent (offline)")
    }

    private suspend fun request(o: JSONObject, timeoutMs: Long = 10_000): JSONObject? {
        val id = nextId.getAndIncrement()
        val d = CompletableDeferred<JSONObject>()
        pending[id] = d
        if (!send(o.put("id", id))) { pending.remove(id); return null }
        return withTimeoutOrNull(timeoutMs) { runCatching { d.await() }.getOrNull() }.also { pending.remove(id) }
    }

    /** HLS URL for a camera (the URL itself carries a short-lived access token). */
    suspend fun cameraStreamUrl(entity: String): String? {
        val r = request(JSONObject().put("type", "camera/stream").put("entity_id", entity)) ?: return null
        if (!r.optBoolean("success")) { Log.w(TAG, "camera/stream failed: $r"); return null }
        val url = r.optJSONObject("result")?.optString("url") ?: return null
        return if (url.startsWith("http")) url else HaConfig.url + url
    }

    /** A camera: [id] is the light "Fluent" sub-stream, [hiId] the full-resolution "Clear" stream when it's enabled. */
    class Cam(val id: String, val name: String, val hiId: String?)

    /** Every working camera (one entry per camera), doorbell first, then by name. */
    suspend fun cameras(): List<Cam> {
        val r = request(JSONObject().put("type", "get_states"), 15_000) ?: return emptyList()
        val arr = r.optJSONArray("result") ?: return emptyList()
        val ok = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("entity_id")
            if (id.startsWith("camera.") && "snapshot" !in id && o.optString("state") != "unavailable") ok[id] = o
        }
        val out = ArrayList<Cam>()
        for ((id, o) in ok) {
            if (id.endsWith("_clear") && ok.containsKey(id.removeSuffix("_clear") + "_fluent")) continue   // shown via its Fluent twin
            if (id.endsWith("_balanced")) continue
            val name = (o.optJSONObject("attributes")?.optString("friendly_name") ?: id)
                .replace(Regex("(?i)\\s*(fluent|clear)$"), "").replace(Regex("(?i)\\s*camera$"), "").trim()
            val hi = if (id.endsWith("_fluent")) (id.removeSuffix("_fluent") + "_clear").takeIf { ok.containsKey(it) } else null
            out.add(Cam(id, name, hi))
        }
        return out.sortedWith(compareBy({ if ("doorbell" in it.id) 0 else 1 }, { it.name.lowercase() }))
    }

    suspend fun snapshot(entity: String, width: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
        val t = token ?: return@withContext null
        try {
            val q = if (width > 0) "?width=$width" else ""
            val req = Request.Builder().url("${HaConfig.url}/api/camera_proxy/$entity$q").header("Authorization", "Bearer $t").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        } catch (e: Exception) { null }
    }

    fun speakAtDoor(message: String) {
        val d = HaConfig.door ?: return
        if (d.tts == null || d.speaker == null) return
        call("tts", "speak", d.tts, JSONObject()
            .put("media_player_entity_id", d.speaker).put("message", message).put("cache", true))
    }
}
