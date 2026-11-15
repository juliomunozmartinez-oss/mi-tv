package com.mitv.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.Normalizer

object Ui {
    fun scaleOnFocus(v: View, scale: Float = 1.08f) {
        v.setOnFocusChangeListener { view, has ->
            val s = if (has) scale else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(140).start()
            view.translationZ = if (has) 8f else 0f
        }
    }

    fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    fun rounded(view: View) {
        view.clipToOutline = true
    }

    /** Pide el PIN si está activado; si no, sigue directo. */
    fun askPin(activity: Activity, prefs: Prefs, onOk: () -> Unit) {
        if (!prefs.pinEnabled || prefs.pin.length != 4) {
            onOk()
            return
        }
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            hint = "PIN"
            textSize = 24f
        }
        AlertDialog.Builder(activity)
            .setTitle("Escribe el PIN")
            .setView(input)
            .setPositiveButton("Entrar") { _, _ ->
                if (input.text.toString() == prefs.pin) onOk()
                else Toast.makeText(activity, "PIN incorrecto", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
        input.requestFocus()
    }

    fun duration(secs: Int): String? {
        if (secs <= 0) return null
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "$h h $m min" else "$m min"
    }

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun ratingText(r: String?): String? {
        val v = r?.toDoubleOrNull() ?: return null
        if (v <= 0) return null
        return "★ " + String.format(java.util.Locale.US, "%.1f", v)
    }
}

/** Abre las pantallas de la app. */
object Nav {
    const val EXTRA_PROFILE = "profile"
    const val EXTRA_KIND = "kind"
    const val EXTRA_ID = "id"
    const val EXTRA_EPISODE = "episode"
    const val EXTRA_START = "start"
    const val EXTRA_INDEX = "index"
    const val EXTRA_URL = "url"
    const val EXTRA_TITLE = "title"

    fun home(ctx: Context, profile: Profile) =
        ctx.startActivity(Intent(ctx, HomeActivity::class.java).putExtra(EXTRA_PROFILE, profile.id))

    fun detail(ctx: Context, profile: Profile, kind: Kind, id: String) =
        ctx.startActivity(
            Intent(ctx, DetailActivity::class.java)
                .putExtra(EXTRA_PROFILE, profile.id).putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_ID, id)
        )

    /** Reproduce una dirección directa (programa grabado de la guía). */
    fun playUrl(ctx: Context, profile: Profile, url: String, title: String) =
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_PROFILE, profile.id).putExtra(EXTRA_KIND, Kind.LIVE.name)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_TITLE, title)
        )

    fun playLive(ctx: Context, profile: Profile, channels: List<LiveChannel>, index: Int) {
        if (channels.isEmpty()) return
        Catalog.liveQueue = channels
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_PROFILE, profile.id).putExtra(EXTRA_KIND, Kind.LIVE.name)
                .putExtra(EXTRA_INDEX, index)
        )
    }

    fun playMovie(ctx: Context, profile: Profile, movieId: String, startMs: Long) =
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_PROFILE, profile.id).putExtra(EXTRA_KIND, Kind.MOVIE.name)
                .putExtra(EXTRA_ID, movieId).putExtra(EXTRA_START, startMs)
        )

    fun playEpisode(ctx: Context, profile: Profile, seriesId: String, episodeId: String, startMs: Long) =
        ctx.startActivity(
            Intent(ctx, PlayerActivity::class.java)
                .putExtra(EXTRA_PROFILE, profile.id).putExtra(EXTRA_KIND, Kind.SERIES.name)
                .putExtra(EXTRA_ID, seriesId).putExtra(EXTRA_EPISODE, episodeId).putExtra(EXTRA_START, startMs)
        )

    /** Abre la sección completa de una fila en cuadrícula. */
    fun openGrid(ctx: Context, profile: Profile, row: Row) {
        GridActivity.pending = row
        ctx.startActivity(Intent(ctx, GridActivity::class.java).putExtra(EXTRA_PROFILE, profile.id))
    }

    /** Abre lo que representa una tarjeta. «Seguir viendo» reproduce directo. */
    fun open(ctx: Context, profile: Profile, row: Row, index: Int) {
        val card = row.cards.getOrNull(index) ?: return
        val r = card.resume
        if (card.more) {
            openGrid(ctx, profile, row)
            return
        }
        when {
            card.kind == Kind.LIVE -> {
                val channels = row.cards.filter { it.kind == Kind.LIVE }.mapNotNull { Catalog.liveById[it.id] }
                val i = channels.indexOfFirst { it.id == card.id }.coerceAtLeast(0)
                playLive(ctx, profile, channels, i)
            }
            r != null && r.kind == Kind.MOVIE -> playMovie(ctx, profile, r.id, r.positionMs)
            r != null && r.kind == Kind.SERIES && r.episodeId != null ->
                playEpisode(ctx, profile, r.id, r.episodeId, r.positionMs)
            else -> detail(ctx, profile, card.kind, card.id)
        }
    }
}
