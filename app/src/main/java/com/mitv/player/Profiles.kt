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
        Profile("maju", "Maju", kids = false, color = palette[3], teen = true),
    )

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("mitv", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Profile> {
        val raw = sp(ctx).getString("profiles", null) ?: return defaults
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Profile(
                    o.getString("id"), o.getString("name"), o.optBoolean("kids"), o.optInt("color", palette[0]),
                    o.optString("avatar").ifBlank { null },
                    o.optBoolean("teen"),
                )
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: defaults
    }

    fun save(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("kids", it.kids).put("color", it.color)
                    .put("avatar", it.avatar ?: "").put("teen", it.teen)
            )
        }
        sp(ctx).edit().putString("profiles", arr.toString()).apply()
    }

    fun byId(ctx: Context, id: String?): Profile? = all(ctx).firstOrNull { it.id == id }

    fun newId() = "p" + System.currentTimeMillis().toString(36)
}

/** Avatares de héroes originales incluidos en la app. */
object Avatars {
    val all: List<Pair<String, Int>> = listOf(
        "rayo" to R.drawable.hero_rayo,
        "escudo" to R.drawable.hero_escudo,
        "mascara" to R.drawable.hero_mascara,
        "cohete" to R.drawable.hero_cohete,
        "robot" to R.drawable.hero_robot,
        "estrella" to R.drawable.hero_estrella,
        "llama" to R.drawable.hero_llama,
        "corona" to R.drawable.hero_corona,
        "diamante" to R.drawable.hero_diamante,
        "luna" to R.drawable.hero_luna,
        "alas" to R.drawable.hero_alas,
        "atomo" to R.drawable.hero_atomo,
    )

    fun resFor(name: String?): Int? = all.firstOrNull { it.first == name }?.second

    /** Pinta el avatar de un perfil: imagen de héroe si tiene, o su inicial sobre su color. */
    fun bind(frame: android.view.View, initial: android.widget.TextView, image: android.widget.ImageView, p: Profile) {
        val res = resFor(p.avatar)
        if (res != null) {
            frame.background = null
            image.setImageResource(res)
            image.visibility = android.view.View.VISIBLE
            initial.visibility = android.view.View.GONE
        } else {
            frame.background = Ui.circle(p.color)
            image.visibility = android.view.View.GONE
            initial.visibility = android.view.View.VISIBLE
            initial.text = p.initial
        }
    }
}
