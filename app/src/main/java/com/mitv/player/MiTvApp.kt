package com.mitv.player

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/** Guarda cualquier cierre inesperado para mostrarlo la próxima vez que se abra la app. */
class MiTvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = "Mi TV ${BuildConfigInfo.version(this)} · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n" +
                    sw.toString().lineSequence().take(25).joinToString("\n")
                getSharedPreferences("crash", Context.MODE_PRIVATE).edit().putString("last", text).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        /** El último error guardado (y lo borra para no mostrarlo dos veces). */
        fun takeLastCrash(ctx: Context): String? {
            val sp = ctx.getSharedPreferences("crash", Context.MODE_PRIVATE)
            val text = sp.getString("last", null) ?: return null
            sp.edit().remove("last").apply()
            return text
        }
    }
}

object BuildConfigInfo {
    fun version(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull() ?: "?"
}
