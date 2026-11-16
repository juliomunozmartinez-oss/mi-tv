package com.mitv.player

import android.app.Application
import android.content.Context
import java.io.PrintWriter
import java.io.StringWriter

/** Guarda cualquier cierre inesperado para mostrarlo la próxima vez que se abra la app. */
class MiTvApp : Application() {
    /** Pantalla que se está viendo, para mostrar ahí los recordatorios. */
    private var current: android.app.Activity? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reminderCheck = object : Runnable {
        override fun run() {
            checkReminders()
            handler.postDelayed(this, 20_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: android.app.Activity) { current = a }
            override fun onActivityPaused(a: android.app.Activity) { if (current === a) current = null }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityStarted(a: android.app.Activity) {}
            override fun onActivityStopped(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        handler.postDelayed(reminderCheck, 20_000)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = "MuGa TV ${BuildConfigInfo.version(this)} · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n" +
                    sw.toString().lineSequence().take(25).joinToString("\n")
                getSharedPreferences("crash", Context.MODE_PRIVATE).edit().putString("last", text).commit()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Si algún programa con recordatorio empieza en 2 minutos, avisar en la pantalla actual. */
    private fun checkReminders() {
        val a = current ?: return
        if (a.isFinishing) return
        val due = Reminders.takeDue(this)
        for (r in due) {
            val minutes = ((r.start - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(0)
            val whenText = if (minutes > 0) "en $minutes min" else "ya empezó"
            androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("⏰ ${r.title}")
                .setMessage("${r.channelName} · $whenText")
                .setPositiveButton("Verlo ahora") { _, _ -> openChannel(a, r.channelId) }
                .setNegativeButton("Cerrar", null)
                .show()
        }
    }

    private fun openChannel(a: android.app.Activity, channelId: String) {
        val ch = Catalog.liveById[channelId]
        val profile = Profiles.byId(this, LastProfile.get(this)) ?: Profiles.all(this).firstOrNull()
        if (ch == null || profile == null) {
            android.widget.Toast.makeText(a, "Abre MuGa TV y entra a tu perfil para ver el canal", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val sameCategory = Catalog.live.filter { it.categoryId == ch.categoryId }
        Nav.playLive(a, profile, sameCategory, sameCategory.indexOfFirst { it.id == ch.id }.coerceAtLeast(0))
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
