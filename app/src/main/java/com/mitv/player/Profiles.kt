package com.mitv.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Perfiles guardados en la tele; se pueden editar desde Ajustes → Perfiles. */
object Profiles {
    const val MAX = 8

    val palette = listOf(
        0xFF3B82F6.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFEC4899.toInt(),
        0xFF8B5CF6.toInt(), 0xFFEF4444.toInt(), 0xFF14B8A6.toInt(), 0xFF64748B.toInt(),
    )

    private val defaults = listOf(
        Profile("jenny", "Jenny", kids = false, color = palette[0]),
        Profile("julio", "Julio", kids = false, color = palette[1]),
        Profile("cam", "Cam", kids = true, color = palette[2]),
        Profile("maju", "Maju", kids = true, color = palette[3]),
    )

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("mitv", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Profile> {
        val raw = sp(ctx).getString("profiles", null) ?: return defaults
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(o.getString("id"), o.getString("name"), o.optBoolean("kids"), o.optInt("color", palette[0]))
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: defaults
    }

    fun save(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("kids", it.kids).put("color", it.color))
        }
        sp(ctx).edit().putString("profiles", arr.toString()).apply()
    }

    fun byId(ctx: Context, id: String?): Profile? = all(ctx).firstOrNull { it.id == id }

    fun newId() = "p" + System.currentTimeMillis().toString(36)
}
