package com.mitv.player

import android.content.Context

/** Ajustes generales de la app (compartidos por todos los perfiles). */
class Prefs(context: Context) {
    private val appContext = context.applicationContext

    /** Archivo donde se guarda el catálogo para abrir rápido. */
    fun catalogFile() = java.io.File(appContext.filesDir, "catalog.json")
    private val sp = context.applicationContext.getSharedPreferences("mitv", Context.MODE_PRIVATE)

    var server: String
        get() = sp.getString("server", "") ?: ""
        set(v) = sp.edit().putString("server", v).apply()

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit().putString("username", v).apply()

    var password: String
        get() = sp.getString("password", "") ?: ""
        set(v) = sp.edit().putString("password", v).apply()

    val isConfigured: Boolean
        get() = server.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    val accountKey: String
        get() = "$server|$username|$password"

    fun client() = XtreamClient(server, username, password)

    // ---- PIN de adultos (desactivado de inicio) ----
    var pinEnabled: Boolean
        get() = sp.getBoolean("pin_enabled", false)
        set(v) = sp.edit().putBoolean("pin_enabled", v).apply()

    var pin: String
        get() = sp.getString("pin", "") ?: ""
        set(v) = sp.edit().putString("pin", v).apply()

    // ---- Categorías permitidas en perfiles infantiles ("live:ID", "vod:ID", "series:ID") ----
    var kidsInitialized: Boolean
        get() = sp.getBoolean("kids_initialized", false)
        set(v) = sp.edit().putBoolean("kids_initialized", v).apply()

    var kidsCategories: Set<String>
        get() = sp.getStringSet("kids_categories", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("kids_categories", v).apply()

    fun toggleKidsCategory(key: String): Boolean {
        val s = kidsCategories.toMutableSet()
        val added = s.add(key)
        if (!added) s.remove(key)
        kidsCategories = s
        return added
    }

    // ---- Avance automático al pasar por un póster ----
    var autoPreview: Boolean
        get() = sp.getBoolean("auto_preview", true)
        set(v) = sp.edit().putBoolean("auto_preview", v).apply()

    // ---- Categorías ocultas (para adultos) y orden personalizado ----
    var hiddenCategories: Set<String>
        get() = sp.getStringSet("hidden_categories", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden_categories", v).apply()

    fun toggleHidden(key: String): Boolean {
        val s = hiddenCategories.toMutableSet()
        val hidden = s.add(key)
        if (!hidden) s.remove(key)
        hiddenCategories = s
        return hidden
    }

    fun categoryOrder(kind: Kind): List<String> =
        (sp.getString("order_${kind.name}", "") ?: "").split('\n').filter { it.isNotBlank() }

    fun setCategoryOrder(kind: Kind, keys: List<String>) =
        sp.edit().putString("order_${kind.name}", keys.joinToString("\n")).apply()

    // ---- Tamaño de subtítulos: 0 normal, 1 grande, 2 muy grande ----
    var subtitleSize: Int
        get() = sp.getInt("sub_size", 1)
        set(v) = sp.edit().putInt("sub_size", v).apply()

    // ---- Volumen propio de la app (para teles cuyo volumen no responde) ----
    /** true = los botones + / − del control cambian el volumen dentro de MuGa TV. */
    var appVolumeMode: Boolean
        get() = sp.getBoolean("app_volume", false)
        set(v) = sp.edit().putBoolean("app_volume", v).apply()

    /** Nivel 0–150 (más de 100 amplifica). */
    var appVolume: Int
        get() = sp.getInt("app_volume_level", 100)
        set(v) = sp.edit().putInt("app_volume_level", v.coerceIn(0, 150)).apply()
}
