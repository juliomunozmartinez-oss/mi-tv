package com.mitv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV en vivo «sencilla», pensada para que cualquiera la use como una tele de siempre:
 * una sola lista grande con números, el canal se ve a la derecha mientras eliges,
 * OK lo pone en pantalla completa (ahí ▲ ▼ cambian de canal) y Atrás regresa a la lista.
 */
class LiveActivity : AppCompatActivity() {

    private sealed class Item {
        data class Header(val title: String) : Item()
        /** pos = lugar en la cola de canales (su número es pos + 1). */
        data class Channel(val ch: LiveChannel, val pos: Int, val dead: Boolean) : Item()
    }

    private lateinit var profile: Profile
    private lateinit var prefs: Prefs
    private lateinit var store: ProfileStore
    private lateinit var list: RecyclerView
    private lateinit var previewView: PlayerView
    private lateinit var previewMsg: TextView
    private lateinit var chName: TextView
    private lateinit var chNow: TextView
    private lateinit var chNext: TextView
    private lateinit var chProgress: ProgressBar

    private var items: List<Item> = emptyList()
    private var queue: List<LiveChannel> = emptyList()
    private val nowTitles = HashMap<String, String>()
    private var player: ExoPlayer? = null
    private var focusedPos = -1
    private var tunedId: String? = null
    private var epgJob: Job? = null
    private var longPressed = false
    private val handler = Handler(Looper.getMainLooper())
    private val tuneLater = Runnable { tunePreview() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Profiles.byId(this, intent.getStringExtra(Nav.EXTRA_PROFILE))
        if (p == null) {
            finish()
            return
        }
        profile = p
        prefs = Prefs(this)
        store = ProfileStore(this, p.id)
        setContentView(R.layout.activity_live)
        list = findViewById(R.id.channels)
        previewView = findViewById(R.id.preview)
        previewMsg = findViewById(R.id.previewMsg)
        chName = findViewById(R.id.chName)
        chNow = findViewById(R.id.chNow)
        chNext = findViewById(R.id.chNext)
        chProgress = findViewById(R.id.chProgress)
        previewView.useController = false
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        findViewById<View>(R.id.btnGuide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java).putExtra(Nav.EXTRA_PROFILE, profile.id))
        }
        load()
    }

    private fun load() {
        previewMsg.text = "Cargando canales…"
        lifecycleScope.launch {
            try {
                Catalog.ensureLoaded(prefs)
                val built = withContext(Dispatchers.Default) { build() }
                items = built.first
                queue = built.second
                if (queue.isEmpty()) {
                    previewMsg.text = if (profile.kids) "No hay canales infantiles todavía.\nUn adulto puede elegirlos en Ajustes."
                    else "No hay canales disponibles."
                    return@launch
                }
                list.adapter = Adapter()
                focusLastChannel()
            } catch (e: Exception) {
                previewMsg.text = "No se pudieron cargar los canales.\n\n${XtreamClient.friendlyError(e)}"
            }
        }
    }

    /** ⭐ Mis canales arriba; luego cada categoría (México primero), con los «sin señal» al final. */
    private fun build(): Pair<List<Item>, List<LiveChannel>> {
        val v = CatalogView(prefs, profile)
        val dead = DeadChannels.deadIds(this)
        val out = ArrayList<Item>()
        val q = ArrayList<LiveChannel>()
        fun section(title: String, chans: List<LiveChannel>) {
            if (chans.isEmpty()) return
            out += Item.Header(title)
            chans.forEach { ch ->
                out += Item.Channel(ch, q.size, ch.id in dead)
                q += ch
            }
        }
        val mine = store.myList.filter { it.startsWith("live:") && v.canSee(it) }
            .mapNotNull { Catalog.liveById[it.removePrefix("live:")] }
        section("⭐  Mis canales", mine)
        v.liveCats.forEach { c ->
            section(c.name, v.liveIn(c.id).sortedBy { if (it.id in dead) 1 else 0 })
        }
        return out to q
    }

    /** Empieza en el último canal que se vio (o en el primero). */
    private fun focusLastChannel() {
        val last = store.recentChannels().firstOrNull()
        var idx = items.indexOfFirst { it is Item.Channel && it.ch.id == last }
        if (idx < 0) idx = items.indexOfFirst { it is Item.Channel }
        if (idx < 0) return
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 160)
        list.post { list.layoutManager?.findViewByPosition(idx)?.requestFocus() }
    }

    private fun onChannelFocused(pos: Int) {
        val item = items.getOrNull(pos) as? Item.Channel ?: return
        focusedPos = pos
        val ch = item.ch
        chName.text = "${item.pos + 1}   ${ch.name}"
        chNow.text = if (item.dead) "Este canal no ha tenido señal últimamente" else "Buscando programación…"
        chNext.text = ""
        chProgress.visibility = View.GONE
        loadEpg(ch)
        handler.removeCallbacks(tuneLater)
        handler.postDelayed(tuneLater, 700)
    }

    private fun loadEpg(ch: LiveChannel) {
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            val epg = runCatching { Catalog.epgFor(prefs.client(), ch.id) }.getOrDefault(emptyList())
            val cur = items.getOrNull(focusedPos) as? Item.Channel ?: return@launch
            if (cur.ch.id != ch.id) return@launch
            val now = epg.firstOrNull()
            if (now == null) {
                if (!cur.dead) chNow.text = "Sin guía de programación"
                return@launch
            }
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es", "MX"))
            fun t(s: Long) = fmt.format(java.util.Date(s * 1000))
            chNow.text = "Ahora: ${now.title}   (${t(now.start)} – ${t(now.end)})"
            val total = (now.end - now.start).coerceAtLeast(1)
            val done = (System.currentTimeMillis() / 1000 - now.start).coerceIn(0, total)
            chProgress.progress = (done * 1000 / total).toInt()
            chProgress.visibility = View.VISIBLE
            chNext.text = epg.getOrNull(1)?.let { "Después: ${it.title}   (${t(it.start)})" } ?: ""
            if (nowTitles[ch.id] != now.title) {
                nowTitles[ch.id] = now.title
                list.adapter?.notifyItemChanged(focusedPos)
            }
        }
    }

    // ---------- Vista previa ----------

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(XtreamClient.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) previewMsg.visibility = View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                previewMsg.text = "Este canal no se puede ver ahorita"
                previewMsg.visibility = View.VISIBLE
            }
        })
        previewView.player = p
        player = p
        return p
    }

    private fun tunePreview() {
        val item = items.getOrNull(focusedPos) as? Item.Channel ?: return
        if (tunedId == item.ch.id && player != null) return
        tunedId = item.ch.id
        previewMsg.text = "Cargando…"
        previewMsg.visibility = View.VISIBLE
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(prefs.client().liveUrl(item.ch.id, "ts")))
        p.prepare()
        p.playWhenReady = true
    }

    private fun releasePlayer() {
        handler.removeCallbacks(tuneLater)
        previewView.player = null
        player?.release()
        player = null
        tunedId = null
    }

    private fun openFullScreen() {
        val item = items.getOrNull(focusedPos) as? Item.Channel ?: return
        releasePlayer()
        Nav.playLive(this, profile, queue, item.pos)
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    override fun onResume() {
        super.onResume()
        if (!::list.isInitialized || items.isEmpty()) return
        // Al volver de pantalla completa: quedarse en el canal que se estaba viendo.
        val last = store.recentChannels().firstOrNull()
        val idx = items.indexOfFirst { it is Item.Channel && it.ch.id == last }
        if (idx >= 0 && idx != focusedPos) {
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 160)
            list.post { list.layoutManager?.findViewByPosition(idx)?.requestFocus() }
        } else if (focusedPos >= 0) {
            handler.postDelayed(tuneLater, 400)
        }
    }

    // ---------- Teclas ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val center = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        val inList = currentFocus?.parent === list
        if (center && inList) {
            // Mantener OK = agregar o quitar de ⭐ Mis canales.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 1) {
                longPressed = true
                toggleFavorite()
                return true
            }
            if (event.action == KeyEvent.ACTION_UP) {
                if (longPressed) longPressed = false else openFullScreen()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleFavorite() {
        val item = items.getOrNull(focusedPos) as? Item.Channel ?: return
        val added = store.toggleList("live:${item.ch.id}")
        Toast.makeText(
            this,
            if (added) "⭐ ${item.ch.name} agregado a Mis canales" else "${item.ch.name} quitado de Mis canales",
            Toast.LENGTH_SHORT,
        ).show()
        val keepId = item.ch.id
        lifecycleScope.launch {
            val built = withContext(Dispatchers.Default) { build() }
            items = built.first
            queue = built.second
            list.adapter?.notifyDataSetChanged()
            // Seguir en el mismo canal (en su categoría, no en la copia de Mis canales).
            val idx = items.indexOfLast { it is Item.Channel && it.ch.id == keepId }
            if (idx >= 0) {
                focusedPos = idx
                (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 160)
                list.post { list.layoutManager?.findViewByPosition(idx)?.requestFocus() }
            }
        }
    }

    // ---------- Lista ----------

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 0) R.layout.item_live_header else R.layout.item_live_channel
            val v = LayoutInflater.from(parent.context).inflate(layout, parent, false)
            val vh = object : RecyclerView.ViewHolder(v) {}
            if (viewType == 1) {
                v.setOnFocusChangeListener { _, has ->
                    val pos = vh.bindingAdapterPosition
                    if (has && pos != RecyclerView.NO_POSITION) onChannelFocused(pos)
                }
            }
            return vh
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder.itemView as TextView).text = item.title
                is Item.Channel -> {
                    val v = holder.itemView
                    val fav = store.inList("live:${item.ch.id}")
                    v.findViewById<TextView>(R.id.num).text = (item.pos + 1).toString()
                    v.findViewById<TextView>(R.id.name).text = (if (fav) "⭐ " else "") + item.ch.name
                    v.findViewById<TextView>(R.id.sub).apply {
                        text = when {
                            item.dead -> "Sin señal"
                            else -> nowTitles[item.ch.id]?.let { "Ahora: $it" } ?: ""
                        }
                        visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
                    }
                    v.alpha = if (item.dead) 0.5f else 1f
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
