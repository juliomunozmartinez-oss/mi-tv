package com.mitv.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * Guía de programación: un renglón por canal con sus programas (lo que ya pasó, lo de ahora y lo que sigue).
 * La programación de cada canal se pide solo cuando su renglón aparece en pantalla.
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var profile: Profile
    private lateinit var client: XtreamClient
    private var channels: List<LiveChannel> = emptyList()
    private val epg = HashMap<String, List<EpgEntry>>()
    private val loading = HashSet<String>()
    private val limiter = Semaphore(4)
    private val holders = HashMap<String, Int>()
    private val dayFmt = SimpleDateFormat("EEE d", Locale("es", "MX"))
    private val timeFmt = SimpleDateFormat("HH:mm", Locale("es", "MX"))
    private lateinit var list: RecyclerView

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
        findViewById<TextView>(R.id.now).text = "Hoy " + timeFmt.format(Date())
        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null

        lifecycleScope.launch {
            channels = withContext(Dispatchers.Default) {
                val v = CatalogView(prefs, profile)
                v.liveCats.flatMap { v.liveIn(it.id) }.distinctBy { it.id }
            }
            findViewById<View>(R.id.loading).visibility = View.GONE
            list.adapter = Adapter()
            list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
        }
    }

    private fun t(sec: Long) = timeFmt.format(Date(sec * 1000))

    /** Programas de ayer a mañana, alrededor de ahora. */
    private fun window(all: List<EpgEntry>): List<EpgEntry> {
        val now = System.currentTimeMillis() / 1000
        return all.filter { it.end > now - 24 * 3600 && it.start < now + 24 * 3600 }
    }

    private fun requestEpg(ch: LiveChannel) {
        if (ch.id in epg || !loading.add(ch.id)) return
        lifecycleScope.launch {
            val result = limiter.withPermit { Catalog.fullEpgFor(client, ch.id) }
            epg[ch.id] = window(result)
            loading.remove(ch.id)
            holders[ch.id]?.let { pos -> list.adapter?.notifyItemChanged(pos) }
        }
    }

    private fun play(ch: LiveChannel) {
        Nav.playLive(this, profile, channels, channels.indexOfFirst { it.id == ch.id }.coerceAtLeast(0))
    }

    private fun onProgram(ch: LiveChannel, e: EpgEntry) {
        val now = System.currentTimeMillis() / 1000
        val url = if (e.hasArchive) client.catchupUrl(ch.id, e) else null
        when {
            e.start <= now && e.end > now -> {
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
            e.end <= now -> {
                if (url != null) Nav.playUrl(this, profile, url, "${ch.name} · ${e.title}")
                else Toast.makeText(this, "Este programa ya pasó y el canal no lo tiene grabado", Toast.LENGTH_LONG).show()
            }
            else -> Toast.makeText(this, "«${e.title}» empieza a las ${t(e.start)}", Toast.LENGTH_SHORT).show()
        }
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val channelCell: View = v.findViewById(R.id.channelCell)
            val logo: ImageView = v.findViewById(R.id.logo)
            val name: TextView = v.findViewById(R.id.name)
            val programs: RowRecyclerView = v.findViewById(R.id.programs)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val vh = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_guide_row, parent, false))
            vh.programs.layoutManager = LinearLayoutManager(parent.context, LinearLayoutManager.HORIZONTAL, false)
            vh.programs.itemAnimator = null
            Ui.scaleOnFocus(vh.channelCell, 1.04f)
            return vh
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val ch = channels[position]
            holders.entries.removeAll { it.value == position }
            holders[ch.id] = position
            holder.name.text = ch.name
            holder.logo.load(ch.icon) { error(R.drawable.ic_launcher); fallback(R.drawable.ic_launcher) }
            holder.channelCell.setOnClickListener { play(ch) }

            val entries = epg[ch.id]
            if (entries == null) requestEpg(ch)
            val now = System.currentTimeMillis() / 1000
            val nowIndex = entries?.indexOfFirst { it.start <= now && it.end > now }?.takeIf { it >= 0 } ?: 0
            // Un «Row» solo para que la fila recuerde/arranque en el programa de ahora.
            val memory = Row(ch.name, emptyList(), false).apply { focusPos = nowIndex }
            holder.programs.row = memory
            holder.programs.adapter = Programs(ch, entries)
            (holder.programs.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(nowIndex, 0)
        }

        override fun getItemCount() = channels.size
    }

    private inner class Programs(
        private val ch: LiveChannel,
        private val entries: List<EpgEntry>?,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = if (entries.isNullOrEmpty()) 1 else entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_guide_cell, parent, false)
            Ui.scaleOnFocus(v, 1.04f)
            return object : RecyclerView.ViewHolder(v) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val v = holder.itemView
            val time = v.findViewById<TextView>(R.id.time)
            val title = v.findViewById<TextView>(R.id.title)
            val nowBar = v.findViewById<View>(R.id.nowBar)
            if (entries.isNullOrEmpty()) {
                time.text = if (entries == null) "Cargando…" else ""
                title.text = if (entries == null) "" else "Sin guía · OK para ver el canal"
                nowBar.visibility = View.GONE
                v.alpha = 1f
                v.setOnClickListener { play(ch) }
                return
            }
            val e = entries[position]
            val now = System.currentTimeMillis() / 1000
            val isNow = e.start <= now && e.end > now
            val past = e.end <= now
            val day = if (Date(e.start * 1000).let { dayFmt.format(it) } != dayFmt.format(Date())) dayFmt.format(Date(e.start * 1000)) + " · " else ""
            time.text = buildString {
                append(day).append(t(e.start)).append(" – ").append(t(e.end))
                if (isNow) append("  ·  AHORA")
                if (past && e.hasArchive) append("  ·  ⟲ grabado")
            }
            title.text = e.title
            nowBar.visibility = if (isNow) View.VISIBLE else View.GONE
            v.alpha = if (past && !e.hasArchive) 0.5f else 1f
            v.setOnClickListener { onProgram(ch, e) }
        }
    }
}
