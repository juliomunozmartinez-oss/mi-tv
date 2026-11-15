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

    class Result(val movies: List<Movie>, val series: List<Series>) {
        val isEmpty get() = movies.isEmpty() && series.isEmpty()
    }

    private data class Entry(val title: String, val original: String)

    /** Descarga (una vez al día) y cruza con lo que puede ver este perfil. */
    suspend fun forView(ctx: Context, v: CatalogView): Result = withContext(Dispatchers.IO) {
        val json = load(File(ctx.filesDir, "trending.json")) ?: return@withContext Result(emptyList(), emptyList())
        val movies = entries(json, "movies")
        val series = entries(json, "series")
        withContext(Dispatchers.Default) {
            Result(match(movies, v.movies) { it.name }, match(series, v.series) { it.name })
        }
    }

    private fun load(file: File): JSONObject? {
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < MAX_AGE_MS
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

    /** Busca cada título del top en el catálogo (por nombre en español o el original), en el orden del top. */
    private fun <T> match(top: List<Entry>, items: List<T>, name: (T) -> String): List<T> {
        if (top.isEmpty() || items.isEmpty()) return emptyList()
        val wanted = HashSet<String>()
        top.forEach { e ->
            key(e.title).takeIf { it.isNotEmpty() }?.let(wanted::add)
            key(e.original).takeIf { it.isNotEmpty() }?.let(wanted::add)
        }
        val index = HashMap<String, T>()
        for (item in items) {
            val k = key(name(item))
            if (k in wanted && k !in index) index[k] = item
        }
        val out = ArrayList<T>()
        val used = HashSet<T>()
        for (e in top) {
            val hit = index[key(e.title)] ?: index[key(e.original)] ?: continue
            if (used.add(hit)) out += hit
        }
        return out
    }
}
