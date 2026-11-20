package com.cesmotacon.tvhome

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What the All lights screen shows, one of two ways:
 *  - With `all_lights.dashboard` in ha.json: that dashboard's lights and switches, in its order, under its headings,
 *    with the names its cards give them. Groups stay groups, so a room's bulbs come and go together.
 *  - Otherwise: Home Assistant's floors, the areas (rooms) on each, and the lights in each room. A light belongs to
 *    its own area, or else its device's area. Hidden lights and config/diagnostic lights (a voice satellite's LED
 *    ring, say) are left out. Rooms on no floor, and lights in no room, go under "Other" at the end.
 * Either way, anything in `all_lights.hide` is left out. Needs no admin rights: these lists are open to any user.
 */
object HaLights {
    /** A room; a blank [name] means the section has no rooms (dashboard mode). */
    class Room(val name: String, val lights: List<String>)
    class Floor(val name: String, val rooms: List<Room>)

    /** Names a dashboard's cards give their entities (they win over Home Assistant's own names). */
    private val cardNames = HashMap<String, String>()

    private fun JSONObject.str(k: String): String? = if (!has(k) || isNull(k)) null else optString(k).trim().takeIf { it.isNotEmpty() }

    private suspend fun list(type: String, timeoutMs: Long = 15_000): JSONObject? =
        Ha.request(JSONObject().put("type", type), timeoutMs)?.takeIf { it.optBoolean("success") }

    /** The sections in order, or null if Home Assistant didn't answer. */
    suspend fun layout(): List<Floor>? {
        cardNames.clear()
        return HaConfig.lightsDashboard?.let { fromDashboard(it) } ?: fromAreas()
    }

    /** Lights and switches on a dashboard, sectioned by its heading cards (or view titles). */
    private suspend fun fromDashboard(path: String): List<Floor>? {
        val cfg = Ha.request(JSONObject().put("type", "lovelace/config").put("url_path", path), 15_000)
            ?.takeIf { it.optBoolean("success") }?.optJSONObject("result") ?: return null
        val sections = LinkedHashMap<String, MutableList<String>>()
        val seen = HashSet<String>()
        var title = "Lights"
        val skip = setOf("tap_action", "hold_action", "double_tap_action", "visibility", "conditions", "badges", "features", "card_mod")
        fun add(id: String?, name: String?) {
            if (id == null || id in seen || id in HaConfig.lightsHide) return
            if (id.substringBefore('.') !in setOf("light", "switch")) return
            seen.add(id)
            name?.let { cardNames[id] = it }
            sections.getOrPut(title) { ArrayList() }.add(id)
        }
        fun walk(o: Any?) {
            when (o) {
                is JSONObject -> {
                    if (o.optString("type") == "heading") { o.str("heading")?.let { title = it }; return }
                    add(o.str("entity"), o.str("name"))
                    o.optJSONArray("entities")?.let { a ->
                        for (i in 0 until a.length()) when (val e = a.opt(i)) { is String -> add(e, null); is JSONObject -> add(e.str("entity"), e.str("name")) }
                    }
                    for (k in o.keys()) if (k !in skip && k != "entity" && k != "entities") walk(o.opt(k))
                }
                is JSONArray -> for (i in 0 until o.length()) walk(o.opt(i))
            }
        }
        val views = cfg.optJSONArray("views") ?: return emptyList()
        for (v in objects(views)) { title = v.str("title") ?: "Lights"; walk(v) }
        return sections.map { (name, ids) -> Floor(name, listOf(Room("", ids))) }
    }

