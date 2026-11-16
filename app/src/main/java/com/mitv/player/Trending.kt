package com.mitv.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * «Lo más visto»: el top diario de películas y series (TMDB, publicado cada día por GitHub)
 * cruzado con el catálogo, para mostrar solo lo que sí está disponible.
 */
object Trending {
    private const val URL_JSON =
        "https://github.com/juliomunozmartinez-oss/mi-tv/releases/download/trending/trending.json"
    private const val MAX_AGE_MS = 20 * 60 * 60 * 1000L

    class Result(
        val movies: List<Movie>,
        val series: List<Series>,
    ) {
        val isEmpty get() = movies.isEmpty() && series.isEmpty()
    }

    private data class Entry(val title: String, val original: String)

    /** Último resultado por perfil, para mostrarlo de inmediato al volver al Inicio. */
    private val memo = HashMap<String, Result>()

    fun cached(profileId: String): Result? = memo[profileId]

    /** Descarga (una vez al día) y cruza con lo que puede ver este perfil. */
    suspend fun forView(ctx: Context, prefs: Prefs, profile: Profile, v: CatalogView): Result = withContext(Dispatchers.IO) {
        val json = load(File(ctx.filesDir, "trending.json")) ?: return@withContext Result(emptyList(), emptyList())
        val movies = entries(json, "movies")
        val series = entries(json, "series")
        val result = withContext(Dispatchers.Default) {
            // Reusa los títulos ya preparados para Buscar (mucho más rápido que volver a procesarlos).
            val idx = SearchIndex.get(prefs, profile, v)
            val movieByKey = HashMap<String, Movie>()
            val seriesByKey = HashMap<String, Series>()
            for (e in idx.entries) {
                val k = e.prepared.joined
                when (e.card.kind) {
                    Kind.MOVIE -> if (k !in movieByKey) Catalog.movieById[e.card.id]?.let { movieByKey[k] = it }
                    Kind.SERIES -> if (k !in seriesByKey) Catalog.seriesById[e.card.id]?.let { seriesByKey[k] = it }
                    else -> Unit
                }
            }
            Result(pick(movies, movieByKey), pick(series, seriesByKey))
        }
        memo[profile.id] = result
        result
    }

    private fun <T> pick(top: List<Entry>, byKey: Map<String, T>): List<T> {
        val out = ArrayList<T>()
        val used = HashSet<T>()
        for (e in top) {
            val hit = byKey[key(e.title)] ?: byKey[key(e.original)] ?: continue
            if (used.add(hit)) out += hit
        }
        return out
    }

    private fun load(file: File): JSONObject? {
        // Si el guardado es de la lista anterior, bajarlo de nuevo.
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS &&
            runCatching { file.readText().contains("\"src\": \"week\"") }.getOrDefault(false)
        if (!fresh) {
            runCatching {
                val conn = URL(URL_JSON).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.instanceFollowRedirects = true
                try {
                    if (conn.responseCode in 200..299) {
                        val text = conn.inputStream.bufferedReader().use { it.readText() }
                        JSONObject(text) // validar antes de guardar
                        file.writeText(text)
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
        // Si no hay internet, usar el último guardado aunque sea viejo.
        return runCatching { JSONObject(file.readText()) }.getOrNull()
    }

    private fun entries(json: JSONObject, key: String): List<Entry> {
        val arr = json.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(o.optString("t"), o.optString("o"))
        }
    }

    private fun key(name: String) = SearchMatcher.tokens(name).joinToString(" ")
}
