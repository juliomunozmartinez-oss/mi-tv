package com.mitv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * TV en vivo en tres columnas:
 *  1) categorías (Todos, ⭐ Favoritos, 🕘 Recientes y las del proveedor, México primero),
 *  2) los canales de esa categoría con lo que pasan ahora y lo que sigue,
 *  3) el canal en vivo mientras eliges.
 * ◀ ▶ pasan entre categorías y canales; OK pone el canal en pantalla completa.
 */
class LiveActivity : AppCompatActivity() {

    private class Group(val title: String, val channels: List<LiveChannel>, val special: Boolean = false)

    private lateinit var profile: Profile
    private lateinit var prefs: Prefs
    private lateinit var store: ProfileStore
    private lateinit var catList: RecyclerView
    private lateinit var list: RecyclerView
    private lateinit var previewView: PlayerView
    private lateinit var previewMsg: TextView
    private lateinit var chName: TextView
    private lateinit var chNow: TextView
    private lateinit var chNext: TextView
    private lateinit var chProgress: ProgressBar

    private var groups: List<Group> = emptyList()
    private var groupIdx = 0
    private var channels: List<LiveChannel> = emptyList()
    private var dead: Set<String> = emptySet()
    private val epg = HashMap<String, List<EpgEntry>>()
    private val epgLoading = HashSet<String>()
    private val epgLimiter = Semaphore(3)
    private var player: ExoPlayer? = null
    private var focusedPos = -1
    private var tunedId: String? = null
    private var infoJob: Job? = null
    private var longPressed = false
    private val handler = Handler(Looper.getMainLooper())
    private val tuneLater = Runnable { tunePreview() }
    private var pendingGroup = -1
    private val showGroupLater = Runnable { if (pendingGroup >= 0) showGroup(pendingGroup, focusChannel = null) }

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
        catList = findViewById(R.id.cats)
        list = findViewById(R.id.channels)
        previewView = findViewById(R.id.preview)
        previewMsg = findViewById(R.id.previewMsg)
        chName = findViewById(R.id.chName)
        chNow = findViewById(R.id.chNow)
        chNext = findViewById(R.id.chNext)
        chProgress = findViewById(R.id.chProgress)
        previewView.useController = false
        catList.layoutManager = LinearLayoutManager(this)
        catList.itemAnimator = null
        list.layoutManager = LinearLayoutManager(this)
        list.itemAnimator = null
        findViewById<View>(R.id.btnGuide).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java).putExtra(Nav.EXTRA_PROFILE, profile.id))
        }
        val lists = findViewById<View>(R.id.btnLists)
        // Los infantiles ya tienen sus propias categorías (las elige un adulto en Ajustes).
        if (profile.kids) lists.visibility = View.GONE
        lists.setOnClickListener {
            startActivity(Intent(this, KidsActivity::class.java).putExtra(KidsActivity.EXTRA_LIVE, true))
        }
        load()
    }

    private var shownHidden: Set<String> = emptySet()

    private fun load() {
        shownHidden = prefs.hiddenCategories
        previewMsg.text = "Cargando canales…"
        lifecycleScope.launch {
            try {
                Catalog.ensureLoaded(prefs)
                groups = withContext(Dispatchers.Default) { buildGroups() }
                if (groups.none { it.channels.isNotEmpty() }) {
                    previewMsg.text = if (profile.kids) "No hay canales infantiles todavía.\nUn adulto puede elegirlos en Ajustes."
                    else "No hay canales disponibles."
                    return@launch
                }
                catList.adapter = CatAdapter()
                list.adapter = ChannelAdapter()
                startOnLastChannel()
            } catch (e: Exception) {
                previewMsg.text = "No se pudieron cargar los canales.\n\n${XtreamClient.friendlyError(e)}"
            }
        }
    }

    /** Todos · ⭐ Favoritos · 🕘 Recientes · categorías (México primero), con los «sin señal» al final. */
    private fun buildGroups(): List<Group> {
        val v = CatalogView(prefs, profile)
        dead = DeadChannels.deadIds(this)
        fun aliveFirst(l: List<LiveChannel>) = l.sortedBy { if (it.id in dead) 1 else 0 }
        val cats = v.liveCats.map { c -> Group(c.name, aliveFirst(v.liveIn(c.id))) }.filter { it.channels.isNotEmpty() }
        val all = cats.flatMap { it.channels }.distinctBy { it.id }
        val favs = store.myList.filter { it.startsWith("live:") && v.canSee(it) }
            .mapNotNull { Catalog.liveById[it.removePrefix("live:")] }
        val recent = store.recentChannels().mapNotNull { Catalog.liveById[it] }.filter { v.canSee("live:${it.id}") }
        return listOf(
            Group("Todos los canales", all, special = true),
            Group("⭐  Favoritos", favs, special = true),
            Group("🕘  Recientes", recent, special = true),
        ) + cats
    }

    /** Abre en la categoría del último canal visto, con ese canal seleccionado. */
    private fun startOnLastChannel() {
        val last = store.recentChannels().firstOrNull()
        var g = if (last == null) -1 else groups.indexOfFirst { !it.special && it.channels.any { c -> c.id == last } }
        if (g < 0) g = groups.indexOfFirst { !it.special && it.channels.isNotEmpty() }.takeIf { it >= 0 } ?: 0
        showGroup(g, focusChannel = last ?: "")
    }

    private fun showGroup(g: Int, focusChannel: String?) {
        val old = groupIdx
        groupIdx = g
        channels = groups[g].channels
        catList.adapter?.notifyItemChanged(old)
        catList.adapter?.notifyItemChanged(g)
        list.adapter?.notifyDataSetChanged()
        list.scrollToPosition(0)
        if (focusChannel != null) {
            val idx = channels.indexOfFirst { it.id == focusChannel }.coerceAtLeast(0)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 120)
            list.post { list.layoutManager?.findViewByPosition(idx)?.requestFocus() }
            (catList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(g, 200)
        }
    }

    private fun focusCategories() {
        (catList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(groupIdx, 200)
        catList.post { catList.layoutManager?.findViewByPosition(groupIdx)?.requestFocus() }
    }

    private fun focusChannels() {
        if (channels.isEmpty()) return
        val idx = focusedPos.takeIf { it in channels.indices && channels[it].id == tunedId } ?: 0
        list.post {
            val v = list.layoutManager?.findViewByPosition(idx)
            if (v != null) v.requestFocus() else {
                list.scrollToPosition(idx)
                list.post { list.layoutManager?.findViewByPosition(idx)?.requestFocus() }
            }
        }
    }

    // ---------- Canal enfocado ----------

    private fun onChannelFocused(pos: Int) {
        val ch = channels.getOrNull(pos) ?: return
        focusedPos = pos
        chName.text = "${pos + 1}   ${ch.name}"
        showInfo(ch)
        handler.removeCallbacks(tuneLater)
        handler.postDelayed(tuneLater, 700)
    }

    private fun showInfo(ch: LiveChannel) {
        val list = epg[ch.id]
        if (list == null) {
            chNow.text = if (ch.id in dead) "Este canal no ha tenido señal últimamente" else "Buscando programación…"
            chNext.text = ""
            chProgress.visibility = View.GONE
            infoJob?.cancel()
            infoJob = lifecycleScope.launch {
                loadEpg(ch)
                if (channels.getOrNull(focusedPos)?.id == ch.id) showInfo(ch)
            }
            return
        }
        val now = list.firstOrNull()
        if (now == null) {
            chNow.text = if (ch.id in dead) "Este canal no ha tenido señal últimamente" else "Sin guía de programación"
            chNext.text = ""
            chProgress.visibility = View.GONE
            return
        }
        chNow.text = "Ahora: ${now.title}   (${t(now.start)} – ${t(now.end)})"
        chProgress.progress = progressOf(now)
        chProgress.visibility = View.VISIBLE
        chNext.text = list.getOrNull(1)?.let { "Después: ${it.title}   (${t(it.start)})" } ?: ""
    }

    private val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es", "MX"))
    private fun t(s: Long) = timeFmt.format(java.util.Date(s * 1000))
    private fun progressOf(e: EpgEntry): Int {
        val total = (e.end - e.start).coerceAtLeast(1)
        val done = (System.currentTimeMillis() / 1000 - e.start).coerceIn(0, total)
        return (done * 1000 / total).toInt()
    }

    private suspend fun loadEpg(ch: LiveChannel) {
        if (ch.id in epg) return
        val r = runCatching { epgLimiter.withPermit { Catalog.epgFor(prefs.client(), ch.id) } }.getOrDefault(emptyList())
        epg[ch.id] = r
    }

    /** Pide la programación de un canal que apareció en la lista y redibuja su tarjeta al llegar. */
    private fun requestRowEpg(ch: LiveChannel) {
        if (ch.id in epg || !epgLoading.add(ch.id)) return
        lifecycleScope.launch {
            loadEpg(ch)
            epgLoading.remove(ch.id)
            val pos = channels.indexOfFirst { it.id == ch.id }
            if (pos >= 0) list.post { if (!list.isComputingLayout) list.adapter?.notifyItemChanged(pos) }
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
        val ch = channels.getOrNull(focusedPos) ?: return
        if (tunedId == ch.id && player != null) return
        tunedId = ch.id
        previewMsg.text = "Cargando…"
        previewMsg.visibility = View.VISIBLE
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(prefs.client().liveUrl(ch.id, "ts")))
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
        if (focusedPos !in channels.indices) return
        val pos = focusedPos
        releasePlayer()
        // En pantalla completa, ▲ ▼ cambian de canal dentro de esta misma categoría.
        Nav.playLive(this, profile, channels, pos)
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    override fun onResume() {
        super.onResume()
        if (!::list.isInitialized) return
        // Si se cambiaron las listas visibles, volver a armar las categorías.
        if (prefs.hiddenCategories != shownHidden) {
            load()
            return
        }
        if (groups.isEmpty()) return
        // Al volver de pantalla completa: quedarse en el canal que se estaba viendo.
        val last = store.recentChannels().firstOrNull()
        val idx = channels.indexOfFirst { it.id == last }
        if (idx >= 0) {
            focusedPos = idx
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 120)
            list.post { list.layoutManager?.findViewByPosition(idx)?.requestFocus() }
            handler.postDelayed(tuneLater, 400)
        } else if (focusedPos >= 0) {
            handler.postDelayed(tuneLater, 400)
        }
    }

    // ---------- Teclas ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val focus = currentFocus
        val inChannels = focus?.parent === list
        val inCats = focus?.parent === catList
        val down = event.action == KeyEvent.ACTION_DOWN
        val center = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (inChannels) {
            if (center) {
                // Mantener OK = agregar o quitar de ⭐ Favoritos.
                if (down && event.repeatCount == 1) {
                    longPressed = true
                    toggleFavorite()
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    if (longPressed) longPressed = false else openFullScreen()
                }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (down) focusCategories()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) return true
        }
        if (inCats && down) {
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || center) {
                handler.removeCallbacks(showGroupLater)
                if (pendingGroup >= 0 && pendingGroup != groupIdx) showGroup(pendingGroup, focusChannel = null)
                if (channels.isEmpty()) return true
                focusedPos = 0
                tunedId = null
                list.scrollToPosition(0)
                list.post { list.layoutManager?.findViewByPosition(0)?.requestFocus() }
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) return true
        }
        if (inCats && center) return true
        return super.dispatchKeyEvent(event)
    }

    private fun onCategoryFocused(g: Int) {
        if (g == groupIdx) return
        // Cambiar la lista de canales un instante después, para que bajar rápido por las categorías no trabe.
        pendingGroup = g
        handler.removeCallbacks(showGroupLater)
        handler.postDelayed(showGroupLater, 250)
    }

    private fun toggleFavorite() {
        val ch = channels.getOrNull(focusedPos) ?: return
        val added = store.toggleList("live:${ch.id}")
        Toast.makeText(
            this,
            if (added) "⭐ ${ch.name} agregado a Favoritos" else "${ch.name} quitado de Favoritos",
            Toast.LENGTH_SHORT,
        ).show()
        val pos = focusedPos
        lifecycleScope.launch {
            val newGroups = withContext(Dispatchers.Default) { buildGroups() }
            val title = groups[groupIdx].title
            groups = newGroups
            catList.adapter?.notifyDataSetChanged()
            val g = groups.indexOfFirst { it.title == title }.coerceAtLeast(0)
            groupIdx = g
            channels = groups[g].channels
            list.adapter?.notifyDataSetChanged()
            val idx = channels.indexOfFirst { it.id == ch.id }.takeIf { it >= 0 } ?: pos.coerceAtMost(channels.size - 1)
            if (idx >= 0) {
                focusedPos = idx
                list.post { list.layoutManager?.findViewByPosition(idx)?.requestFocus() }
            } else {
                focusCategories()
            }
        }
    }

    // ---------- Listas ----------

    private inner class CatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_live_cat, parent, false)
            val vh = object : RecyclerView.ViewHolder(v) {}
            v.setOnFocusChangeListener { _, has ->
                val pos = vh.bindingAdapterPosition
                if (has && pos != RecyclerView.NO_POSITION) onCategoryFocused(pos)
            }
            return vh
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val g = groups[position]
            val v = holder.itemView
            v.findViewById<TextView>(R.id.catName).text = g.title
            v.findViewById<TextView>(R.id.catCount).text = g.channels.size.toString()
            v.isActivated = position == groupIdx
        }

        override fun getItemCount() = groups.size
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_live_channel, parent, false)
            val vh = object : RecyclerView.ViewHolder(v) {}
            v.setOnFocusChangeListener { _, has ->
                val pos = vh.bindingAdapterPosition
                if (has && pos != RecyclerView.NO_POSITION) onChannelFocused(pos)
            }
            return vh
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val ch = channels[position]
            val v = holder.itemView
            val fav = store.inList("live:${ch.id}")
            val num = v.findViewById<TextView>(R.id.num)
            val logo = v.findViewById<ImageView>(R.id.logo)
            num.text = "TV"
            num.visibility = View.VISIBLE
            logo.setImageDrawable(null)
            if (!ch.icon.isNullOrBlank()) {
                logo.load(ch.icon) {
                    listener(onSuccess = { _, _ -> num.visibility = View.INVISIBLE })
                }
            }
            v.findViewById<TextView>(R.id.name).text = "${position + 1}  " + (if (fav) "⭐ " else "") + ch.name
            val nowV = v.findViewById<TextView>(R.id.now)
            val nextV = v.findViewById<TextView>(R.id.next)
            val prog = v.findViewById<ProgressBar>(R.id.progress)
            val e = epg[ch.id]
            val now = e?.firstOrNull()
            when {
                ch.id in dead -> {
                    nowV.text = "Sin señal"
                    nextV.text = ""
                    prog.visibility = View.GONE
                }
                e == null -> {
                    nowV.text = "…"
                    nextV.text = ""
                    prog.visibility = View.GONE
                    requestRowEpg(ch)
                }
                now == null -> {
                    nowV.text = "Sin información"
                    nextV.text = ""
                    prog.visibility = View.GONE
                }
                else -> {
                    nowV.text = "${t(now.start)} – ${t(now.end)}   ${now.title}"
                    prog.progress = progressOf(now)
                    prog.visibility = View.VISIBLE
                    nextV.text = e.getOrNull(1)?.let { "${t(it.start)}  ${it.title}" } ?: ""
                }
            }
            v.alpha = if (ch.id in dead) 0.5f else 1f
        }

        override fun getItemCount() = channels.size
    }
}