    private suspend fun fromAreas(): List<Floor>? {
        val floors = list("config/floor_registry/list")?.optJSONArray("result") ?: return null
        val areas = list("config/area_registry/list")?.optJSONArray("result") ?: return null
        val devices = list("config/device_registry/list", 20_000)?.optJSONArray("result") ?: return null
        val ents = list("config/entity_registry/list_for_display", 20_000)?.optJSONObject("result")?.optJSONArray("entities") ?: return null

        val deviceArea = HashMap<String, String>()
        for (d in objects(devices)) { val a = d.str("area_id") ?: continue; deviceArea[d.str("id") ?: continue] = a }

        val byArea = LinkedHashMap<String?, MutableList<String>>()
        for (e in objects(ents)) {
            val id = e.str("ei") ?: continue
            if (!id.startsWith("light.") || id in HaConfig.lightsHide) continue
            if (e.has("ec") || e.optBoolean("hb")) continue   // config/diagnostic category, or hidden
            val area = e.str("ai") ?: e.str("di")?.let { deviceArea[it] }
            byArea.getOrPut(area) { ArrayList() }.add(id)
        }

        class F(val id: String, val name: String, val level: Int?, val index: Int)
        val fl = objects(floors).mapIndexed { i, f -> F(f.str("floor_id") ?: "", f.str("name") ?: "Floor", if (f.isNull("level")) null else f.optInt("level"), i) }
        val order = HaConfig.floorOrder
        val sorted = fl.sortedWith(compareBy<F>({ order.indexOf(it.id).let { p -> if (p < 0) Int.MAX_VALUE else p } }, { it.level ?: Int.MAX_VALUE }, { it.index }))

        val areaList = objects(areas)
        fun room(a: JSONObject): Room? = byArea[a.str("area_id")]?.takeIf { it.isNotEmpty() }?.let { Room(a.str("name") ?: "Room", it) }

        val out = ArrayList<Floor>()
        val floorIds = fl.map { it.id }.toSet()
        for (f in sorted) {
            val rooms = areaList.filter { it.str("floor_id") == f.id }.mapNotNull { room(it) }
            if (rooms.isNotEmpty()) out.add(Floor(f.name, rooms))
        }
        val other = areaList.filter { it.str("floor_id").let { id -> id == null || id !in floorIds } }.mapNotNull { room(it) }.toMutableList()
        byArea[null]?.takeIf { it.isNotEmpty() }?.let { other.add(Room("Not in a room", it)) }
        if (other.isNotEmpty()) out.add(Floor("Other", other))
        return out
    }

    private fun objects(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    // ---------------------------------------------------------------- reading a light's state (from Ha.states)

    private fun attrs(id: String) = Ha.states[id]?.attrs

    /** The light's name, without its room's name in front ("Guest Room Light 1" in Guest Room is "Light 1"). */
    fun name(id: String, room: String?): String {
        cardNames[id]?.let { return it }
        val n = attrs(id)?.optString("friendly_name")?.trim()?.takeIf { it.isNotEmpty() } ?: id.substringAfter('.')
        if (!room.isNullOrBlank() && n.length > room.length + 1 && n.startsWith(room, ignoreCase = true) && n[room.length] == ' ')
            return n.substring(room.length + 1).trim().replaceFirstChar { it.uppercase() }
        return n
    }

    /** Sorts names with numbers in number order ("#2" before "#10"). */
    fun sortKey(s: String) = s.lowercase().replace(Regex("\\d+")) { it.value.padStart(6, '0') }

    fun available(id: String) = Ha.states[id]?.state.let { it != null && it != "unavailable" }
    fun isOn(id: String) = Ha.states[id]?.state == "on"

    /** Brightness 1-100 while on (null for lights that only switch). */
    fun percent(id: String): Int? {
        val a = Ha.states[id]?.takeIf { it.state == "on" }?.attrs ?: return null
        if (!a.has("brightness") || a.isNull("brightness")) return null
        return (a.optDouble("brightness") * 100 / 255).roundToInt().coerceIn(1, 100)
    }

    /** The colour it's shining (a warm white for lights that don't report one). */
    fun color(id: String): Int {
        val rgb = attrs(id)?.optJSONArray("rgb_color")
        if (rgb != null && rgb.length() == 3) return Color.rgb(rgb.optInt(0), rgb.optInt(1), rgb.optInt(2))
        return 0xFFFFC27A.toInt()
    }

    fun kelvin(id: String): Int? = attrs(id)?.takeIf { it.optString("color_mode") == "color_temp" && it.has("color_temp_kelvin") && !it.isNull("color_temp_kelvin") }?.optInt("color_temp_kelvin")

    private fun modes(id: String): Set<String> =
        attrs(id)?.optJSONArray("supported_color_modes")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()

    fun dimmable(id: String) = modes(id).any { it != "onoff" }
    fun whites(id: String) = "color_temp" in modes(id)
    fun colours(id: String) = modes(id).any { it in setOf("hs", "xy", "rgb", "rgbw", "rgbww") }
}
