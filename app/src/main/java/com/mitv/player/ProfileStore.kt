package com.mitv.player

import android.content.Context
import org.json.JSONObject

/**
 * Lo que vio cada perfil para «Seguir viendo».
 * Las películas se guardan como "movie:ID"; las series como "series:ID" con el último episodio.
 */
data class Resume(
    val key: String,
    val kind: Kind,
    val id: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val ext: String,
    val episodeId: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    fun toJson(): JSONObject = JSONObject()
        .put("kind", kind.name).put("id", id).put("title", title)
        .put("subtitle", subtitle ?: "").put("image", image ?: "").put("ext", ext)
        .put("episodeId", episodeId ?: "").put("pos", positionMs).put("dur", durationMs)
        .put("ts", updatedAt)

    companion object {
        fun fromJson(key: String, o: JSONObject) = Resume(
            key = key,
            kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.MOVIE),
            id = o.optString("id"),
            title = o.optString("title"),
            subtitle = o.optString("subtitle").ifBlank { null },
            image = o.optString("image").ifBlank { null },
            ext = o.optString("ext", "mp4"),
            episodeId = o.optString("episodeId").ifBlank { null },
            positionMs = o.optLong("pos"),
            durationMs = o.optLong("dur"),
            updatedAt = o.optLong("ts"),
        )

        fun movieKey(id: String) = "movie:$id"
        fun seriesKey(id: String) = "series:$id"
    }
}

class ProfileStore(context: Context, profileId: String) {
    private val sp = context.applicationContext.getSharedPreferences("profile_$profileId", Context.MODE_PRIVATE)

    private fun resumeJson(): JSONObject =
        runCatching { JSONObject(sp.getString("resume", "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun continueWatching(): List<Resume> {
        val json = resumeJson()
        return json.keys().asSequence()
            .mapNotNull { k -> json.optJSONObject(k)?.let { Resume.fromJson(k, it) } }
            .sortedByDescending { it.updatedAt }
            .toList()
    }

    fun get(key: String): Resume? = resumeJson().optJSONObject(key)?.let { Resume.fromJson(key, it) }

    fun save(r: Resume) {
        val json = resumeJson()
        json.put(r.key, r.toJson())
        // Guardar como máximo 40 títulos.
        if (json.length() > 40) {
            continueWatching().drop(40).forEach { json.remove(it.key) }
        }
        sp.edit().putString("resume", json.toString()).apply()
    }

    fun remove(key: String) {
        val json = resumeJson()
        json.remove(key)
        sp.edit().putString("resume", json.toString()).apply()
    }

    // ---- Mi lista ("movie:ID", "series:ID", "live:ID") ----
    var myList: List<String>
        get() = (sp.getString("my_list", "") ?: "").split('\n').filter { it.isNotBlank() }
        private set(v) = sp.edit().putString("my_list", v.joinToString("\n")).apply()

    fun inList(key: String) = key in myList

    fun toggleList(key: String): Boolean {
        val list = myList.toMutableList()
        return if (list.remove(key)) {
            myList = list
            false
        } else {
            list.add(0, key)
            myList = list
            true
        }
    }
}
