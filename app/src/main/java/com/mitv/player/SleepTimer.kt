package com.mitv.player

/**
 * Temporizador para dormir. Se activa desde la barra de arriba (🌙) y cuenta
 * sin importar en qué pantalla estén; el reproductor lo revisa y detiene todo al cumplirse.
 */
object SleepTimer {
    /** Hora (ms) en que se detiene; 0 = apagado. */
    var deadline: Long = 0
        private set

    /** Detenerse al terminar el episodio o película actual. */
    var afterCurrent: Boolean = false
        private set

    val active get() = deadline > 0 || afterCurrent

    fun setMinutes(min: Int) {
        deadline = System.currentTimeMillis() + min * 60_000L
        afterCurrent = false
    }

    fun setAfterCurrent() {
        deadline = 0
        afterCurrent = true
    }

    fun cancel() {
        deadline = 0
        afterCurrent = false
    }

    fun remainingMs(): Long = if (deadline > 0) deadline - System.currentTimeMillis() else Long.MAX_VALUE

    fun label(): String = when {
        afterCurrent -> "🌙 Al terminar"
        deadline > 0 -> "🌙 ${((remainingMs() + 59_999) / 60_000).coerceAtLeast(0)} min"
        else -> "🌙"
    }

    /** Muestra las opciones del temporizador. */
    fun showDialog(activity: android.app.Activity, onChange: () -> Unit) {
        val options = arrayOf("30 minutos", "60 minutos", "90 minutos", "Al terminar lo que estoy viendo", "Apagar temporizador")
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(if (active) "Temporizador: ${label().removePrefix("🌙 ")}" else "¿Cuándo detener la reproducción?")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> setMinutes(30)
                    1 -> setMinutes(60)
                    2 -> setMinutes(90)
                    3 -> setAfterCurrent()
                    else -> cancel()
                }
                onChange()
            }
            .show()
    }
}
