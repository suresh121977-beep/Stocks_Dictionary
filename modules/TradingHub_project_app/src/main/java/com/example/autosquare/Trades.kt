package com.example.autosquare
import org.json.JSONArray
import org.json.JSONObject

/** Trades placed from the app that the service manages (partial profit booking + trailing stop). */
object Trades {
    private const val K = "trades"
    @Synchronized fun list(s: Store): List<JSONObject> { val a = JSONArray(s.get(K, "[]")); return (0 until a.length()).map { a.getJSONObject(it) } }
    @Synchronized private fun save(s: Store, l: List<JSONObject>) { s.put(K to JSONArray(l).toString()) }
    @Synchronized fun add(s: Store, t: JSONObject) { save(s, list(s) + t) }
    @Synchronized fun put(s: Store, t: JSONObject) { save(s, list(s).map { if (it.getString("id") == t.getString("id")) t else it }) }
    @Synchronized fun remove(s: Store, id: String) { save(s, list(s).filter { it.getString("id") != id }) }
}
