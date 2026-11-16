package com.mitv.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Canales «sin señal»: si un canal no abre 3 veces seguidas se marca por 24 horas.
 * Se pasa al final, el cambio de canal se lo salta y al día siguiente se vuelve a probar.
 */
object DeadChannels {
    private const val FAILS = 3
    private const val RETRY_MS = 24 * 60 * 60 * 1000L

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("mitv_live", Context.MODE_PRIVATE)

    private fun load(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString("dead", "{}") ?: "{}") }.getOrDefault(JSONObject())

    private fun save(ctx: Context, o: JSONObject) = sp(ctx).edit().putString("dead", o.toString()).apply()

    fun recordFailure(ctx: Context, id: String) {
        val o = load(ctx)
        val e = o.optJSONObject(id) ?: JSONObject()
        val fails = e.optInt("f") + 1
        e.put("f", fails)
        if (fails >= FAILS) e.put("at", System.currentTimeMillis())
        o.put(id, e)
        save(ctx, o)
    }

    fun recordSuccess(ctx: Context, id: String) {
        val o = load(ctx)
        if (o.has(id)) {
            o.remove(id)
            save(ctx, o)
        }
    }

    fun deadIds(ctx: Context): Set<String> {
        val o = load(ctx)
        val now = System.currentTimeMillis()
        return o.keys().asSequence().filter { k ->
            val e = o.optJSONObject(k) ?: return@filter false
            e.optInt("f") >= FAILS && now - e.optLong("at") < RETRY_MS
        }.toSet()
    }

    fun clear(ctx: Context) = save(ctx, JSONObject())
}

/** Recordatorios de programas de la guía. */
object Reminders {
    data class Item(val channelId: String, val channelName: String, val title: String, val start: Long)

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("mitv_live", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Item> {
        val arr = runCatching { JSONArray(sp(ctx).getString("reminders", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optString("c"), o.optString("n"), o.optString("t"), o.optLong("s"))
        }
    }

    private fun save(ctx: Context, list: List<Item>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("c", it.channelId).put("n", it.channelName).put("t", it.title).put("s", it.start)) }
        sp(ctx).edit().putString("reminders", arr.toString()).apply()
    }

    fun has(ctx: Context, channelId: String, start: Long) = all(ctx).any { it.channelId == channelId && it.start == start }

    /** Agrega o quita; regresa true si quedó puesto. */
    fun toggle(ctx: Context, item: Item): Boolean {
        val list = all(ctx).toMutableList()
        val removed = list.removeAll { it.channelId == item.channelId && it.start == item.start }
        if (!removed) list += item
        save(ctx, list.filter { it.start > System.currentTimeMillis() / 1000 - 600 })
        return !removed
    }

    /** Los que empiezan en los próximos 2 minutos (o ya empezaron hace poco). Se quitan al regresarlos. */
    fun takeDue(ctx: Context): List<Item> {
        val now = System.currentTimeMillis() / 1000
        val list = all(ctx)
        val due = list.filter { it.start - now <= 120 && now - it.start < 600 }
        if (due.isNotEmpty() || list.any { now - it.start >= 600 }) {
            save(ctx, list.filter { it !in due && now - it.start < 600 })
        }
        return due
    }
}

/** Perfil que se usó por última vez (para abrir un canal desde un recordatorio). */
object LastProfile {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("mitv_live", Context.MODE_PRIVATE)
    fun set(ctx: Context, id: String) = sp(ctx).edit().putString("last_profile", id).apply()
    fun get(ctx: Context): String? = sp(ctx).getString("last_profile", null)
}
