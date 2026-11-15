package com.mitv.player

import android.content.Context

/** Ajustes guardados en la tele: servidor, cuenta, favoritos y categorías ocultas. */
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

    /** Cambia cuando cambia la cuenta: sirve para saber si hay que recargar la lista. */
    val accountKey: String
        get() = "$server|$username|$password"

    var favorites: Set<String>
        get() = sp.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("favorites", v).apply()

    var hiddenCategories: Set<String>
        get() = sp.getStringSet("hidden_categories", emptySet())?.toSet() ?: emptySet()
        set(v) {
            sp.edit().putStringSet("hidden_categories", v).apply()
            layoutVersion += 1
        }

    var lastCategory: String
        get() = sp.getString("last_category", "") ?: ""
        set(v) = sp.edit().putString("last_category", v).apply()

    /** Sube cada vez que cambia lo que se muestra en la lista de categorías. */
    var layoutVersion: Int
        get() = sp.getInt("layout_version", 0)
        private set(v) = sp.edit().putInt("layout_version", v).apply()

    /** Agrega o quita un favorito. Regresa true si quedó como favorito. */
    fun toggleFavorite(channelId: String): Boolean {
        val set = favorites.toMutableSet()
        val added = set.add(channelId)
        if (!added) set.remove(channelId)
        favorites = set
        return added
    }

    fun hideCategory(categoryId: String) {
        hiddenCategories = hiddenCategories + categoryId
    }

    fun unhideAll() {
        hiddenCategories = emptySet()
    }
}
