package com.mitv.player

import android.content.Context

/** Ajustes generales de la app (compartidos por todos los perfiles). */
class Prefs(context: Context) {
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
}
