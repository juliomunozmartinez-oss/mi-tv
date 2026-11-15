package com.mitv.player

import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

class XtreamException(message: String) : Exception(message)

/** Habla con un servidor tipo Xtream Codes (player_api.php). */
class XtreamClient(server: String, private val username: String, private val password: String) {

    val baseUrl: String = normalizeServer(server)

    private fun apiUrl(action: String?, extra: String = ""): String {
        val sb = StringBuilder("$baseUrl/player_api.php?username=")
            .append(Uri.encode(username))
            .append("&password=")
            .append(Uri.encode(password))
        if (action != null) sb.append("&action=").append(action)
        sb.append(extra)
        return sb.toString()
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 90_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        val code = conn.responseCode
        if (code == 401 || code == 403) {
            conn.disconnect()
            throw XtreamException("El servidor rechazó la cuenta (código $code)")
        }
        if (code !in 200..299) {
            conn.disconnect()
            throw XtreamException("El servidor respondió con el código $code")
        }
        return conn
    }

    private fun getText(url: String): String {
        val conn = open(url)
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun getObject(url: String): JSONObject = try {
        JSONObject(getText(url))
    } catch (e: JSONException) {
        throw XtreamException("Respuesta inesperada del servidor")
    }

    /**
     * Lee una lista grande sin cargarla toda en memoria como texto.
     * Cada elemento llega como mapa plano de texto; de las listas internas solo se toma el primer valor.
     */
    private fun <T> streamList(url: String, map: (Map<String, String>) -> T?): List<T> {
        val conn = open(url)
        try {
            JsonReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { r ->
                r.isLenient = true
                if (r.peek() != JsonToken.BEGIN_ARRAY) {
                    r.skipValue()
                    return emptyList()
                }
                val out = ArrayList<T>()
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() == JsonToken.BEGIN_OBJECT) {
                        map(readFlat(r))?.let { out += it }
                    } else {
                        r.skipValue()
                    }
                }
                r.endArray()
                return out
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readFlat(r: JsonReader): Map<String, String> {
        val m = HashMap<String, String>(24)
        r.beginObject()
        while (r.hasNext()) {
            val k = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[k] = r.nextString()
                JsonToken.BOOLEAN -> m[k] = r.nextBoolean().toString()
                JsonToken.NULL -> r.nextNull()
                JsonToken.BEGIN_ARRAY -> {
                    var first: String? = null
                    r.beginArray()
                    while (r.hasNext()) {
                        if (first == null && r.peek() == JsonToken.STRING) first = r.nextString() else r.skipValue()
                    }
                    r.endArray()
                    if (!first.isNullOrBlank()) m[k] = first
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    suspend fun login(): AccountInfo = withContext(Dispatchers.IO) {
        val json = try {
            JSONObject(getText(apiUrl(null)))
        } catch (e: JSONException) {
            throw XtreamException("Respuesta inesperada del servidor. ¿La dirección es correcta?")
        }
        val ui = json.optJSONObject("user_info")
            ?: throw XtreamException("Usuario o contraseña incorrectos")
        if (ui.optInt("auth", 0) != 1) throw XtreamException("Usuario o contraseña incorrectos")
        AccountInfo(ui.optString("status", ""), ui.optString("exp_date", "").toLongOrNull())
    }

    suspend fun categories(kind: Kind): List<Category> = withContext(Dispatchers.IO) {
        val action = when (kind) {
            Kind.LIVE -> "get_live_categories"
            Kind.MOVIE -> "get_vod_categories"
            Kind.SERIES -> "get_series_categories"
        }
        streamList(apiUrl(action)) { m ->
            val id = m["category_id"] ?: return@streamList null
            Category(id, (m["category_name"] ?: "").trim(), kind)
        }
    }

    suspend fun liveStreams(): List<LiveChannel> = withContext(Dispatchers.IO) {
        var n = 0
        streamList(apiUrl("get_live_streams")) { m ->
            n++
            val id = m["stream_id"] ?: return@streamList null
            LiveChannel(
                id = id,
                name = (m["name"] ?: "").trim(),
                icon = m["stream_icon"].httpOrNull(),
                categoryId = m["category_id"] ?: "",
                num = m["num"]?.toIntOrNull() ?: n,
            )
        }
    }

    suspend fun movies(): List<Movie> = withContext(Dispatchers.IO) {
        streamList(apiUrl("get_vod_streams")) { m ->
            val id = m["stream_id"] ?: return@streamList null
            Movie(
                id = id,
                name = (m["name"] ?: "").trim(),
                poster = m["stream_icon"].httpOrNull(),
                categoryId = m["category_id"] ?: "",
                added = m["added"]?.toLongOrNull() ?: 0L,
                rating = m["rating"]?.toDoubleOrNull() ?: 0.0,
                ext = m["container_extension"]?.takeIf { it.isNotBlank() } ?: "mp4",
            )
        }
    }

    suspend fun series(): List<Series> = withContext(Dispatchers.IO) {
        streamList(apiUrl("get_series")) { m ->
            val id = m["series_id"] ?: return@streamList null
            Series(
                id = id,
                name = (m["name"] ?: "").trim(),
                poster = m["cover"].httpOrNull(),
                backdrop = m["backdrop_path"].httpOrNull(),
                categoryId = m["category_id"] ?: "",
                updated = m["last_modified"]?.toLongOrNull() ?: 0L,
                rating = m["rating"]?.toDoubleOrNull() ?: 0.0,
                plot = m["plot"]?.takeIf { it.isNotBlank() },
            )
        }
    }

    suspend fun movieDetail(movie: Movie): MovieDetail = withContext(Dispatchers.IO) {
        val json = getObject(apiUrl("get_vod_info", "&vod_id=${movie.id}"))
        val info = json.optJSONObject("info") ?: JSONObject()
        val data = json.optJSONObject("movie_data")
        MovieDetail(
            name = info.str("name") ?: movie.name,
            plot = info.str("plot") ?: info.str("description"),
            backdrop = info.firstUrl("backdrop_path") ?: info.str("cover_big").httpOrNull(),
            poster = info.str("movie_image").httpOrNull() ?: movie.poster,
            genre = info.str("genre"),
            year = (info.str("releasedate") ?: info.str("release_date"))?.take(4),
            rating = info.str("rating"),
            durationSecs = info.optString("duration_secs").toIntOrNull() ?: 0,
            ext = data?.str("container_extension") ?: movie.ext,
        )
    }

    suspend fun seriesDetail(seriesId: String, series: Series?): SeriesDetail = withContext(Dispatchers.IO) {
        val json = getObject(apiUrl("get_series_info", "&series_id=$seriesId"))
        val info = json.optJSONObject("info") ?: JSONObject()
        val seasons = sortedMapOf<Int, MutableList<Episode>>()

        fun addEpisode(o: JSONObject, fallbackSeason: Int) {
            val id = o.str("id") ?: return
            val epInfo = o.optJSONObject("info")
            val season = o.optString("season").toIntOrNull() ?: fallbackSeason
            val ep = Episode(
                id = id,
                season = season,
                num = o.optString("episode_num").toIntOrNull() ?: 0,
                title = o.str("title") ?: "Episodio",
                ext = o.str("container_extension") ?: "mp4",
                image = epInfo?.str("movie_image").httpOrNull(),
                plot = epInfo?.str("plot"),
                durationSecs = epInfo?.optString("duration_secs")?.toIntOrNull() ?: 0,
            )
            seasons.getOrPut(season) { mutableListOf() } += ep
        }

        when (val eps = json.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { k ->
                val arr = eps.optJSONArray(k) ?: return@forEach
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { addEpisode(it, k.toIntOrNull() ?: 1) }
            }
            is JSONArray -> for (i in 0 until eps.length()) {
                val arr = eps.optJSONArray(i) ?: continue
                for (j in 0 until arr.length()) arr.optJSONObject(j)?.let { addEpisode(it, i + 1) }
            }
        }
        seasons.values.forEach { list -> list.sortBy { it.num } }

        SeriesDetail(
            series = series,
            name = info.str("name") ?: series?.name ?: "",
            plot = info.str("plot") ?: series?.plot,
            backdrop = info.firstUrl("backdrop_path") ?: series?.backdrop,
            poster = info.str("cover").httpOrNull() ?: series?.poster,
            genre = info.str("genre"),
            year = (info.str("releaseDate") ?: info.str("release_date"))?.take(4),
            rating = info.str("rating"),
            seasons = seasons,
        )
    }

    fun liveUrl(id: String, ext: String) = "$baseUrl/live/${enc(username)}/${enc(password)}/$id.$ext"
    fun movieUrl(id: String, ext: String) = "$baseUrl/movie/${enc(username)}/${enc(password)}/$id.$ext"
    fun episodeUrl(id: String, ext: String) = "$baseUrl/series/${enc(username)}/${enc(password)}/$id.$ext"

    private fun enc(s: String) = Uri.encode(s)

    companion object {
        const val USER_AGENT = "MiTV/2.0 (Android TV)"

        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return s
        }

        fun friendlyError(e: Throwable): String = when (e) {
            is XtreamException -> e.message ?: "Error del servidor"
            is UnknownHostException -> "No se encontró el servidor. Revisa la dirección y tu internet."
            is SocketTimeoutException -> "El servidor no respondió a tiempo. Intenta de nuevo."
            is SSLException -> "Falló la conexión segura. Prueba cambiando https:// por http:// (o al revés)."
            is IOException -> "No se pudo conectar: ${e.message ?: "error de red"}. Revisa dirección y puerto."
            else -> "Error: ${e.message ?: e.javaClass.simpleName}"
        }

        /** Saca servidor, usuario y contraseña de un enlace get.php, /playlist/U/P/... o /live/U/P/... */
        fun parseProviderLink(link: String): Triple<String, String, String>? {
            val uri = Uri.parse(link.trim())
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val server = if (uri.port > 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
            val user = uri.getQueryParameter("username")
            val pass = uri.getQueryParameter("password")
            if (!user.isNullOrBlank() && !pass.isNullOrBlank()) return Triple(server, user, pass)
            val seg = uri.pathSegments
            val i = seg.indexOfFirst { it == "playlist" || it == "live" || it == "movie" || it == "series" }
            if (i >= 0 && seg.size >= i + 3) return Triple(server, seg[i + 1], seg[i + 2])
            return null
        }
    }
}

private fun String?.httpOrNull(): String? = this?.trim()?.takeIf { it.startsWith("http") }

private fun JSONObject.str(key: String): String? =
    if (isNull(key)) null else optString(key, "").trim().takeIf { it.isNotEmpty() && it != "null" }

private fun JSONObject.firstUrl(key: String): String? = when (val v = opt(key)) {
    is JSONArray -> (0 until v.length()).asSequence().map { v.optString(it) }.firstOrNull { it.startsWith("http") }
    is String -> v.httpOrNull()
    else -> null
}
