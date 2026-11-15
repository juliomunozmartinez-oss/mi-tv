package com.mitv.player

/** Lista de canales en memoria, compartida entre pantallas. */
object ChannelRepo {
    const val FAVORITES = "__fav__"
    const val ALL = "__all__"

    var categories: List<Category> = emptyList()
        private set
    var channels: List<Channel> = emptyList()
        private set
    var loadedKey: String? = null
        private set

    /** Canales de la categoría que se está viendo (para cambiar con ▲▼ en el reproductor). */
    var current: List<Channel> = emptyList()

    suspend fun load(client: XtreamClient, key: String) {
        val cats = client.liveCategories()
        val streams = client.liveStreams()
        categories = cats
        channels = streams
        loadedKey = key
    }

    fun channelsFor(categoryId: String, prefs: Prefs): List<Channel> {
        val hidden = prefs.hiddenCategories
        return when (categoryId) {
            FAVORITES -> {
                val favs = prefs.favorites
                channels.filter { it.id in favs }
            }
            ALL -> channels.filter { it.categoryId !in hidden }
            else -> channels.filter { it.categoryId == categoryId }
        }
    }
}
