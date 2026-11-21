package com.example.angelwatch

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

object Store {
    private lateinit var sp: SharedPreferences
    private lateinit var secure: SharedPreferences
    val lists = MutableStateFlow<List<WatchList>>(listOf(WatchList("My fav", emptyList())))

    @Synchronized
    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        val app = ctx.applicationContext
        sp = app.getSharedPreferences("app", Context.MODE_PRIVATE)
        val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        secure = EncryptedSharedPreferences.create(
            app, "secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        lists.value = load()
    }

    private fun load(): List<WatchList> {
        val s = sp.getString("lists", null) ?: return listOf(WatchList("My fav", emptyList()))
        return try {
            val a = JSONArray(s)
            val out = ArrayList<WatchList>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val sy = o.getJSONArray("s")
                val syms = ArrayList<String>()
                for (j in 0 until sy.length()) syms.add(sy.getString(j))
                out.add(WatchList(o.getString("n"), syms))
            }
            out.ifEmpty { listOf(WatchList("My fav", emptyList())) }
        } catch (e: Exception) {
            listOf(WatchList("My fav", emptyList()))
        }
    }

    private fun update(f: (List<WatchList>) -> List<WatchList>) {
        var n = f(lists.value)
        if (n.isEmpty()) n = listOf(WatchList("My fav", emptyList()))
        lists.value = n
        val a = JSONArray()
        n.forEach { w -> a.put(JSONObject().put("n", w.name).put("s", JSONArray(w.symbols))) }
        sp.edit().putString("lists", a.toString()).apply()
    }

    fun addList(name: String) = update { l ->
        val n = name.trim()
        if (n.isEmpty() || l.any { it.name == n }) l else l + WatchList(n, emptyList())
    }

    fun rename(old: String, new: String) = update { l ->
        val n = new.trim()
        if (n.isEmpty() || l.any { it.name == n }) l else l.map { if (it.name == old) it.copy(name = n) else it }
    }

    fun deleteList(name: String) = update { l -> l.filter { it.name != name } }

    fun addSymbol(list: String, sym: String) = update { l ->
        l.map { if (it.name == list && sym !in it.symbols) it.copy(symbols = it.symbols + sym) else it }
    }

    fun removeSymbol(list: String, sym: String) = update { l ->
        l.map { if (it.name == list) it.copy(symbols = it.symbols - sym) else it }
    }

    fun move(list: String, sym: String, delta: Int) = update { l ->
        l.map { w ->
            if (w.name != list) w else {
                val m = w.symbols.toMutableList()
                val i = m.indexOf(sym)
                val j = i + delta
                if (i >= 0 && j in m.indices) {
                    m.removeAt(i); m.add(j, sym)
                }
                w.copy(symbols = m)
            }
        }
    }

    /** Returns Pair(added, skipped). */
    fun importText(list: String, text: String): Pair<Int, Int> {
        val tokens = text.split(Regex("[,;\\s]+")).map {
            it.trim().uppercase().substringBefore(":").removeSuffix("-EQ")
        }.filter { it.isNotEmpty() }.distinct()
        var added = 0
        var skipped = 0
        val ok = ArrayList<String>()
        tokens.forEach { if (Instruments.get(it) != null) ok.add(it) else skipped++ }
        update { l ->
            l.map { w ->
                if (w.name != list) w else {
                    val fresh = ok.filter { it !in w.symbols }
                    added = fresh.size
                    w.copy(symbols = w.symbols + fresh)
                }
            }
        }
        return added to skipped
    }

    fun creds() = Creds(
        secure.getString("k", "") ?: "", secure.getString("c", "") ?: "",
        secure.getString("p", "") ?: "", secure.getString("t", "") ?: ""
    )

    fun saveCreds(c: Creds) {
        secure.edit().putString("k", c.apiKey.trim()).putString("c", c.client.trim())
            .putString("p", c.pin.trim()).putString("t", c.totp.trim()).apply()
    }

    fun getRaw(k: String): String? = sp.getString(k, null)
    fun putRaw(k: String, v: String) { sp.edit().putString(k, v).apply() }

    var voice: Boolean
        get() = sp.getBoolean("voice", true)
        set(v) { sp.edit().putBoolean("voice", v).apply() }

    fun hasCreds(): Boolean = creds().let { it.apiKey.isNotBlank() && it.client.isNotBlank() && it.pin.isNotBlank() && it.totp.isNotBlank() }

    var alertsOn: Boolean
        get() = sp.getBoolean("alertsOn", true)
        set(v) { sp.edit().putBoolean("alertsOn", v).apply() }

    var stopped: Boolean
        get() = sp.getBoolean("stopped", false)
        set(v) { sp.edit().putBoolean("stopped", v).apply() }
}
