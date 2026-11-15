package com.mitv.player

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualizaciones automáticas: revisa la última versión publicada en GitHub y, si es más nueva,
 * la descarga y abre el instalador de Android.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/juliomunozmartinez-oss/mi-tv/releases/latest"
    private const val CHECK_EVERY_MS = 3 * 60 * 60 * 1000L

    data class Release(val build: Int, val apkUrl: String, val sizeBytes: Long)

    private fun sp(ctx: Context) = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)

    /** Consulta GitHub. Regresa la versión si es más nueva que la instalada. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MugaTV-Updater")
            if (conn.responseCode !in 200..299) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = json.optString("tag_name").removePrefix("build-").toIntOrNull() ?: return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    val r = Release(build, a.optString("browser_download_url"), a.optLong("size"))
                    return@withContext if (r.build > BuildConfig.BUILD_NUMBER) r else null
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Revisión automática al abrir (como mucho cada 3 horas). */
    fun checkOnStart(activity: Activity, scope: LifecycleCoroutineScope) {
        val last = sp(activity).getLong("last_check", 0)
        if (System.currentTimeMillis() - last < CHECK_EVERY_MS) return
        sp(activity).edit().putLong("last_check", System.currentTimeMillis()).apply()
        scope.launch {
            val r = runCatching { check() }.getOrNull() ?: return@launch
            if (!activity.isFinishing) offer(activity, scope, r)
        }
    }

    /** Revisión a mano desde Ajustes. */
    fun checkNow(activity: Activity, scope: LifecycleCoroutineScope) {
        Toast.makeText(activity, "Buscando actualizaciones…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val r = runCatching { check() }
            when {
                r.isFailure -> Toast.makeText(activity, "No se pudo revisar. ¿Hay internet?", Toast.LENGTH_LONG).show()
                r.getOrNull() == null -> Toast.makeText(
                    activity, "Ya tienes la versión más nueva (build ${BuildConfig.BUILD_NUMBER}).", Toast.LENGTH_LONG,
                ).show()
                else -> offer(activity, scope, r.getOrNull()!!)
            }
        }
    }

    private fun offer(activity: Activity, scope: LifecycleCoroutineScope, r: Release) {
        val mb = if (r.sizeBytes > 0) " (%.0f MB)".format(r.sizeBytes / 1_048_576.0) else ""
        AlertDialog.Builder(activity)
            .setTitle("Hay una versión nueva de MuGa TV")
            .setMessage(
                "Tienes la build ${BuildConfig.BUILD_NUMBER} y ya está la build ${r.build}$mb.\n\n" +
                    "Se instala encima: no se pierden perfiles, listas ni «Seguir viendo»."
            )
            .setPositiveButton("Actualizar ahora") { _, _ -> download(activity, scope, r) }
            .setNegativeButton("Después", null)
            .show()
    }

    private fun download(activity: Activity, scope: LifecycleCoroutineScope, r: Release) {
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(48, 32, 48, 32)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Descargando actualización…")
            .setView(bar)
            .setCancelable(false)
            .show()
        scope.launch {
            val file = File(activity.cacheDir, "updates/MugaTV.apk")
            val ok = runCatching {
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    try {
                        val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.sizeBytes
                        conn.inputStream.use { input ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    done += n
                                    if (total > 0) {
                                        val pct = (done * 100 / total).toInt()
                                        withContext(Dispatchers.Main) { bar.progress = pct }
                                    }
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
            }.isSuccess
            dialog.dismiss()
            if (ok) install(activity, file)
            else Toast.makeText(activity, "No se pudo descargar la actualización. Intenta más tarde.", Toast.LENGTH_LONG).show()
        }
    }

    private fun install(activity: Activity, file: File) {
        // Android pide permiso una vez para que MuGa TV pueda instalar su propia actualización.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("Falta un permiso (solo la primera vez)")
                .setMessage(
                    "Activa «Permitir de esta fuente» para MuGa TV y luego vuelve a tocar «Actualizar ahora».\n\n" +
                        "Si no se abre solo: Ajustes → Apps → Seguridad y restricciones → Fuentes desconocidas → MuGa TV."
                )
                .setPositiveButton("Abrir ajustes") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                        )
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(activity, "Ábrelo desde Ajustes → Apps → Fuentes desconocidas", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Después", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, "No se pudo abrir el instalador", Toast.LENGTH_LONG).show()
        }
    }
}
