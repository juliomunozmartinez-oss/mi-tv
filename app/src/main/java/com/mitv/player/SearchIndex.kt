package com.mitv.player

/**
 * Índice de búsqueda preparado una sola vez por perfil (en segundo plano desde el Inicio),
 * para que la pantalla Buscar responda al instante.
 */
object SearchIndex {
    data class Entry(val card: Card, val prepared: SearchMatcher.Prepared, val boost: Int)

    class Data(val key: String, val entries: List<Entry>, val suggestions: List<Card>)

    private val cache = HashMap<String, Data>()

    /** Cambia si cambia el catálogo o lo que puede ver el perfil. */
    private fun keyFor(prefs: Prefs, profile: Profile) = listOf(
        Catalog.loadedKey, Catalog.live.size, Catalog.movies.size, Catalog.series.size,
        profile.kids, prefs.kidsCategories.hashCode(), prefs.hiddenCategories.hashCode(),
    ).joinToString("|")

    /** Regresa el índice del perfil, armándolo si hace falta. Llamar fuera del hilo de pantalla. */
    @Synchronized
    fun get(prefs: Prefs, profile: Profile, view: CatalogView? = null): Data {
        val key = keyFor(prefs, profile)
        cache[profile.id]?.takeIf { it.key == key }?.let { return it }
        val v = view ?: CatalogView(prefs, profile)
        val list = ArrayList<Entry>(v.series.size + v.movies.size + v.live.size)
        v.series.forEach {
            list += Entry(Card(Kind.SERIES, it.id, it.name, image = it.poster), SearchMatcher.prepare(it.name), 30)
        }
        v.movies.forEach {
            list += Entry(Card(Kind.MOVIE, it.id, it.name, image = it.poster), SearchMatcher.prepare(it.name), 20)
        }
        v.live.forEach {
            list += Entry(Card(Kind.LIVE, it.id, it.name, image = it.icon, logo = true), SearchMatcher.prepare(it.name), 0)
        }
        // Sugerencias mientras no se escribe nada: lo más nuevo, series y películas mezcladas.
        val s = v.recentSeries.take(12).map { Card(Kind.SERIES, it.id, it.name, image = it.poster) }
        val m = v.recentMovies.take(12).map { Card(Kind.MOVIE, it.id, it.name, image = it.poster) }
        val data = Data(key, list, s.zip(m).flatMap { listOf(it.first, it.second) })
        cache[profile.id] = data
        return data
    }
}
