package com.mitv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Guía de programación en cuadrícula: canales hacia abajo, horas hacia la derecha,
 * cada programa del ancho de lo que dura y una línea roja de «ahora».
 * Las teclas se manejan aquí (◀ ▶ horario, ▲ ▼ canal); la programación de cada canal
 * se pide solo cuando su renglón aparece en pantalla.
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var profile: Profile
    private lateinit var client: XtreamClient
    private var channels: List<LiveChannel> = emptyList()
    private val epg = HashMap<String, List<EpgEntry>>()
    private val loading = HashSet<String>()
    private val limiter = Semaphore(4)

    private lateinit var list: RecyclerView
    private lateinit var hours: FrameLayout
    private lateinit var nowLine: View
    private lateinit var selTimeV: TextView
    private lateinit var selTitleV: TextView
    private lateinit var selDescV: TextView
    private lateinit var dayLabel: TextView

    private val timeFmt = SimpleDateFormat("HH:mm", Locale("es", "MX"))
    private val dayFmt = SimpleDateFormat("EEEE d", Locale("es", "MX"))

    private var pxPerMin = 0f
    private var timelineWidth = 0
    private var viewStart = 0L      // segundos (epoch) en el borde izquierdo
    private var viewSpan = 3 * 3600L
    private var selRow = 0
    private var selTime = 0L

    private val now get() = System.currentTimeMillis() / 1000
    private val viewEnd get() = viewStart + viewSpan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Profiles.byId(this, intent.getStringExtra(Nav.EXTRA_PROFILE))
        if (p == null) {
            finish()
            return
        }
        profile = p
        val prefs = Prefs(this)
        client = prefs.client()
        setContentView(R.layout.activity_guide)
        list = findViewById(R.id.list)
        hours = findViewById(R.id.hours)
        nowLine = findViewById(R.id.nowLine)
        selTimeV = findViewById(R.id.selTime)
        selTitleV = findViewById(R.id.selTitle)
        selDescV = findViewById(R.id.selDesc)
        dayLabel = findViewById(R.id.dayLabel)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null

        pxPerMin = 4 * resources.displayMetrics.density
        selTime = now
        viewStart = (now / 1800) * 1800 - 1800

        lifecycleScope.launch {
            channels = withContext(Dispatchers.Default) {
                val v = CatalogView(prefs, profile)
                v.liveCats.flatMap { v.liveIn(it.id) }.distinctBy { it.id }
            }
            findViewById<View>(R.id.loading).visibility = View.GONE
            list.adapter = Adapter()
            list.post {
                timelineWidth = list.width - list.paddingStart - (210 * resources.displayMetrics.density).toInt()
                viewSpan = (timelineWidth / pxPerMin * 60).toLong()
                renderAll()
            }
        }
    }

    // ---------- Dibujo ----------

    private fun x(t: Long): Float = (t - viewStart) / 60f * pxPerMin

    private fun renderAll() {
        drawHours()
        list.adapter?.notifyDataSetChanged()
        updateInfo()
    }

    private fun drawHours() {
        hours.removeAllViews()
        var t = ((viewStart + 1799) / 1800) * 1800
        while (t < viewEnd) {
            val tv = TextView(this).apply {
                text = timeFmt.format(Date(t * 1000))
                setTextColor(getColor(R.color.text_dim))
                textSize = 13f
            }
            hours.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                leftMargin = x(t).toInt()
            })
            t += 1800
        }
        dayLabel.text = dayFmt.format(Date(selTime * 1000)).replaceFirstChar { it.uppercase() }
        // Línea roja de «ahora».
        val nx = x(now)
        if (nx in 0f..timelineWidth.toFloat()) {
            nowLine.visibility = View.VISIBLE
            nowLine.translationX = list.left + list.paddingStart + 210 * resources.displayMetrics.density + nx
        } else {
            nowLine.visibility = View.GONE
        }
    }

    private fun entriesOf(row: Int): List<EpgEntry>? = channels.getOrNull(row)?.let { epg[it.id] }

    private fun selectedEntry(): EpgEntry? = entriesOf(selRow)?.firstOrNull { selTime >= it.start && selTime < it.end }

    private fun updateInfo() {
        val ch = channels.getOrNull(selRow) ?: return
        val e = selectedEntry()
        if (e == null) {
            selTimeV.text = ch.name
            selTitleV.text = if (epg[ch.id] == null) "Cargando programación…" else "Sin información de este horario"
            selDescV.text = "OK para ver el canal"
            return
        }
        val state = when {
            e.start <= now && e.end > now -> "AHORA"
            e.end <= now -> if (e.hasArchive) "YA PASÓ · ⟲ se puede ver" else "YA PASÓ"
            Reminders.has(this, ch.id, e.start) -> "⏰ CON RECORDATORIO"
            else -> "MÁS TARDE · OK para recordarte"
        }
        selTimeV.text = "${ch.name}  ·  ${timeFmt.format(Date(e.start * 1000))} – ${timeFmt.format(Date(e.end * 1000))}  ·  $state"
        selTitleV.text = e.title
        selDescV.text = e.description ?: ""
    }

    /** Avisar que cambió una fila sin chocar con el RecyclerView mientras acomoda o se desplaza. */
    private fun safeNotify(pos: Int) {
        if (pos < 0) return
        if (list.isComputingLayout) {
            list.post { safeNotify(pos) }
        } else {
            list.adapter?.notifyItemChanged(pos)
        }
    }

    private fun requestEpg(ch: LiveChannel, pos: Int) {
        if (ch.id in epg || !loading.add(ch.id)) return
        lifecycleScope.launch {
            val result = limiter.withPermit { Catalog.fullEpgFor(client, ch.id) }
            val t = now
            epg[ch.id] = result.filter { it.end > t - 24 * 3600 && it.start < t + 24 * 3600 }
            loading.remove(ch.id)
            safeNotify(pos)
            if (pos == selRow) updateInfo()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val channelCell: View = v.findViewById(R.id.channelCell)
            val logo: ImageView = v.findViewById(R.id.logo)
            val name: TextView = v.findViewById(R.id.name)
            val timeline: FrameLayout = v.findViewById(R.id.timeline)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false))

        override fun getItemCount() = channels.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = channels[position]
            val selectedRow = position == selRow
            holder.name.text = ch.name
            holder.logo.load(ch.icon) { error(R.drawable.ic_launcher); fallback(R.drawable.ic_launcher) }
            holder.channelCell.alpha = if (selectedRow) 1f else 0.8f
            holder.timeline.removeAllViews()
            val entries = epg[ch.id]
            if (entries == null) requestEpg(ch, position)
            val gap = (3 * resources.displayMetrics.density).toInt()
            val visible = entries.orEmpty().filter { it.end > viewStart && it.start < viewEnd }
            if (visible.isEmpty()) {
                holder.timeline.addView(
                    cell(if (entries == null) "Cargando…" else "Sin guía · OK para ver el canal", selectedRow, dim = false),
                    FrameLayout.LayoutParams(timelineWidth - gap, ViewGroup.LayoutParams.MATCH_PARENT),
                )
                return
            }
            for (e in visible) {
                val left = x(maxOf(e.start, viewStart))
                val right = x(minOf(e.end, viewEnd))
                val width = (right - left).toInt() - gap
                if (width <= 0) continue
                val past = e.end <= now
                val mark = when {
                    past && e.hasArchive -> "⟲ "
                    Reminders.has(this@GuideActivity, ch.id, e.start) -> "⏰ "
                    else -> ""
                }
                val sel = selectedRow && selTime >= e.start && selTime < e.end
                holder.timeline.addView(
                    cell(mark + e.title, sel, dim = past && !e.hasArchive, live = e.start <= now && e.end > now),
                    FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = left.toInt() },
                )
            }
        }
    }

    private fun cell(text: String, selected: Boolean, dim: Boolean, live: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((10 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt(), 0)
            textSize = 14f
            setTypeface(typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(if (selected) getColor(R.color.bg) else getColor(R.color.text))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8 * resources.displayMetrics.density
                setColor(
                    when {
                        selected -> getColor(R.color.text)
                        live -> 0xFF1E3A8A.toInt()
                        else -> getColor(R.color.surface_hi)
                    }
                )
            }
            alpha = if (dim && !selected) 0.5f else 1f
        }

    // ---------- Control remoto ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (channels.isEmpty() || timelineWidth <= 0) return super.dispatchKeyEvent(event)
        val k = event.keyCode
        val handled = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN ||
            k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT ||
            k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveProgram(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveProgram(1)
            else -> if (event.repeatCount == 0) onSelect()
        }
        return true
    }

    private fun moveRow(step: Int) {
        val target = (selRow + step).coerceIn(0, channels.size - 1)
        if (target == selRow) return
        val old = selRow
        selRow = target
        safeNotify(old)
        safeNotify(selRow)
        list.scrollToPosition(selRow)
        updateInfo()
    }

    private fun moveProgram(dir: Int) {
        val entries = entriesOf(selRow).orEmpty()
        val cur = entries.indexOfFirst { selTime >= it.start && selTime < it.end }
        val next: EpgEntry? = when {
            cur >= 0 -> entries.getOrNull(cur + dir)
            dir > 0 -> entries.firstOrNull { it.start > selTime }
            else -> entries.lastOrNull { it.end <= selTime }
        }
        val newTime = next?.start ?: (selTime + dir * 1800)
        selTime = newTime.coerceIn(now - 24 * 3600, now + 24 * 3600)
        // Mover la cuadrícula si el programa quedó fuera de la vista.
        val start = next?.start ?: selTime
        val end = next?.end ?: (selTime + 1800)
        val margin = 10 * 60
        if (end <= viewStart + margin || start >= viewEnd - margin) {
            viewStart = if (dir > 0) (start / 1800) * 1800 - 1800 else ((end + 1799) / 1800) * 1800 - viewSpan + 1800
            renderAll()
        } else {
            safeNotify(selRow)
            updateInfo()
        }
    }

    private fun play(ch: LiveChannel) {
        Nav.playLive(this, profile, channels, channels.indexOfFirst { it.id == ch.id }.coerceAtLeast(0))
    }

    private fun onSelect() {
        val ch = channels.getOrNull(selRow) ?: return
        val e = selectedEntry()
        if (e == null) {
            play(ch)
            return
        }
        val t = now
        val url = if (e.hasArchive) client.catchupUrl(ch.id, e) else null
        when {
            e.start <= t && e.end > t -> {
                if (url == null) {
                    play(ch)
                } else {
                    AlertDialog.Builder(this)
                        .setTitle(e.title)
                        .setItems(arrayOf("▶  Ver en vivo", "↺  Ver desde el inicio")) { _, which ->
                            if (which == 0) play(ch) else Nav.playUrl(this, profile, url, "${ch.name} · ${e.title}")
                        }
                        .show()
                }
            }
            e.end <= t -> {
                if (url != null) Nav.playUrl(this, profile, url, "${ch.name} · ${e.title}")
                else Toast.makeText(this, "Este programa ya pasó y el canal no lo tiene grabado", Toast.LENGTH_LONG).show()
            }
            else -> {
                val on = Reminders.toggle(this, Reminders.Item(ch.id, ch.name, e.title, e.start))
                Toast.makeText(
                    this,
                    if (on) "⏰ Te aviso 2 minutos antes de «${e.title}»" else "Recordatorio quitado",
                    Toast.LENGTH_LONG,
                ).show()
                safeNotify(selRow)
                updateInfo()
            }
        }
    }
}
