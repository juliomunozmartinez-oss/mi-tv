package com.mitv.player

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class Category(val id: String, val name: String)

data class Channel(
    val id: String,
    val name: String,
    val icon: String?,
    val categoryId: String,
    val num: Int,
)

data class AccountInfo(val status: String, val expDate: Long?, val maxConnections: String)

class XtreamException(message: String) : Exception(message)

/** Habla con un servidor tipo Xtream Codes (player_api.php). */
class XtreamClient(server: String, private val username: String, private val password: String) {

    val baseUrl: String = normalizeServer(server)

    private fun apiUrl(action: String?): String {
        val sb = StringBuilder("$baseUrl/player_api.php?username=")
            .append(Uri.encode(username))
            .append("&password=")
            .append(Uri.encode(password))
        if (action != null) sb.append("&action=").append(action)
        return sb.toString()
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code == 401 || code == 403) throw XtreamException("El servidor rechazó la cuenta (código $code)")
            if (code !in 200..299) throw XtreamException("El servidor respondió con el código $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun getArray(url: String): JSONArray {
        val body = get(url).trim()
        return try {
            if (body.isEmpty() || body == "null") JSONArray() else JSONArray(body)
        } catch (e: JSONException) {
            throw XtreamException("Respuesta inesperada del servidor")
        }
    }

    suspend fun login(): AccountInfo = withContext(Dispatchers.IO) {
        val json = try {
            JSONObject(get(apiUrl(null)))
        } catch (e: JSONException) {
            throw XtreamException("Respuesta inesperada del servidor. ¿La dirección es correcta?")
        }
        val ui = json.optJSONObject("user_info")
            ?: throw XtreamException("Usuario o contraseña incorrectos")
        if (ui.optInt("auth", 0) != 1) throw XtreamException("Usuario o contraseña incorrectos")
        AccountInfo(
            status = ui.optString("status", ""),
            expDate = ui.optString("exp_date", "").toLongOrNull(),
            maxConnections = ui.optString("max_connections", ""),
        )
    }

    suspend fun liveCategories(): List<Category> = withContext(Dispatchers.IO) {
        val arr = getArray(apiUrl("get_live_categories"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Category(o.optString("category_id"), o.optString("category_name").trim())
        }
    }

    suspend fun liveStreams(): List<Channel> = withContext(Dispatchers.IO) {
        val arr = getArray(apiUrl("get_live_streams"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("stream_id")
            if (id.isEmpty()) return@mapNotNull null
            Channel(
                id = id,
                name = o.optString("name").trim(),
                icon = o.optString("stream_icon").takeIf { it.startsWith("http") },
                categoryId = o.optString("category_id"),
                num = o.optInt("num", i + 1),
            )
        }
    }

    /** Dirección del video de un canal en vivo. ext = "ts" o "m3u8". */
    fun liveUrl(channel: Channel, ext: String): String =
        "$baseUrl/live/${Uri.encode(username)}/${Uri.encode(password)}/${channel.id}.$ext"

    companion object {
        const val USER_AGENT = "MiTV/1.0 (Android TV)"

        fun normalizeServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
            return s
        }

        /** Convierte un error técnico en un mensaje entendible. */
        fun friendlyError(e: Throwable): String = when (e) {
            is XtreamException -> e.message ?: "Error del servidor"
            is UnknownHostException -> "No se encontró el servidor. Revisa la dirección y tu internet."
            is SocketTimeoutException -> "El servidor no respondió a tiempo. Intenta de nuevo."
            is SSLException -> "Falló la conexión segura. Prueba cambiando https:// por http:// (o al revés)."
            is IOException -> "No se pudo conectar: ${e.message ?: "error de red"}. Revisa dirección y puerto."
            else -> "Error: ${e.message ?: e.javaClass.simpleName}"
        }

        /**
         * Saca servidor, usuario y contraseña de un enlace del proveedor:
         *  - https://host:puerto/get.php?username=U&password=P&...
         *  - https://host:puerto/playlist/U/P/m3u_plus
         *  - https://host:puerto/live/U/P/123.ts
         */
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
